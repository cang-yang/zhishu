#!/usr/bin/env python3
"""Independent recomputation of ZH-F03 correctness invariants."""

from __future__ import annotations

import argparse
from collections import defaultdict
from datetime import datetime
import sys

from zh_f03_light_common import (
    BASELINE_ID,
    CASE_IDS,
    EXPERIMENT_ID,
    EvidenceError,
    load_attempts,
    write_json,
)


CASE_WINDOWS_SECONDS = {
    "F03-L-001": 120,
    "F03-L-002": 120,
    "F03-L-003": 120,
    "F03-L-004": 120,
    "F03-L-005": 180,
    "F03-L-006": 120,
}

REQUIRED_FAULT_EVENTS = {
    "F03-L-001": ("KAFKA_UNAVAILABLE", "KAFKA_RESTORED"),
    "F03-L-002": ("DUPLICATE_KAFKA_DELIVERY", "MYSQL_HASH_CONFLICT"),
    "F03-L-003": ("WORKER_TERMINATED", "LEASE_EXPIRED"),
    "F03-L-004": ("ES_BULK_ITEM_REJECTED",),
    "F03-L-005": ("MINIO_SOURCE_UNAVAILABLE", "DLT_RECORD_OBSERVED"),
    "F03-L-006": ("MYSQL_FINALIZE_REJECTED",),
}


def _parse_instant(value):
    if not isinstance(value, str) or not value.strip():
        raise ValueError("timestamp is missing")
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _status_values(raw, field):
    rows = raw.get(field)
    if not isinstance(rows, list):
        return []
    return [row.get("status") for row in rows if isinstance(row, dict)]


def _contains_in_order(values, expected):
    position = 0
    for value in values:
        if position < len(expected) and value == expected[position]:
            position += 1
    return position == len(expected)


def _require_fault_and_window(raw, failed):
    case_id = raw.get("caseId")
    observation = raw.get("observation")
    try:
        started = _parse_instant(observation["startedAt"])
        completed = _parse_instant(observation["completedAt"])
        elapsed = (completed - started).total_seconds()
        if elapsed < 0 or elapsed > CASE_WINDOWS_SECONDS[case_id]:
            failed.append("observation_window")
    except (KeyError, TypeError, ValueError):
        failed.append("observation_window")
        started = completed = None

    events = raw.get("faultEvents")
    event_types = set()
    if isinstance(events, list):
        for event in events:
            if not isinstance(event, dict):
                failed.append("fault_event_shape")
                continue
            event_type = event.get("type")
            event_types.add(event_type)
            try:
                captured = _parse_instant(event.get("capturedAt"))
                if started is not None and not (started <= captured <= completed):
                    failed.append(f"fault_event_window:{event_type}")
            except (TypeError, ValueError):
                failed.append(f"fault_event_time:{event_type}")
    for event_type in REQUIRED_FAULT_EVENTS[case_id]:
        if event_type not in event_types:
            failed.append(f"fault_proof:{event_type}")


def _recompute_case_facts(raw, failed):
    case_id = raw["caseId"]
    task_statuses = _status_values(raw, "taskHistory")
    outbox_statuses = _status_values(raw, "outboxHistory")

    if case_id == "F03-L-001":
        outbox_history = raw.get("outboxHistory")
        retried = any(
            isinstance(row, dict) and isinstance(row.get("retryCount", row.get("attemptCount")), int)
            and row.get("retryCount", row.get("attemptCount")) > 0
            for row in (outbox_history or [])
        )
        recoverable_then_published = (
            bool(outbox_statuses)
            and outbox_statuses[0] in ("NEW", "SENDING")
            and outbox_statuses[-1] == "PUBLISHED"
            and ("SENDING" in outbox_statuses or retried)
        )
        if not recoverable_then_published:
            failed.append("outbox_publish_transition")
        if not any(status in ("PROCESSING", "COMPLETED") for status in task_statuses):
            failed.append("task_processing_opportunity")
        return

    if case_id == "F03-L-002":
        delivery = raw.get("kafkaDelivery")
        if not isinstance(delivery, dict) or not (
            delivery.get("eventId")
            and delivery.get("eventId") == delivery.get("duplicateEventId")
            and isinstance(delivery.get("offsetBefore"), int)
            and isinstance(delivery.get("offsetAfter"), int)
            and delivery["offsetAfter"] > delivery["offsetBefore"]
        ):
            failed.append("duplicate_delivery")
        idem = raw.get("idempotency")
        if not isinstance(idem, dict):
            failed.extend(("mysql_idempotent_set", "es_idempotent_set", "hash_conflict_rejected"))
            return
        mysql_before = idem.get("mysqlBusinessKeysBefore")
        mysql_after = idem.get("mysqlBusinessKeysAfter")
        if not isinstance(mysql_before, list) or mysql_before != mysql_after or len(mysql_after) != len(set(mysql_after)):
            failed.append("mysql_idempotent_set")
        es_before = idem.get("esIdsBefore")
        es_after = idem.get("esIdsAfter")
        if not isinstance(es_before, list) or es_before != es_after or len(es_after) != len(set(es_after)):
            failed.append("es_idempotent_set")
        conflict = idem.get("hashConflict")
        sql_state = str(conflict.get("sqlState", "")).strip() if isinstance(conflict, dict) else ""
        if not isinstance(conflict, dict) or not (
            conflict.get("existingHash")
            and conflict.get("attemptedHash")
            and conflict["existingHash"] != conflict["attemptedHash"]
            and (conflict.get("errorCode") == 1062 or sql_state in ("23000", "23505"))
        ):
            failed.append("hash_conflict_rejected")
        return

    if case_id == "F03-L-003":
        history = raw.get("taskHistory")
        if not _contains_in_order(task_statuses, ("PROCESSING", "RETRY_WAIT", "COMPLETED")):
            failed.append("lease_recovery_transition")
        recoveries = [row.get("retryCount") for row in history if isinstance(row, dict)] if isinstance(history, list) else []
        if len(recoveries) < 2 or not isinstance(recoveries[0], int) or not isinstance(recoveries[-1], int) or recoveries[-1] <= recoveries[0]:
            failed.append("lease_recovery_count")
        if not _contains_in_order(outbox_statuses, ("NEW", "PUBLISHED")):
            failed.append("lease_recovery_outbox")
        return

    if case_id == "F03-L-004":
        bulk = raw.get("esBulk")
        failed_items = bulk.get("failedItems") if isinstance(bulk, dict) else None
        if not isinstance(failed_items, list) or not failed_items or any(
            not isinstance(item, dict)
            or not isinstance(item.get("status"), int)
            or item["status"] < 400
            or not item.get("errorType")
            for item in (failed_items or [])
        ):
            failed.append("bulk_item_failure")
        file_history = raw.get("fileHistory")
        if not isinstance(file_history, list) or len(file_history) < 2 or not all(isinstance(row, dict) for row in file_history):
            failed.append("active_version_history")
        elif file_history[0].get("activeProcessingVersion") != file_history[-1].get("activeProcessingVersion"):
            failed.append("active_version_changed")
        return

    if case_id == "F03-L-005":
        dlt = raw.get("dlt")
        if not isinstance(dlt, dict) or not (
            isinstance(dlt.get("offsetBefore"), int)
            and isinstance(dlt.get("offsetAfter"), int)
            and dlt["offsetAfter"] > dlt["offsetBefore"]
        ):
            failed.append("dlt_offset")
        reprocess = raw.get("reprocess")
        if not isinstance(reprocess, dict) or not (
            isinstance(reprocess.get("httpStatus"), int)
            and 200 <= reprocess["httpStatus"] < 300
            and isinstance(reprocess.get("oldVersion"), int)
            and reprocess.get("newVersion") == reprocess["oldVersion"] + 1
        ):
            failed.append("reprocess_version")
        history = raw.get("taskHistory")
        failed_versions = {
            row.get("processingVersion") for row in history or []
            if isinstance(row, dict) and row.get("status") == "FAILED"
        }
        completed_versions = {
            row.get("processingVersion") for row in history or []
            if isinstance(row, dict) and row.get("status") == "COMPLETED"
        }
        if not isinstance(reprocess, dict) or reprocess.get("oldVersion") not in failed_versions or reprocess.get("newVersion") not in completed_versions:
            failed.append("reprocess_task_transition")
        return

    transaction = raw.get("transaction")
    if not isinstance(transaction, dict) or not (
        isinstance(transaction.get("httpStatus"), int)
        and transaction["httpStatus"] >= 500
        and transaction.get("before") == transaction.get("afterFailure")
        and isinstance(transaction.get("retryHttpStatus"), int)
        and 200 <= transaction["retryHttpStatus"] < 300
    ):
        failed.append("mysql_finalize_rollback")
    minio = raw.get("minioProof")
    if not isinstance(minio, dict) or minio.get("mergedObjectBeforeRetry") is not True or minio.get("mergedObjectAfterRetry") is not True:
        failed.append("minio_reuse")


def recompute(raw: dict):
    failed = []

    _require_fault_and_window(raw, failed)
    _recompute_case_facts(raw, failed)

    chunks = raw.get("chunks")
    if not isinstance(chunks, list):
        failed.append("mysql_chunk_shape")
        chunks = []
    chunk_keys = []
    for chunk in chunks:
        try:
            chunk_keys.append(
                (chunk["fileUploadId"], chunk["processingVersion"], chunk["chunkId"])
            )
        except (KeyError, TypeError):
            failed.append("mysql_chunk_shape")
            break
    if len(chunk_keys) != len(set(chunk_keys)):
        failed.append("mysql_business_key")

    file_state = raw.get("file")
    task = raw.get("task")
    if not isinstance(file_state, dict):
        failed.append("file_state")
        active_version = None
    else:
        active_version = file_state.get("activeProcessingVersion")

    active_chunks = raw.get("activeChunks")
    recomputed_ids = []
    if not isinstance(active_chunks, list):
        failed.append("es_active_chunk_shape")
        active_chunks = []
    for chunk in active_chunks:
        try:
            file_upload_id = chunk["fileUploadId"]
            processing_version = chunk["processingVersion"]
            chunk_id = chunk["chunkId"]
            if processing_version != active_version:
                failed.append("es_active_chunk_version")
            recomputed_ids.append(f"{file_upload_id}:{processing_version}:{chunk_id}")
        except (KeyError, TypeError):
            failed.append("es_active_chunk_shape")
            break
    if len(recomputed_ids) != len(set(recomputed_ids)):
        failed.append("es_active_chunk_key")

    expected_ids = raw.get("expectedActiveEsIds")
    actual_ids = raw.get("actualActiveEsIds")
    if not isinstance(expected_ids, list) or not isinstance(actual_ids, list):
        failed.append("es_id_shape")
    else:
        if set(expected_ids) != set(recomputed_ids) or len(expected_ids) != len(set(expected_ids)):
            failed.append("es_id_formula")
        if set(actual_ids) != set(recomputed_ids) or len(actual_ids) != len(set(actual_ids)):
            failed.append("es_id_set")

    if isinstance(task, dict):
        task_status = task.get("status")
        task_version = task.get("processingVersion")
        if task_status == "COMPLETED" and active_version != task_version:
            failed.append("state_data_consistency")
        if task_status == "FAILED" and active_version == task_version:
            failed.append("state_data_consistency")
    elif raw.get("caseId") != "F03-L-006":
        failed.append("task_state")

    search_hits = raw.get("searchHits")
    if not isinstance(search_hits, list):
        failed.append("search_hit_shape")
    else:
        for hit in search_hits:
            if not isinstance(hit, dict):
                failed.append("search_hit_shape")
                break
            hit_version = hit.get("processingVersion")
            hit_active = hit.get("activeProcessingVersion", active_version)
            if hit_version is not None and hit_version != hit_active:
                failed.append("invisible_version_search")
                break

    permissions = raw.get("permissionChecks")
    permission_map = {}
    if isinstance(permissions, list):
        for check in permissions:
            if isinstance(check, dict):
                permission_map[check.get("actor")] = check.get("status")
    if not (
        permission_map.get("owner") in range(200, 300)
        and permission_map.get("admin") in range(200, 300)
        and permission_map.get("other") == 403
    ):
        failed.append("permission_matrix")

    legacy_checks = raw.get("legacyVisibilityChecks")
    if not isinstance(legacy_checks, list) or not legacy_checks:
        failed.append("legacy_visibility_shape")
    else:
        for check in legacy_checks:
            if not isinstance(check, dict) or (
                not str(check.get("fileOwnerUserId", "")).strip()
                or not str(check.get("queryOwnerUserId", "")).strip()
                or check.get("expectedVisible") != check.get("actualVisible")
            ):
                failed.append("legacy_visibility")
                break

    if raw.get("recordedOutcome") != "PASS":
        failed.append(f"recorded_outcome:{raw.get('recordedOutcome')}")
    return sorted(set(failed))


def main() -> int:
    parser = argparse.ArgumentParser(description="Recompute ZH-F03 invariants from raw evidence")
    parser.add_argument("--run-type", required=True, choices=("REAL", "FIXTURE_ONLY", "STUB"))
    parser.add_argument("--input", required=True)
    parser.add_argument("--minimum-attempts", type=int)
    args = parser.parse_args()
    minimum = args.minimum_attempts or (3 if args.run_type == "REAL" else 1)

    try:
        run_type_dir, attempts, counts = load_attempts(args.input, args.run_type, minimum)
    except EvidenceError as exc:
        print(f"FAIL_CLOSED: {exc}", file=sys.stderr)
        return 2

    results = []
    per_case = defaultdict(list)
    for attempt in attempts:
        raw = attempt["raw"]
        failed_checks = recompute(raw)
        if attempt["manifest"]["includedInStats"] and any(
            metric["pass"] is not True for metric in attempt["rawMetrics"]
        ):
            failed_checks = sorted(set(failed_checks + ["raw_metric_pass"]))
        result = {
            "caseId": raw["caseId"],
            "runId": raw["runId"],
            "attempt": raw["attempt"],
            "pass": not failed_checks,
            "failedChecks": failed_checks,
            "manifestPath": attempt["manifestPath"],
        }
        results.append(result)
        per_case[raw["caseId"]].append(result)

    cases = []
    for case_id in CASE_IDS:
        rows = per_case[case_id]
        passed = len(rows) >= minimum and all(row["pass"] for row in rows)
        cases.append(
            {
                "caseId": case_id,
                "attemptCount": len(rows),
                "passCount": sum(row["pass"] for row in rows),
                "pass": passed,
            }
        )
    verify_pass = all(case["pass"] for case in cases)
    verdict = {
        "featureId": "ZH-F03",
        "experimentId": EXPERIMENT_ID,
        "baselineId": BASELINE_ID,
        "runType": args.run_type,
        "minimumAttempts": minimum,
        "countsByCase": counts,
        "casePassCount": sum(case["pass"] for case in cases),
        "caseTotal": len(CASE_IDS),
        "attemptPassCount": sum(result["pass"] for result in results),
        "attemptTotal": len(results),
        "verifyPass": verify_pass,
        "cases": cases,
        "attempts": results,
    }
    write_json(run_type_dir / "verify-verdict.json", verdict)

    lines = [
        "# ZH-F03 独立复算报告",
        "",
        f"- runType: `{args.run_type}`",
        f"- case: `{verdict['casePassCount']}/6`",
        f"- attempts: `{verdict['attemptPassCount']}/{verdict['attemptTotal']}`",
        f"- verifyPass: `{verify_pass}`",
        "",
        "| caseId | runId | 判定 | 失败检查 |",
        "|---|---|---|---|",
    ]
    for result in results:
        details = ", ".join(result["failedChecks"]) or "-"
        lines.append(
            f"| {result['caseId']} | {result['runId']} | "
            f"{'PASS' if result['pass'] else 'FAIL'} | {details} |"
        )
    (run_type_dir / "verify-report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(
        f"[verify] runType={args.run_type} cases={verdict['casePassCount']}/6 "
        f"attempts={verdict['attemptPassCount']}/{len(results)} verifyPass={verify_pass}"
    )
    return 0 if verify_pass else 5


if __name__ == "__main__":
    raise SystemExit(main())
