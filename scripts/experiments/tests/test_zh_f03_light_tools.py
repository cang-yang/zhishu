#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
from datetime import datetime, timedelta, timezone


ROOT = Path(__file__).resolve().parents[3]
SCRIPTS = ROOT / "scripts" / "experiments"
STAT = SCRIPTS / "zh_f03_light_stat.py"
VERIFY = SCRIPTS / "zh_f03_light_verify.py"
ORCHESTRATOR = SCRIPTS / "zh-f03-light-reliability.ps1"
METRIC_CONTRACT = SCRIPTS / "zh-f03-light-metric-contract.ps1"
REAL_DRIVER = SCRIPTS / "zh_f03_real_fault_driver.py"
FREEZE = SCRIPTS / "zh_f03_light_freeze.py"
CASES = [f"F03-L-{index:03d}" for index in range(1, 7)]
RUN_TYPE_DIRS = {"REAL": "real", "FIXTURE_ONLY": "fixture-only", "STUB": "stub"}
CANONICAL_LOCAL_DRIVER_TEMPLATE = (
    ROOT.parent
    / "Zhishu-main"
    / ".local"
    / "项目功能改造与指标评审"
    / "phase3"
    / "zhishu"
    / "ZH-F03"
    / "config"
    / "zh-f03-real-driver.local.template.json"
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def case_assertions(case_id: str):
    values = {
        "F03-L-001": {"outboxPublished": True, "taskProcessingOpportunity": True},
        "F03-L-002": {
            "duplicateDeliveryObserved": True,
            "hashConflictRejected": True,
            "esIdSetUnchanged": True,
        },
        "F03-L-003": {"leaseRecovered": True, "finalCompleted": True},
        "F03-L-004": {"bulkItemFailureObserved": True, "activeVersionUnchanged": True},
        "F03-L-005": {
            "dltFailedObserved": True,
            "reprocessCreatedNewVersion": True,
            "finalCompleted": True,
        },
        "F03-L-006": {"mysqlFinalizeRolledBack": True, "minioObjectReusable": True},
    }
    return values[case_id]


def case_evidence(case_id: str):
    started = datetime(2026, 7, 18, tzinfo=timezone.utc)
    completed = started + timedelta(seconds=90 if case_id == "F03-L-005" else 60)
    common = {
        "observation": {
            "startedAt": started.isoformat().replace("+00:00", "Z"),
            "completedAt": completed.isoformat().replace("+00:00", "Z"),
        },
        "faultEvents": [],
        "taskHistory": [],
        "outboxHistory": [],
        "fileHistory": [],
    }
    if case_id == "F03-L-001":
        common["faultEvents"] = [
            {"type": "KAFKA_UNAVAILABLE", "capturedAt": "2026-07-18T00:00:05Z"},
            {"type": "KAFKA_RESTORED", "capturedAt": "2026-07-18T00:00:20Z"},
        ]
        common["outboxHistory"] = [
            {"status": "NEW", "attemptCount": 0},
            {"status": "SENDING", "attemptCount": 1},
            {"status": "PUBLISHED", "attemptCount": 1},
        ]
        common["taskHistory"] = [{"status": "PENDING"}, {"status": "PROCESSING"}]
    elif case_id == "F03-L-002":
        common["faultEvents"] = [
            {"type": "DUPLICATE_KAFKA_DELIVERY", "capturedAt": "2026-07-18T00:00:10Z"},
            {"type": "MYSQL_HASH_CONFLICT", "capturedAt": "2026-07-18T00:00:20Z"},
        ]
        common["kafkaDelivery"] = {
            "eventId": "event-2",
            "duplicateEventId": "event-2",
            "offsetBefore": 10,
            "offsetAfter": 11,
        }
        common["idempotency"] = {
            "mysqlBusinessKeysBefore": ["10:2:0"],
            "mysqlBusinessKeysAfter": ["10:2:0"],
            "esIdsBefore": ["10:2:0"],
            "esIdsAfter": ["10:2:0"],
            "hashConflict": {
                "existingHash": "aaa",
                "attemptedHash": "bbb",
                "sqlState": "23000",
            },
        }
    elif case_id == "F03-L-003":
        common["faultEvents"] = [
            {"type": "WORKER_TERMINATED", "capturedAt": "2026-07-18T00:00:10Z"},
            {"type": "LEASE_EXPIRED", "capturedAt": "2026-07-18T00:00:20Z"},
        ]
        common["taskHistory"] = [
            {"status": "PROCESSING", "retryCount": 0},
            {"status": "RETRY_WAIT", "retryCount": 1},
            {"status": "COMPLETED", "retryCount": 1},
        ]
        common["outboxHistory"] = [{"status": "NEW"}, {"status": "PUBLISHED"}]
    elif case_id == "F03-L-004":
        common["faultEvents"] = [
            {"type": "ES_BULK_ITEM_REJECTED", "capturedAt": "2026-07-18T00:00:10Z"}
        ]
        common["esBulk"] = {
            "failedItems": [{"documentId": "10:2:1", "status": 429, "errorType": "rejected"}]
        }
        common["fileHistory"] = [
            {"activeProcessingVersion": 1},
            {"activeProcessingVersion": 1},
        ]
    elif case_id == "F03-L-005":
        common["faultEvents"] = [
            {"type": "MINIO_SOURCE_UNAVAILABLE", "capturedAt": "2026-07-18T00:00:10Z"},
            {"type": "DLT_RECORD_OBSERVED", "capturedAt": "2026-07-18T00:00:40Z"},
        ]
        common["dlt"] = {"offsetBefore": 20, "offsetAfter": 21}
        common["taskHistory"] = [
            {"taskId": "old", "status": "FAILED", "processingVersion": 1},
            {"taskId": "new", "status": "COMPLETED", "processingVersion": 2},
        ]
        common["reprocess"] = {"httpStatus": 200, "oldVersion": 1, "newVersion": 2}
    elif case_id == "F03-L-006":
        common["faultEvents"] = [
            {"type": "MYSQL_FINALIZE_REJECTED", "capturedAt": "2026-07-18T00:00:10Z"}
        ]
        common["transaction"] = {
            "httpStatus": 500,
            "before": {"fileVersion": 0, "taskRows": 0, "outboxRows": 0},
            "afterFailure": {"fileVersion": 0, "taskRows": 0, "outboxRows": 0},
            "retryHttpStatus": 200,
        }
        common["minioProof"] = {"mergedObjectBeforeRetry": True, "mergedObjectAfterRetry": True}
    return common


def write_run(root: Path, case_id: str, run_type="FIXTURE_ONLY", outcome="PASS") -> Path:
    run_id = f"{case_id.lower()}-attempt-01"
    run_dir = root / RUN_TYPE_DIRS[run_type] / case_id / run_id
    run_dir.mkdir(parents=True)
    if case_id == "F03-L-004":
        task = {"status": "FAILED", "processingVersion": 2}
        file_state = {"latestProcessingVersion": 2, "activeProcessingVersion": 1}
        expected_ids = ["10:1:0"]
        active_chunks = [
            {"fileUploadId": 10, "processingVersion": 1, "chunkId": 0, "contentHash": "old"}
        ]
    elif case_id == "F03-L-006":
        task = None
        file_state = {"latestProcessingVersion": 0, "activeProcessingVersion": None}
        expected_ids = []
        active_chunks = []
    else:
        task = {"status": "COMPLETED", "processingVersion": 2}
        file_state = {"latestProcessingVersion": 2, "activeProcessingVersion": 2}
        expected_ids = ["10:2:0"]
        active_chunks = [
            {"fileUploadId": 10, "processingVersion": 2, "chunkId": 0, "contentHash": "abc"}
        ]
    raw = {
        "experimentId": "ZH-EXP-F03-LIGHT-RELIABILITY",
        "runType": run_type,
        "caseId": case_id,
        "runId": run_id,
        "attempt": 1,
        "recordedOutcome": outcome,
        "task": task,
        "outbox": {"status": "PUBLISHED"} if case_id != "F03-L-006" else None,
        "file": file_state,
        "chunks": [] if case_id == "F03-L-006" else [
            {"fileUploadId": 10, "processingVersion": task["processingVersion"], "chunkId": 0, "contentHash": "abc"}
        ],
        "activeChunks": active_chunks,
        "expectedActiveEsIds": expected_ids,
        "actualActiveEsIds": list(expected_ids),
        "kafka": {"topic": "document-processing-v2", "dltTopic": "document-processing-v2-dlt", "offsets": [1]},
        "minio": {"objectKey": "merged/fixture", "reusable": True},
        "searchHits": [],
        "permissionChecks": [
            {"actor": "owner", "status": 200},
            {"actor": "admin", "status": 200},
            {"actor": "other", "status": 403},
        ],
        "legacyVisibilityChecks": [
            {
                "fileOwnerUserId": "101",
                "queryOwnerUserId": "101",
                "expectedVisible": True,
                "actualVisible": True,
            }
        ],
        "caseAssertions": case_assertions(case_id),
    }
    raw.update(case_evidence(case_id))
    raw_path = run_dir / "raw-state.json"
    raw_path.write_text(json.dumps(raw, ensure_ascii=False, indent=2), encoding="utf-8")
    env_path = run_dir / "environment-snapshot.json"
    env_path.write_text(json.dumps({"fixture": True, "runType": run_type}), encoding="utf-8")
    log_path = run_dir / "raw.log"
    log_path.write_text("fixture-only tool contract test\n", encoding="utf-8")
    command_path = run_dir / "command.txt"
    command_path.write_text("fixture-only\n", encoding="utf-8")
    reset_path = run_dir / "state-reset-proof.json"
    reset_path.write_text(json.dumps({"fixtureReset": True}), encoding="utf-8")
    frozen_hash = "A" * 64
    raw_result_path = run_dir / "raw-results.jsonl"
    raw_metric_rows = []
    for metric_id in [f"ZH-M-F03-L0{index}" for index in range(1, 8)]:
        raw_metric_rows.append(
            json.dumps(
                {
                    "runId": run_id,
                    "experimentId": "ZH-EXP-F03-LIGHT-RELIABILITY",
                    "featureId": "ZH-F03",
                    "sampleId": case_id,
                    "variant": "after",
                    "runType": run_type,
                    "metricId": metric_id,
                    "value": 0 if metric_id in {"ZH-M-F03-L01", "ZH-M-F03-L02", "ZH-M-F03-L03"} else 1,
                    "unit": "fixture",
                    "round": 1,
                    "repetition": 1,
                    "executionOrder": 1,
                    "startedAt": "2026-07-18T00:00:00Z",
                    "elapsedMs": 1,
                    "sampleStatus": "PASS",
                    "httpStatus": None,
                    "exitCode": 0,
                    "pass": True,
                    "errorCode": "",
                    "includedInStats": True,
                    "exclusionReason": "",
                    "metricSchemaVersion": "ZH-F03-METRIC/1",
                    "collectorVersion": frozen_hash,
                    "requestParamHash": frozen_hash,
                },
                ensure_ascii=False,
            )
        )
    raw_result_path.write_text("\n".join(raw_metric_rows) + "\n", encoding="utf-8")
    manifest = {
        "featureId": "ZH-F03",
        "project": "zhishu",
        "variant": "after",
        "experimentId": "ZH-EXP-F03-LIGHT-RELIABILITY",
        "baselineId": "ZH-BL-F03-CURRENT-DIRECT-KAFKA-V2",
        "evidencePackageId": f"fixture-{run_id}",
        "cardVersion": "V3",
        "cardSectionId": "ZH-CARD-F03-LIGHT-V3",
        "cardSectionSha256": frozen_hash,
        "experimentDesignCardSha256": frozen_hash,
        "plannedHarnessSpecHash": frozen_hash,
        "collectionScriptSha256": frozen_hash,
        "metricContractScriptSha256": frozen_hash,
        "statScriptSha256": frozen_hash,
        "verificationScriptSha256": frozen_hash,
        "resultPathTemplateSha256": "NOT_APPLICABLE:ZHISHU",
        "executionProtocolVersion": "ZH-F03-LIGHT/1",
        "repoCommitSha": "a" * 40,
        "dirtyDiffSha256": frozen_hash,
        "buildArtifactHash": frozen_hash,
        "environmentSnapshotId": f"env-{run_id}",
        "datasetManifestSha256": frozen_hash,
        "configHash": frozen_hash,
        "promptHash": "NOT_APPLICABLE",
        "agentConfigHash": "NOT_APPLICABLE",
        "toolManifestHash": frozen_hash,
        "cacheState": "RESET",
        "stateResetProof": "state-reset-proof.json",
        "stateResetProofSha256": sha256(reset_path),
        "randomSeed": None,
        "orderIndex": 1,
        "startedAt": "2026-07-18T00:00:00Z",
        "completedAt": "2026-07-18T00:00:01Z",
        "command": "fixture-only",
        "exitCode": 0,
        "status": "PASS",
        "round": 1,
        "repetition": 1,
        "executionOrder": 1,
        "includedInStats": True,
        "exclusionReason": "",
        "runType": run_type,
        "caseId": case_id,
        "runId": run_id,
        "attempt": 1,
        "rawResultPath": "raw-results.jsonl",
        "rawResultSha256": sha256(raw_result_path),
        "rawLogPath": "raw.log",
        "rawLogSha256": sha256(log_path),
        "rawStateFile": "raw-state.json",
        "rawStateSha256": sha256(raw_path),
        "environmentSnapshotFile": "environment-snapshot.json",
        "environmentSnapshotSha256": sha256(env_path),
        "rawLogFile": "raw.log",
        "rawLogSha256": sha256(log_path),
        "commandFile": "command.txt",
        "commandSha256": sha256(command_path),
        "runtimeMode": "WINDOWS_LOCAL",
        "nativeProcessControllerSha256": frozen_hash,
        "authPreparationScriptSha256": frozen_hash,
        "applicationEnvSha256": frozen_hash,
    }
    (run_dir / "run-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return run_dir


class ZhF03LightToolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        for case_id in CASES:
            write_run(self.root, case_id)

    def tearDown(self):
        self.temp.cleanup()

    def run_python(self, script: Path, *args):
        return subprocess.run(
            [sys.executable, str(script), *map(str, args)],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_stat_requires_complete_unmixed_matrix_and_writes_summary(self):
        result = self.run_python(
            STAT, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        summary = json.loads((self.root / "fixture-only" / "stat-summary.json").read_text(encoding="utf-8"))
        self.assertEqual(6, summary["casePassCount"])
        self.assertEqual(6, summary["attemptPassCount"])
        self.assertTrue(summary["matrixPass"])

    def test_stat_fails_closed_on_mixed_run_type_or_tampered_raw_state(self):
        manifest_path = next((self.root / "fixture-only").rglob("run-manifest.json"))
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["runType"] = "REAL"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        result = self.run_python(
            STAT, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("runType", result.stderr + result.stdout)

    def test_stat_fails_closed_when_required_run_identity_is_missing(self):
        manifest_path = next((self.root / "fixture-only").rglob("run-manifest.json"))
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        del manifest["featureId"]
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        result = self.run_python(
            STAT, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("featureId", result.stderr + result.stdout)

    def test_stat_fails_closed_when_raw_metric_long_table_is_invalid(self):
        run_dir = next((self.root / "fixture-only" / "F03-L-001").iterdir())
        raw_result_path = run_dir / "raw-results.jsonl"
        raw_result_path.write_text("{}\n", encoding="utf-8")
        manifest_path = run_dir / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["rawResultSha256"] = sha256(raw_result_path)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        result = self.run_python(
            STAT, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("raw metric", result.stderr + result.stdout)

    def test_stat_fails_closed_when_an_included_raw_metric_fails(self):
        run_dir = next((self.root / "fixture-only" / "F03-L-001").iterdir())
        raw_result_path = run_dir / "raw-results.jsonl"
        rows = [json.loads(line) for line in raw_result_path.read_text(encoding="utf-8").splitlines()]
        target = next(row for row in rows if row["metricId"] == "ZH-M-F03-L07")
        target["pass"] = False
        raw_result_path.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
            encoding="utf-8",
        )
        manifest_path = run_dir / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["rawResultSha256"] = sha256(raw_result_path)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        result = self.run_python(
            STAT, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )

        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        summary = json.loads((self.root / "fixture-only" / "stat-summary.json").read_text(encoding="utf-8"))
        self.assertFalse(summary["matrixPass"])
        l07 = next(item for item in summary["metrics"] if item["metricId"] == "ZH-M-F03-L07")
        self.assertEqual(1, l07["failCount"])
        self.assertFalse(l07["pass"])

    def test_verify_fails_closed_when_an_included_raw_metric_fails(self):
        run_dir = next((self.root / "fixture-only" / "F03-L-001").iterdir())
        raw_result_path = run_dir / "raw-results.jsonl"
        rows = [json.loads(line) for line in raw_result_path.read_text(encoding="utf-8").splitlines()]
        next(row for row in rows if row["metricId"] == "ZH-M-F03-L07")["pass"] = False
        raw_result_path.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
            encoding="utf-8",
        )
        manifest_path = run_dir / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["rawResultSha256"] = sha256(raw_result_path)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        result = self.run_python(
            VERIFY, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )

        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        verdict = json.loads((self.root / "fixture-only" / "verify-verdict.json").read_text(encoding="utf-8"))
        failed = next(item for item in verdict["attempts"] if item["caseId"] == "F03-L-001")
        self.assertIn("raw_metric_pass", failed["failedChecks"])

    def test_verify_recomputes_invariants_instead_of_trusting_recorded_pass(self):
        run_dir = next((self.root / "fixture-only" / "F03-L-002").iterdir())
        raw_path = run_dir / "raw-state.json"
        raw = json.loads(raw_path.read_text(encoding="utf-8"))
        raw["actualActiveEsIds"].append("unexpected-id")
        raw_path.write_text(json.dumps(raw), encoding="utf-8")
        manifest_path = run_dir / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["rawStateSha256"] = sha256(raw_path)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        result = self.run_python(
            VERIFY, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )
        self.assertNotEqual(0, result.returncode)
        verdict = json.loads((self.root / "fixture-only" / "verify-verdict.json").read_text(encoding="utf-8"))
        failed = [item for item in verdict["attempts"] if item["caseId"] == "F03-L-002"]
        self.assertFalse(failed[0]["pass"])
        self.assertIn("es_id_set", failed[0]["failedChecks"])

    def test_verify_recomputes_deterministic_ids_from_active_chunk_keys(self):
        run_dir = next((self.root / "fixture-only" / "F03-L-002").iterdir())
        raw_path = run_dir / "raw-state.json"
        raw = json.loads(raw_path.read_text(encoding="utf-8"))
        raw["expectedActiveEsIds"] = ["forged-id"]
        raw["actualActiveEsIds"] = ["forged-id"]
        raw_path.write_text(json.dumps(raw), encoding="utf-8")
        manifest_path = run_dir / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["rawStateSha256"] = sha256(raw_path)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        result = self.run_python(
            VERIFY, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )
        self.assertNotEqual(0, result.returncode)
        verdict = json.loads((self.root / "fixture-only" / "verify-verdict.json").read_text(encoding="utf-8"))
        failed = [item for item in verdict["attempts"] if item["caseId"] == "F03-L-002"]
        self.assertIn("es_id_formula", failed[0]["failedChecks"])

    def test_verify_rejects_self_reported_assertions_without_fault_facts(self):
        run_dir = next((self.root / "fixture-only" / "F03-L-001").iterdir())
        raw_path = run_dir / "raw-state.json"
        raw = json.loads(raw_path.read_text(encoding="utf-8"))
        raw.pop("faultEvents")
        raw.pop("outboxHistory")
        raw.pop("taskHistory")
        raw["caseAssertions"] = case_assertions("F03-L-001")
        raw_path.write_text(json.dumps(raw), encoding="utf-8")
        manifest_path = run_dir / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["rawStateSha256"] = sha256(raw_path)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        result = self.run_python(
            VERIFY, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )
        self.assertNotEqual(0, result.returncode)
        verdict = json.loads((self.root / "fixture-only" / "verify-verdict.json").read_text(encoding="utf-8"))
        failed = next(item for item in verdict["attempts"] if item["caseId"] == "F03-L-001")
        self.assertIn("fault_proof:KAFKA_UNAVAILABLE", failed["failedChecks"])

    def test_verify_rejects_case_completed_after_observation_window(self):
        run_dir = next((self.root / "fixture-only" / "F03-L-001").iterdir())
        raw_path = run_dir / "raw-state.json"
        raw = json.loads(raw_path.read_text(encoding="utf-8"))
        raw["observation"]["completedAt"] = "2026-07-18T00:02:01Z"
        raw_path.write_text(json.dumps(raw), encoding="utf-8")
        manifest_path = run_dir / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["rawStateSha256"] = sha256(raw_path)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        result = self.run_python(
            VERIFY, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )
        self.assertNotEqual(0, result.returncode)
        verdict = json.loads((self.root / "fixture-only" / "verify-verdict.json").read_text(encoding="utf-8"))
        failed = next(item for item in verdict["attempts"] if item["caseId"] == "F03-L-001")
        self.assertIn("observation_window", failed["failedChecks"])

    def test_verify_accepts_complete_hash_bound_fixture_matrix(self):
        result = self.run_python(
            VERIFY, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        verdict = json.loads((self.root / "fixture-only" / "verify-verdict.json").read_text(encoding="utf-8"))
        self.assertTrue(verdict["verifyPass"])
        self.assertEqual(6, verdict["casePassCount"])

    def test_verify_accepts_task_history_serialized_by_the_real_driver(self):
        sys.path.insert(0, str(SCRIPTS))
        try:
            from zh_f03_real_fault_driver import RealFaultDriver
        finally:
            sys.path.pop(0)
        driver = RealFaultDriver.__new__(RealFaultDriver)
        run_dir = next((self.root / "fixture-only" / "F03-L-003").iterdir())
        raw_path = run_dir / "raw-state.json"
        raw = json.loads(raw_path.read_text(encoding="utf-8"))
        raw["taskHistory"] = [
            driver.json_safe({"status": "PROCESSING", "retry_count": 0}),
            driver.json_safe({"status": "RETRY_WAIT", "retry_count": 1}),
            driver.json_safe({"status": "COMPLETED", "retry_count": 1}),
        ]
        raw_path.write_text(json.dumps(raw), encoding="utf-8")
        manifest_path = run_dir / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["rawStateSha256"] = sha256(raw_path)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        result = self.run_python(
            VERIFY, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_metric_collector_accepts_visible_legacy_document_owned_by_another_user(self):
        shell = shutil.which("pwsh") or shutil.which("powershell")
        if not shell:
            self.skipTest("PowerShell is not available")
        helper_path = str(METRIC_CONTRACT).replace("'", "''")
        probe = self.root / "metric-contract-probe.ps1"
        probe.write_text(
            ". '" + helper_path + "'\n"
            "$checks = @([pscustomobject]@{ fileOwnerUserId = '202'; queryOwnerUserId = '101'; "
            "expectedVisible = $true; actualVisible = $true })\n"
            "if (Test-ZhF03LegacyVisibilityChecks -Checks $checks) { exit 0 }\n"
            "exit 1\n",
            encoding="utf-8",
        )

        result = subprocess.run(
            [shell, "-NoProfile", "-File", str(probe)],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_verify_accepts_observable_outbox_retry_without_sampling_transient_sending(self):
        run_dir = next((self.root / "fixture-only" / "F03-L-001").iterdir())
        raw_path = run_dir / "raw-state.json"
        raw = json.loads(raw_path.read_text(encoding="utf-8"))
        raw["outboxHistory"] = [
            {"status": "NEW", "retryCount": 0},
            {"status": "NEW", "retryCount": 1},
            {"status": "PUBLISHED", "retryCount": 1},
        ]
        raw_path.write_text(json.dumps(raw), encoding="utf-8")
        manifest_path = run_dir / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["rawStateSha256"] = sha256(raw_path)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        result = self.run_python(
            VERIFY, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_verify_accepts_mysql_duplicate_key_error_code_without_fake_sqlstate(self):
        run_dir = next((self.root / "fixture-only" / "F03-L-002").iterdir())
        raw_path = run_dir / "raw-state.json"
        raw = json.loads(raw_path.read_text(encoding="utf-8"))
        conflict = raw["idempotency"]["hashConflict"]
        conflict.pop("sqlState")
        conflict["errorCode"] = 1062
        raw_path.write_text(json.dumps(raw), encoding="utf-8")
        manifest_path = run_dir / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["rawStateSha256"] = sha256(raw_path)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        result = self.run_python(
            VERIFY, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_verify_accepts_visible_legacy_document_owned_by_another_user(self):
        run_dir = next((self.root / "fixture-only" / "F03-L-001").iterdir())
        raw_path = run_dir / "raw-state.json"
        raw = json.loads(raw_path.read_text(encoding="utf-8"))
        raw["legacyVisibilityChecks"][0]["fileOwnerUserId"] = "202"
        raw["legacyVisibilityChecks"][0]["queryOwnerUserId"] = "101"
        raw_path.write_text(json.dumps(raw), encoding="utf-8")
        manifest_path = run_dir / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["rawStateSha256"] = sha256(raw_path)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        result = self.run_python(
            VERIFY, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_real_driver_materializes_frozen_case_with_unique_run_marker(self):
        sys.path.insert(0, str(SCRIPTS))
        try:
            from zh_f03_real_fault_driver import materialize_dataset
        finally:
            sys.path.pop(0)
        manifest = {
            "schemaVersion": "ZH-F03-DATASET/1",
            "cases": {
                "F03-L-001": {
                    "fileName": "case-{{RUN_ID}}.txt",
                    "contentUtf8": "fixed ZH_F03_{{RUN_ID}}",
                    "searchMarker": "ZH_F03_{{RUN_ID}}",
                }
            },
        }
        name, payload, marker = materialize_dataset(manifest, "F03-L-001", "run-7")
        self.assertEqual("case-run-7.txt", name)
        self.assertEqual("ZH_F03_run-7", marker)
        self.assertIn(marker.encode(), payload)

    def test_real_driver_es_write_block_backup_round_trips_exact_original_value(self):
        sys.path.insert(0, str(SCRIPTS))
        try:
            from zh_f03_real_fault_driver import (
                load_es_write_block_backup,
                persist_es_write_block_backup,
            )
        finally:
            sys.path.pop(0)

        for original in (None, "true", "false"):
            persist_es_write_block_backup(self.root, original)
            self.assertEqual(original, load_es_write_block_backup(self.root))

    def test_orchestrator_rejects_arbitrary_real_driver_even_in_validate_only(self):
        shell = shutil.which("pwsh") or shutil.which("powershell")
        if not shell:
            self.skipTest("PowerShell is not available")
        fake_driver = self.root / "fake-driver.ps1"
        fake_driver.write_text("exit 0\n", encoding="utf-8")
        result = subprocess.run(
            [
                shell, "-NoProfile", "-File", str(ORCHESTRATOR),
                "-RunType", "REAL", "-Case", "F03-L-001", "-DriverScript", str(fake_driver),
                "-ValidateOnly",
            ],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )
        self.assertNotEqual(0, result.returncode)
        compact_output = " ".join((result.stdout + result.stderr).split())
        self.assertIn("arbitrary DriverScript is forbidden", compact_output)

    def test_stat_rejects_incomplete_six_case_matrix(self):
        shutil.rmtree(self.root / "fixture-only" / "F03-L-006")
        result = self.run_python(
            STAT, "--run-type", "FIXTURE_ONLY", "--input", self.root, "--minimum-attempts", "1"
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("incomplete six-case matrix", result.stderr + result.stdout)

    def test_orchestrator_public_mode_rejects_non_real_before_running(self):
        shell = shutil.which("pwsh") or shutil.which("powershell")
        if not shell:
            self.skipTest("PowerShell is not available")
        rejected = subprocess.run(
            [shell, "-NoProfile", "-File", str(ORCHESTRATOR), "-RunType", "FIXTURE_ONLY",
             "-Case", "F03-L-001", "-PublicMode", "true", "-ValidateOnly"],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )
        self.assertNotEqual(0, rejected.returncode)
        accepted = subprocess.run(
            [shell, "-NoProfile", "-File", str(ORCHESTRATOR), "-RunType", "FIXTURE_ONLY",
             "-Case", "F03-L-001", "-PublicMode", "false", "-ValidateOnly"],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )
        self.assertEqual(0, accepted.returncode, accepted.stdout + accepted.stderr)

    def test_orchestrator_windows_local_validate_only_requires_application_env(self):
        shell = shutil.which("pwsh") or shutil.which("powershell")
        if not shell:
            self.skipTest("PowerShell is not available")
        result = subprocess.run(
            [
                shell, "-NoProfile", "-File", str(ORCHESTRATOR),
                "-RunType", "FIXTURE_ONLY", "-Case", "F03-L-001",
                "-PublicMode", "false", "-RuntimeMode", "WINDOWS_LOCAL",
                "-ApplicationEnvPath", str(self.root / "missing.env"), "-ValidateOnly",
            ],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ApplicationEnvPath", result.stdout + result.stderr)

    def test_orchestrator_uses_only_fixed_native_helper_paths(self):
        script = ORCHESTRATOR.read_text(encoding="utf-8-sig")
        self.assertIn("zh_f03_native_process_control.py", script)
        self.assertIn("zh_f03_auth_prepare.py", script)
        self.assertNotIn("ProcessControllerPath", script)

    def test_orchestrator_uses_windows_powershell_51_safe_dotenv_split(self):
        script = ORCHESTRATOR.read_text(encoding="utf-8-sig")
        self.assertIn("$separatorIndex = $line.IndexOf('=')", script)
        self.assertIn("$line.Substring($separatorIndex + 1)", script)
        self.assertNotIn("$line.Split(@('='), 2)", script)

    def test_orchestrator_jdbc_url_has_explicit_variable_boundaries(self):
        script = ORCHESTRATOR.read_text(encoding="utf-8-sig")
        self.assertIn(
            'jdbc:mysql://${MySqlHost}:${MySqlPort}/${MySqlDatabase}?useSSL=false',
            script,
        )

    def test_windows_powershell_executes_dotenv_and_jdbc_helpers(self):
        shell = shutil.which("powershell") or shutil.which("pwsh")
        if not shell:
            self.skipTest("PowerShell is not available")
        env_file = self.root / "parser.env"
        env_file.write_text("TARGET=A=B=C\n", encoding="utf-8")
        orchestrator = str(ORCHESTRATOR).replace("'", "''")
        env_path = str(env_file).replace("'", "''")
        command = f"""
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile('{orchestrator}', [ref]$tokens, [ref]$errors)
if ($errors.Count -ne 0) {{ exit 40 }}
foreach ($name in @('Get-DotEnvValue', 'New-MySqlJdbcUrl')) {{
    $definition = $ast.FindAll({{
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }}, $true) | Select-Object -First 1
    if ($null -eq $definition) {{ exit 41 }}
    Invoke-Expression $definition.Extent.Text
}}
$value = Get-DotEnvValue '{env_path}' 'TARGET'
$jdbc = New-MySqlJdbcUrl '127.0.0.1' 3306 'zhishu_f03'
if ($value -ne 'A=B=C') {{ exit 42 }}
if ($jdbc -ne 'jdbc:mysql://127.0.0.1:3306/zhishu_f03?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8') {{ exit 43 }}
"$value`n$jdbc"
"""
        result = subprocess.run(
            [shell, "-NoProfile", "-Command", command],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("A=B=C", result.stdout)
        self.assertIn("/zhishu_f03?useSSL=false", result.stdout)

    def test_windows_powershell_retains_process_handle_before_reading_exit_code(self):
        shell = shutil.which("powershell") or shutil.which("pwsh")
        python = shutil.which("python")
        if not shell or not python:
            self.skipTest("PowerShell and Python are required")

        script = ORCHESTRATOR.read_text(encoding="utf-8-sig")
        self.assertIn("$null = $process.Handle", script)
        self.assertIn("$null = $resetProcess.Handle", script)
        self.assertIn("$process.Refresh()", script)
        self.assertIn("$resetProcess.Refresh()", script)

        python_path = python.replace("'", "''")
        stdout_path = str(self.root / "exit-code.stdout.log").replace("'", "''")
        stderr_path = str(self.root / "exit-code.stderr.log").replace("'", "''")
        command = f"""
$process = Start-Process -FilePath '{python_path}' -ArgumentList @('-c', "__import__('time').sleep(0.2)") `
    -NoNewWindow -PassThru -RedirectStandardOutput '{stdout_path}' `
    -RedirectStandardError '{stderr_path}'
$null = $process.Handle
if (-not $process.WaitForExit(10000)) {{ exit 51 }}
$process.Refresh()
if ($null -eq $process.ExitCode) {{ exit 52 }}
exit [int]$process.ExitCode
"""
        result = subprocess.run(
            [shell, "-NoProfile", "-Command", command],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_local_runtime_configures_and_freezes_expired_lease_timing(self):
        script = ORCHESTRATOR.read_text(encoding="utf-8-sig")
        for contract in (
            "$ExperimentLeaseSeconds",
            "$ExperimentReaperInitialDelayMs",
            "$ExperimentReaperScanDelayMs",
            "$env:ZH_F03_LEASE_SECONDS",
            "$env:ZH_F03_REAPER_INITIAL_DELAY_MS",
            "$env:ZH_F03_REAPER_SCAN_DELAY_MS",
        ):
            self.assertIn(contract, script)
        self.assertGreaterEqual(script.count("experimentTiming = $experimentTiming"), 3)

        self.assertTrue(
            CANONICAL_LOCAL_DRIVER_TEMPLATE.is_file(),
            f"missing canonical local driver template: {CANONICAL_LOCAL_DRIVER_TEMPLATE}",
        )
        template = CANONICAL_LOCAL_DRIVER_TEMPLATE.read_text(encoding="utf-8")
        for contract in (
            '"DOCUMENT_PROCESSING_LEASE_SECONDS": "${ZH_F03_LEASE_SECONDS}"',
            '"DOCUMENT_PROCESSING_REAPER_INITIAL_DELAY_MS": "${ZH_F03_REAPER_INITIAL_DELAY_MS}"',
            '"DOCUMENT_PROCESSING_REAPER_SCAN_DELAY_MS": "${ZH_F03_REAPER_SCAN_DELAY_MS}"',
        ):
            self.assertIn(contract, template)

    def test_local_runtime_restarts_harness_backend_to_apply_experiment_timing(self):
        script = ORCHESTRATOR.read_text(encoding="utf-8-sig")
        self.assertIn("--action status --service backend", script)
        self.assertIn("--action stop --service backend", script)
        restart_index = script.index("--action stop --service backend")
        ensure_index = script.index("--action ensure-running", restart_index)
        self.assertLess(restart_index, ensure_index)

    def test_windows_local_requires_stable_native_state_path(self):
        script = ORCHESTRATOR.read_text(encoding="utf-8-sig")
        self.assertIn("[string] $NativeStatePath", script)
        self.assertIn("WINDOWS_LOCAL requires a stable -NativeStatePath", script)
        self.assertNotIn(
            "$nativeStatePath = Join-Path (Join-Path $OutRoot '.runtime') 'native-process-state.json'",
            script,
        )
        self.assertGreaterEqual(
            script.count("nativeStatePathSha256 = $nativeStatePathSha256"),
            3,
        )
        template = CANONICAL_LOCAL_DRIVER_TEMPLATE.read_text(encoding="utf-8")
        self.assertIn('"startupStabilitySeconds": 12', template)
        self.assertIn('"startupStabilitySeconds": 20', template)

    def test_windows_local_rejects_all_windows_relative_native_state_forms(self):
        shell = shutil.which("powershell") or shutil.which("pwsh")
        if not shell:
            self.skipTest("PowerShell is not available")

        application_env = self.root / "application.env"
        kafka_home = self.root / "kafka"
        kafka_config = self.root / "kafka-server.properties"
        application_env.write_text("PLACEHOLDER=value\n", encoding="utf-8")
        kafka_home.mkdir()
        kafka_config.write_text("process.roles=broker,controller\n", encoding="utf-8")

        for native_state_path in (
            r"D:relative\native-process-state.json",
            r"\current-drive-relative\native-process-state.json",
            r"relative\native-process-state.json",
        ):
            with self.subTest(native_state_path=native_state_path):
                result = subprocess.run(
                    [
                        shell, "-NoProfile", "-File", str(ORCHESTRATOR),
                        "-RunType", "FIXTURE_ONLY", "-Case", "F03-L-001",
                        "-PublicMode", "false", "-RuntimeMode", "WINDOWS_LOCAL",
                        "-ApplicationEnvPath", str(application_env),
                        "-KafkaHome", str(kafka_home),
                        "-KafkaConfigPath", str(kafka_config),
                        "-NativeStatePath", native_state_path,
                        "-ValidateOnly",
                    ],
                    cwd=ROOT, text=True, capture_output=True, check=False,
                )
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn(
                    "stable -NativeStatePath",
                    result.stdout + result.stderr,
                )

    def test_windows_local_accepts_drive_absolute_and_unc_native_state_paths(self):
        shell = shutil.which("powershell") or shutil.which("pwsh")
        if not shell:
            self.skipTest("PowerShell is not available")

        application_env = self.root / "application.env"
        kafka_home = self.root / "kafka"
        kafka_config = self.root / "kafka-server.properties"
        application_env.write_text("PLACEHOLDER=value\n", encoding="utf-8")
        kafka_home.mkdir()
        kafka_config.write_text("process.roles=broker,controller\n", encoding="utf-8")

        for native_state_path in (
            r"D:\stable\native-process-state.json",
            r"\\server\share\native-process-state.json",
        ):
            with self.subTest(native_state_path=native_state_path):
                result = subprocess.run(
                    [
                        shell, "-NoProfile", "-File", str(ORCHESTRATOR),
                        "-RunType", "FIXTURE_ONLY", "-Case", "F03-L-001",
                        "-PublicMode", "false", "-RuntimeMode", "WINDOWS_LOCAL",
                        "-ApplicationEnvPath", str(application_env),
                        "-KafkaHome", str(kafka_home),
                        "-KafkaConfigPath", str(kafka_config),
                        "-NativeStatePath", native_state_path,
                        "-ValidateOnly",
                    ],
                    cwd=ROOT, text=True, capture_output=True, check=False,
                )
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_real_driver_upload_uses_owner_primary_org_fallback(self):
        driver = REAL_DRIVER.read_text(encoding="utf-8")
        self.assertNotIn('"orgTag": "zh-f03-real"', driver)

    def test_verify_rejects_public_local_real_without_controller_hash(self):
        real_root = self.root / "real-only"
        for case_id in CASES:
            run_dir = write_run(real_root, case_id, run_type="REAL")
            manifest_path = run_dir / "run-manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["publicMode"] = "true"
            manifest.pop("nativeProcessControllerSha256")
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        result = self.run_python(
            VERIFY, "--run-type", "REAL", "--input", real_root, "--minimum-attempts", "1"
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("nativeProcessControllerSha256", result.stdout + result.stderr)

    def test_freeze_writes_canonical_real_inventory_and_summary(self):
        real_root = self.root / "freeze-runs"
        for case_id in CASES:
            write_run(real_root, case_id, run_type="REAL")
        stat = self.run_python(
            STAT, "--run-type", "REAL", "--input", real_root, "--minimum-attempts", "1"
        )
        self.assertEqual(0, stat.returncode, stat.stdout + stat.stderr)
        feature_root = self.root / "feature-root"
        feature_root.mkdir()

        frozen = self.run_python(
            FREEZE, "--run-type", "REAL", "--input", real_root,
            "--feature-root", feature_root, "--minimum-attempts", "1",
        )

        self.assertEqual(0, frozen.returncode, frozen.stdout + frozen.stderr)
        manifests = (feature_root / "07-run-manifest.jsonl").read_text(encoding="utf-8").splitlines()
        self.assertEqual(6, len(manifests))
        inventory = json.loads((feature_root / "08-原始结果清单.json").read_text(encoding="utf-8"))
        self.assertEqual(6, len(inventory["runs"]))
        summary = (feature_root / "09-统计汇总.csv").read_text(encoding="utf-8")
        self.assertIn("F03-L-006,1,1,0,0,true", summary)

    def test_freeze_rejects_non_real_input(self):
        feature_root = self.root / "feature-root"
        feature_root.mkdir()
        result = self.run_python(
            FREEZE, "--run-type", "FIXTURE_ONLY", "--input", self.root,
            "--feature-root", feature_root, "--minimum-attempts", "1",
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("REAL", result.stdout + result.stderr)

    def test_orchestrator_blocked_matrix_still_writes_a_valid_evidence_contract(self):
        shell = shutil.which("pwsh") or shutil.which("powershell")
        if not shell:
            self.skipTest("PowerShell is not available")
        output = self.root / "orchestrated"
        collected = subprocess.run(
            [
                shell,
                "-NoProfile",
                "-File",
                str(ORCHESTRATOR),
                "-RunType",
                "FIXTURE_ONLY",
                "-Case",
                "all",
                "-PublicMode",
                "false",
                "-Attempts",
                "1",
                "-OutRoot",
                str(output),
                "-ApplicationHealthUrl",
                "http://127.0.0.1:1/health",
                "-MySqlPort",
                "1",
                "-KafkaPort",
                "1",
                "-MinioPort",
                "1",
                "-ElasticsearchPort",
                "1",
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(4, collected.returncode, collected.stdout + collected.stderr)
        self.assertEqual(6, len(list((output / "fixture-only").rglob("run-manifest.json"))))

        aggregated = self.run_python(
            STAT,
            "--run-type",
            "FIXTURE_ONLY",
            "--input",
            output,
            "--minimum-attempts",
            "1",
        )
        self.assertEqual(4, aggregated.returncode, aggregated.stdout + aggregated.stderr)
        self.assertNotIn("FAIL_CLOSED", aggregated.stdout + aggregated.stderr)


class NativeProcessControlTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.state_path = self.root / "native-process-state.json"
        self.config = {
            "services": {
                "kafka": {
                    "command": ["fake-kafka.exe", "server.properties"],
                    "workingDirectory": str(self.root),
                },
                "backend": {
                    "command": ["fake-java.exe", "-jar", "app.jar"],
                    "workingDirectory": str(self.root),
                },
            }
        }

    def tearDown(self):
        self.temp.cleanup()

    @staticmethod
    def identity(pid=101, created_at_ns=2026071801, executable_path="fake.exe", digest="A" * 64):
        return {
            "pid": pid,
            "createdAtNs": created_at_ns,
            "executablePath": executable_path,
            "executableSha256": digest,
        }

    def import_controller(self):
        sys.path.insert(0, str(SCRIPTS))
        try:
            from zh_f03_native_process_control import DriverFailure, NativeProcessController
            return DriverFailure, NativeProcessController
        finally:
            sys.path.pop(0)

    def test_stop_rejects_state_not_owned_by_harness(self):
        DriverFailure, NativeProcessController = self.import_controller()
        self.state_path.write_text(json.dumps({
            "schemaVersion": "ZH-F03-NATIVE-PROCESS/1",
            "managedByHarness": False,
            "services": {},
        }), encoding="utf-8")
        controller = NativeProcessController(
            self.config,
            self.state_path,
            process_probe=lambda pid: self.identity(pid),
            process_start=lambda service, spec: self.identity(),
            process_stop=lambda identity: None,
        )
        with self.assertRaisesRegex(DriverFailure, "not managed by this harness"):
            controller.stop("backend")

    def test_stop_rejects_pid_identity_mismatch_without_calling_stopper(self):
        DriverFailure, NativeProcessController = self.import_controller()
        expected = self.identity(pid=202, executable_path="expected.exe", digest="B" * 64)
        self.state_path.write_text(json.dumps({
            "schemaVersion": "ZH-F03-NATIVE-PROCESS/1",
            "managedByHarness": True,
            "services": {
                "backend": {"desiredRunning": True, "identity": expected},
            },
        }), encoding="utf-8")
        stopped = []
        controller = NativeProcessController(
            self.config,
            self.state_path,
            process_probe=lambda pid: self.identity(pid=pid, executable_path="other.exe", digest="C" * 64),
            process_start=lambda service, spec: self.identity(),
            process_stop=stopped.append,
        )
        with self.assertRaisesRegex(DriverFailure, "process identity mismatch"):
            controller.stop("backend")
        self.assertEqual([], stopped)

    def test_unknown_service_is_rejected(self):
        DriverFailure, NativeProcessController = self.import_controller()
        controller = NativeProcessController(
            self.config,
            self.state_path,
            process_probe=lambda pid: None,
            process_start=lambda service, spec: self.identity(),
            process_stop=lambda identity: None,
        )
        with self.assertRaisesRegex(DriverFailure, "unsupported service"):
            controller.stop("elasticsearch")

    def test_real_process_start_rejects_identity_that_dies_during_stability_window(self):
        sys.path.insert(0, str(SCRIPTS))
        try:
            import zh_f03_native_process_control as control
        finally:
            sys.path.pop(0)
        identity = self.identity(pid=303)
        process = Mock(pid=303)
        process.poll.return_value = None
        spec = {
            "command": ["fake.exe"],
            "workingDirectory": str(self.root),
            "logDirectory": str(self.root / "logs"),
            "startupStabilitySeconds": 1,
        }
        with (
            patch.object(control.subprocess, "Popen", return_value=process),
            patch.object(control, "_probe_process", side_effect=[identity, None]),
            patch.object(control.time, "sleep", return_value=None),
        ):
            with self.assertRaisesRegex(control.DriverFailure, "stability window"):
                control._start_process("backend", spec)

    def test_start_stop_restore_round_trip_uses_only_recorded_identity(self):
        _, NativeProcessController = self.import_controller()
        live = {}
        calls = []

        def start(service, spec):
            identity = self.identity(
                pid=301 if service == "kafka" else 302,
                executable_path=f"{service}.exe",
                digest=("D" if service == "kafka" else "E") * 64,
            )
            live[identity["pid"]] = identity
            calls.append(("start", service))
            return identity

        def stop(identity):
            calls.append(("stop", identity["pid"]))
            live.pop(identity["pid"], None)

        controller = NativeProcessController(
            self.config,
            self.state_path,
            process_probe=lambda pid: live.get(pid),
            process_start=start,
            process_stop=stop,
        )
        controller.ensure_running()
        controller.stop("kafka")
        controller.restore()

        state = json.loads(self.state_path.read_text(encoding="utf-8"))
        self.assertTrue(state["managedByHarness"])
        self.assertEqual({"kafka", "backend"}, set(state["services"]))
        self.assertEqual(
            [("start", "kafka"), ("start", "backend"), ("stop", 301), ("start", "kafka")],
            calls,
        )


class RealFaultDriverRuntimeContractTests(unittest.TestCase):
    @staticmethod
    def driver_type():
        sys.path.insert(0, str(SCRIPTS))
        try:
            from zh_f03_real_fault_driver import DriverFailure, RealFaultDriver
            return DriverFailure, RealFaultDriver
        finally:
            sys.path.pop(0)

    @staticmethod
    def fake_controller():
        class FakeController:
            def __init__(self):
                self.calls = []

            def stop(self, service):
                self.calls.append(("stop", service))

            def start(self, service):
                self.calls.append(("start", service))

            def restore(self):
                self.calls.append(("restore", None))

            def status(self, service):
                self.calls.append(("status", service))
                return {"service": service, "running": True}

            def snapshot(self):
                return {
                    "schemaVersion": "ZH-F03-NATIVE-PROCESS/1",
                    "managedByHarness": True,
                    "services": {},
                }

        return FakeController()

    def test_windows_local_mode_delegates_to_fixed_controller(self):
        _, driver_type = self.driver_type()
        driver = driver_type.__new__(driver_type)
        driver.config = {"processControl": {"mode": "WINDOWS_LOCAL"}}
        driver.process_controller = self.fake_controller()

        driver.stop_runtime("kafka")
        driver.start_runtime("backend")
        driver.runtime_restore()

        self.assertEqual(
            [("stop", "kafka"), ("start", "backend"), ("restore", None)],
            driver.process_controller.calls,
        )

    def test_windows_local_preflight_requires_both_harness_services_running(self):
        _, driver_type = self.driver_type()
        driver = driver_type.__new__(driver_type)
        driver.config = {"processControl": {"mode": "WINDOWS_LOCAL"}}
        driver.process_controller = self.fake_controller()

        with tempfile.TemporaryDirectory() as temp:
            driver.run_dir = Path(temp)
            driver.runtime_preflight()

        self.assertEqual(
            [("status", "kafka"), ("status", "backend")],
            driver.process_controller.calls,
        )

    def test_docker_mode_preserves_container_control(self):
        _, driver_type = self.driver_type()
        driver = driver_type.__new__(driver_type)
        driver.config = {
            "processControl": {"mode": "DOCKER"},
            "docker": {"kafkaContainer": "zhishu-kafka", "backendContainer": "zhishu-backend"},
        }
        calls = []
        driver.docker_stop = lambda container: calls.append(("stop", container))
        driver.docker_start = lambda container: calls.append(("start", container))

        driver.stop_runtime("kafka")
        driver.start_runtime("backend")

        self.assertEqual([("stop", "zhishu-kafka"), ("start", "zhishu-backend")], calls)

    def test_unknown_runtime_mode_fails_closed(self):
        DriverFailure, driver_type = self.driver_type()
        driver = driver_type.__new__(driver_type)
        driver.config = {"processControl": {"mode": "REMOTE_SHELL"}}

        with self.assertRaisesRegex(DriverFailure, "unsupported processControl.mode"):
            driver.runtime_mode()


class AuthPreparationTests(unittest.TestCase):
    class Response:
        def __init__(self, status_code, payload):
            self.status_code = status_code
            self._payload = payload

        def json(self):
            return self._payload

    @staticmethod
    def import_auth():
        sys.path.insert(0, str(SCRIPTS))
        try:
            from zh_f03_auth_prepare import DriverFailure, prepare_auth
            return DriverFailure, prepare_auth
        finally:
            sys.path.pop(0)

    def successful_request(self, method, url, **kwargs):
        body = kwargs.get("json") or {}
        if url.endswith("/register"):
            return self.Response(200, {"code": 200})
        if url.endswith("/login"):
            username = body["username"]
            return self.Response(200, {"code": 200, "data": {"token": f"token-{username}"}})
        if url.endswith("/me"):
            token = kwargs["headers"]["Authorization"].removeprefix("Bearer ")
            username = token.removeprefix("token-")
            return self.Response(200, {
                "code": 200,
                "data": {
                    "id": abs(hash(username)) % 100000 + 1,
                    "username": username,
                    "role": "ADMIN" if username == "root-admin" else "USER",
                },
            })
        raise AssertionError((method, url, kwargs))

    def test_prepare_auth_returns_three_distinct_tokens_and_safe_evidence(self):
        _, prepare_auth = self.import_auth()

        result = prepare_auth(
            self.successful_request,
            "http://127.0.0.1:8081",
            "root-admin",
            "admin-secret",
            "session-20260718",
        )

        self.assertNotEqual(result["ownerToken"], result["adminToken"])
        self.assertNotEqual(result["ownerToken"], result["otherToken"])
        evidence_json = json.dumps(result["evidence"])
        self.assertNotIn("password", evidence_json.lower())
        self.assertNotIn(result["ownerToken"], evidence_json)
        self.assertTrue(result["evidence"]["distinctTokens"])
        self.assertEqual("ADMIN", result["evidence"]["admin"]["role"])

    def test_existing_generated_account_with_unknown_password_fails_closed(self):
        DriverFailure, prepare_auth = self.import_auth()

        def request(method, url, **kwargs):
            if url.endswith("/register"):
                return self.Response(400, {"code": 400, "message": "Username already exists"})
            if url.endswith("/login"):
                return self.Response(401, {"code": 401, "message": "Invalid credentials"})
            raise AssertionError((method, url))

        with self.assertRaisesRegex(DriverFailure, "cannot authenticate generated experimental account"):
            prepare_auth(request, "http://127.0.0.1:8081", "root-admin", "secret", "session-existing")

    def test_invite_required_registration_fails_before_sampling(self):
        DriverFailure, prepare_auth = self.import_auth()

        def request(method, url, **kwargs):
            if url.endswith("/register"):
                return self.Response(403, {"code": 403, "message": "Invite code is required"})
            raise AssertionError((method, url))

        with self.assertRaisesRegex(DriverFailure, "registration policy rejected"):
            prepare_auth(request, "http://127.0.0.1:8081", "root-admin", "secret", "session-invite")

    def test_admin_actor_must_have_admin_role(self):
        DriverFailure, prepare_auth = self.import_auth()

        def request(method, url, **kwargs):
            response = self.successful_request(method, url, **kwargs)
            if url.endswith("/me"):
                payload = response.json()
                payload["data"]["role"] = "USER"
            return response

        with self.assertRaisesRegex(DriverFailure, "admin actor does not have ADMIN role"):
            prepare_auth(request, "http://127.0.0.1:8081", "root-admin", "secret", "session-role")


if __name__ == "__main__":
    unittest.main()
