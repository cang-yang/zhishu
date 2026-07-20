#!/usr/bin/env python3
"""Shared fail-closed readers for ZH-F03 reliability evidence."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re


EXPERIMENT_ID = "ZH-EXP-F03-LIGHT-RELIABILITY"
BASELINE_ID = "ZH-BL-F03-CURRENT-DIRECT-KAFKA-V2"
CASE_IDS = tuple(f"F03-L-{index:03d}" for index in range(1, 7))
METRIC_IDS = tuple(f"ZH-M-F03-L0{index}" for index in range(1, 8))
RUN_TYPES = ("REAL", "FIXTURE_ONLY", "STUB")
RUN_TYPE_DIR_NAMES = {
    "REAL": "real",
    "FIXTURE_ONLY": "fixture-only",
    "STUB": "stub",
}
REQUIRED_MANIFEST_FIELDS = (
    "runId",
    "experimentId",
    "featureId",
    "project",
    "variant",
    "runType",
    "baselineId",
    "evidencePackageId",
    "cardVersion",
    "cardSectionId",
    "cardSectionSha256",
    "experimentDesignCardSha256",
    "plannedHarnessSpecHash",
    "collectionScriptSha256",
    "metricContractScriptSha256",
    "statScriptSha256",
    "verificationScriptSha256",
    "resultPathTemplateSha256",
    "executionProtocolVersion",
    "repoCommitSha",
    "dirtyDiffSha256",
    "buildArtifactHash",
    "environmentSnapshotId",
    "datasetManifestSha256",
    "configHash",
    "promptHash",
    "agentConfigHash",
    "toolManifestHash",
    "cacheState",
    "stateResetProof",
    "stateResetProofSha256",
    "randomSeed",
    "orderIndex",
    "startedAt",
    "completedAt",
    "command",
    "exitCode",
    "status",
    "round",
    "repetition",
    "executionOrder",
    "includedInStats",
    "exclusionReason",
    "rawResultPath",
    "rawResultSha256",
    "rawLogPath",
    "rawLogSha256",
)
SHA256_FIELDS = (
    "cardSectionSha256",
    "experimentDesignCardSha256",
    "plannedHarnessSpecHash",
    "collectionScriptSha256",
    "metricContractScriptSha256",
    "statScriptSha256",
    "verificationScriptSha256",
    "dirtyDiffSha256",
    "buildArtifactHash",
    "datasetManifestSha256",
    "configHash",
    "toolManifestHash",
    "stateResetProofSha256",
    "rawResultSha256",
    "rawLogSha256",
)
HEX_64 = re.compile(r"^[0-9A-Fa-f]{64}$")
GIT_SHA = re.compile(r"^[0-9A-Fa-f]{40,64}$")


class EvidenceError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def read_json(path: Path):
    try:
        with path.open("r", encoding="utf-8-sig") as handle:
            return json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        raise EvidenceError(f"cannot read JSON {path}: {exc}") from exc


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def resolve_run_type_dir(input_path: str, run_type: str) -> Path:
    root = Path(input_path).resolve()
    expected_name = RUN_TYPE_DIR_NAMES[run_type]
    if root.name == expected_name:
        target = root
    else:
        target = root / expected_name
    if not target.is_dir():
        raise EvidenceError(f"runType directory does not exist: {target}")
    return target


def _local_evidence_file(run_dir: Path, manifest: dict, field: str) -> Path:
    relative = manifest.get(field)
    if not isinstance(relative, str) or not relative.strip():
        raise EvidenceError(f"{run_dir}: manifest missing {field}")
    path = (run_dir / relative).resolve()
    try:
        path.relative_to(run_dir.resolve())
    except ValueError as exc:
        raise EvidenceError(f"{run_dir}: {field} escapes run directory") from exc
    if not path.is_file():
        raise EvidenceError(f"{run_dir}: evidence file missing: {relative}")
    return path


def _validate_manifest_contract(manifest_path: Path, manifest: dict, run_type: str) -> None:
    missing = [field for field in REQUIRED_MANIFEST_FIELDS if field not in manifest]
    if missing:
        raise EvidenceError(f"{manifest_path}: manifest missing {', '.join(missing)}")
    expected_values = {
        "experimentId": EXPERIMENT_ID,
        "featureId": "ZH-F03",
        "project": "zhishu",
        "variant": "after",
        "runType": run_type,
        "baselineId": BASELINE_ID,
        "cardVersion": "V3",
        "cardSectionId": "ZH-CARD-F03-LIGHT-V3",
        "executionProtocolVersion": "ZH-F03-LIGHT/1",
    }
    for field, expected in expected_values.items():
        if manifest.get(field) != expected:
            raise EvidenceError(
                f"{manifest_path}: {field}={manifest.get(field)} does not match {expected}"
            )
    for field in SHA256_FIELDS:
        if not isinstance(manifest.get(field), str) or not HEX_64.fullmatch(manifest[field]):
            raise EvidenceError(f"{manifest_path}: {field} is not a SHA-256")
    if not isinstance(manifest.get("repoCommitSha"), str) or not GIT_SHA.fullmatch(
        manifest["repoCommitSha"]
    ):
        raise EvidenceError(f"{manifest_path}: repoCommitSha is not a git object id")
    for field in (
        "runId",
        "evidencePackageId",
        "environmentSnapshotId",
        "startedAt",
        "completedAt",
        "command",
        "rawResultPath",
        "rawLogPath",
    ):
        if not isinstance(manifest.get(field), str) or not manifest[field].strip():
            raise EvidenceError(f"{manifest_path}: {field} must be non-empty")
    for field in ("orderIndex", "round", "repetition", "executionOrder"):
        if not isinstance(manifest.get(field), int) or manifest[field] < 1:
            raise EvidenceError(f"{manifest_path}: {field} must be a positive integer")
    if manifest.get("cacheState") not in ("COLD", "HOT", "RESET", "NOT_APPLICABLE"):
        raise EvidenceError(f"{manifest_path}: invalid cacheState")
    if manifest.get("status") not in ("PASS", "FAIL", "TIMEOUT", "ABORTED"):
        raise EvidenceError(f"{manifest_path}: invalid status")
    if not isinstance(manifest.get("includedInStats"), bool):
        raise EvidenceError(f"{manifest_path}: includedInStats must be boolean")
    if run_type == "REAL" and manifest.get("runtimeMode") == "WINDOWS_LOCAL":
        for field in (
            "nativeProcessControllerSha256",
            "authPreparationScriptSha256",
            "applicationEnvSha256",
        ):
            if not isinstance(manifest.get(field), str) or not HEX_64.fullmatch(manifest[field]):
                raise EvidenceError(f"{manifest_path}: {field} is not a SHA-256")


def _validate_raw_metrics(path: Path, manifest: dict) -> list[dict]:
    required = (
        "runId",
        "experimentId",
        "featureId",
        "sampleId",
        "variant",
        "runType",
        "metricId",
        "value",
        "unit",
        "round",
        "repetition",
        "executionOrder",
        "startedAt",
        "elapsedMs",
        "sampleStatus",
        "httpStatus",
        "exitCode",
        "pass",
        "errorCode",
        "includedInStats",
        "exclusionReason",
        "metricSchemaVersion",
        "collectorVersion",
        "requestParamHash",
    )
    rows = []
    try:
        with path.open("r", encoding="utf-8-sig") as handle:
            for line_number, line in enumerate(handle, start=1):
                if not line.strip():
                    continue
                row = json.loads(line)
                if not isinstance(row, dict):
                    raise EvidenceError(f"{path}:{line_number}: raw metric must be an object")
                missing = [field for field in required if field not in row]
                if missing:
                    raise EvidenceError(
                        f"{path}:{line_number}: raw metric missing {', '.join(missing)}"
                    )
                rows.append(row)
    except (OSError, json.JSONDecodeError) as exc:
        raise EvidenceError(f"{path}: cannot read raw metric JSONL: {exc}") from exc
    if not rows:
        raise EvidenceError(f"{path}: raw metric JSONL is empty")
    metric_ids = [row["metricId"] for row in rows]
    if sorted(metric_ids) != sorted(METRIC_IDS):
        raise EvidenceError(f"{path}: raw metric set must be exactly {', '.join(METRIC_IDS)}")
    expected_status = {
        "PASS": "PASS",
        "FAIL": "FAIL",
        "ABORTED": "ABORTED",
    }[manifest["status"]]
    for row in rows:
        expected_values = {
            "runId": manifest["runId"],
            "experimentId": EXPERIMENT_ID,
            "featureId": "ZH-F03",
            "sampleId": manifest["caseId"],
            "variant": "after",
            "runType": manifest["runType"],
            "round": manifest["round"],
            "repetition": manifest["repetition"],
            "executionOrder": manifest["executionOrder"],
            "sampleStatus": expected_status,
            "exitCode": manifest["exitCode"],
            "includedInStats": manifest["includedInStats"],
            "exclusionReason": manifest["exclusionReason"],
            "metricSchemaVersion": "ZH-F03-METRIC/1",
            "collectorVersion": manifest["collectionScriptSha256"],
        }
        for field, expected in expected_values.items():
            if row.get(field) != expected:
                raise EvidenceError(f"{path}: raw metric {field} does not match manifest")
        if not isinstance(row.get("pass"), bool):
            raise EvidenceError(f"{path}: raw metric pass must be boolean")
        if not isinstance(row.get("requestParamHash"), str) or not HEX_64.fullmatch(
            row["requestParamHash"]
        ):
            raise EvidenceError(f"{path}: raw metric requestParamHash is not SHA-256")
    return rows


def load_attempts(input_path: str, run_type: str, minimum_attempts: int):
    if run_type not in RUN_TYPES:
        raise EvidenceError(f"unsupported runType: {run_type}")
    if minimum_attempts < 1:
        raise EvidenceError("minimum-attempts must be >= 1")
    run_type_dir = resolve_run_type_dir(input_path, run_type)
    manifests = sorted(run_type_dir.rglob("run-manifest.json"))
    if not manifests:
        raise EvidenceError(f"no run-manifest.json under {run_type_dir}")

    attempts = []
    seen_run_ids = set()
    for manifest_path in manifests:
        run_dir = manifest_path.parent
        manifest = read_json(manifest_path)
        _validate_manifest_contract(manifest_path, manifest, run_type)
        case_id = manifest.get("caseId")
        if case_id not in CASE_IDS:
            raise EvidenceError(f"{manifest_path}: invalid caseId={case_id}")
        run_id = manifest.get("runId")
        if not isinstance(run_id, str) or not run_id:
            raise EvidenceError(f"{manifest_path}: missing runId")
        if run_id in seen_run_ids:
            raise EvidenceError(f"duplicate runId: {run_id}")
        seen_run_ids.add(run_id)

        evidence_specs = (
            ("rawResultPath", "rawResultSha256"),
            ("rawLogPath", "rawLogSha256"),
            ("stateResetProof", "stateResetProofSha256"),
            ("rawStateFile", "rawStateSha256"),
            ("environmentSnapshotFile", "environmentSnapshotSha256"),
            ("rawLogFile", "rawLogSha256"),
            ("commandFile", "commandSha256"),
        )
        files = {}
        for file_field, hash_field in evidence_specs:
            path = _local_evidence_file(run_dir, manifest, file_field)
            expected_hash = manifest.get(hash_field)
            actual_hash = sha256(path)
            if not isinstance(expected_hash, str) or expected_hash.upper() != actual_hash:
                raise EvidenceError(
                    f"{manifest_path}: {hash_field} mismatch expected={expected_hash} actual={actual_hash}"
                )
            files[file_field] = path

        raw_metrics = _validate_raw_metrics(files["rawResultPath"], manifest)

        raw = read_json(files["rawStateFile"])
        for field, expected in (
            ("experimentId", EXPERIMENT_ID),
            ("runType", run_type),
            ("caseId", case_id),
            ("runId", run_id),
        ):
            if raw.get(field) != expected:
                raise EvidenceError(
                    f"{files['rawStateFile']}: {field}={raw.get(field)} does not match {expected}"
                )
        if raw.get("attempt") != manifest.get("attempt"):
            raise EvidenceError(f"{manifest_path}: attempt differs between manifest and raw state")
        if raw.get("recordedOutcome") not in ("PASS", "FAIL", "BLOCKED_BY_ENVIRONMENT"):
            raise EvidenceError(f"{manifest_path}: invalid recordedOutcome")
        expected_status = {
            "PASS": "PASS",
            "FAIL": "FAIL",
            "BLOCKED_BY_ENVIRONMENT": "ABORTED",
        }[raw["recordedOutcome"]]
        if manifest.get("status") != expected_status:
            raise EvidenceError(f"{manifest_path}: status does not match recordedOutcome")
        expected_inclusion = raw["recordedOutcome"] != "BLOCKED_BY_ENVIRONMENT"
        if manifest.get("includedInStats") is not expected_inclusion:
            raise EvidenceError(f"{manifest_path}: includedInStats does not match outcome")
        if not expected_inclusion and not str(manifest.get("exclusionReason", "")).strip():
            raise EvidenceError(f"{manifest_path}: excluded run requires exclusionReason")
        attempts.append(
            {
                "manifestPath": str(manifest_path),
                "runDir": str(run_dir),
                "manifest": manifest,
                "raw": raw,
                "rawMetrics": raw_metrics,
            }
        )

    counts = {case_id: 0 for case_id in CASE_IDS}
    for attempt in attempts:
        counts[attempt["manifest"]["caseId"]] += 1
    missing = [case_id for case_id, count in counts.items() if count < minimum_attempts]
    if missing:
        detail = ", ".join(f"{case_id}={counts[case_id]}" for case_id in missing)
        raise EvidenceError(
            f"incomplete six-case matrix; minimumAttempts={minimum_attempts}; {detail}"
        )
    return run_type_dir, attempts, counts
