#!/usr/bin/env python3
"""Fail-closed ZH-F03 reliability matrix aggregation."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import sys

from zh_f03_light_common import (
    BASELINE_ID,
    CASE_IDS,
    EXPERIMENT_ID,
    METRIC_IDS,
    EvidenceError,
    load_attempts,
    write_json,
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Aggregate immutable ZH-F03 fault runs")
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

    per_case_rows = defaultdict(list)
    per_metric_rows = defaultdict(list)
    outcomes = Counter()
    for attempt in attempts:
        raw = attempt["raw"]
        case_id = raw["caseId"]
        outcome = raw["recordedOutcome"]
        included = attempt["manifest"]["includedInStats"]
        metric_pass = included and all(row["pass"] for row in attempt["rawMetrics"])
        attempt_pass = outcome == "PASS" and metric_pass
        outcomes[outcome] += 1
        per_case_rows[case_id].append(
            {
                "runId": raw["runId"],
                "attempt": raw["attempt"],
                "recordedOutcome": outcome,
                "metricPass": metric_pass,
                "pass": attempt_pass,
                "manifestPath": attempt["manifestPath"],
            }
        )
        if included:
            for metric in attempt["rawMetrics"]:
                per_metric_rows[metric["metricId"]].append(
                    {
                        "caseId": case_id,
                        "runId": raw["runId"],
                        "attempt": raw["attempt"],
                        "pass": metric["pass"],
                    }
                )

    cases = []
    for case_id in CASE_IDS:
        rows = per_case_rows[case_id]
        case_pass = len(rows) >= minimum and all(row["pass"] for row in rows)
        cases.append(
            {
                "caseId": case_id,
                "attemptCount": len(rows),
                "passCount": sum(row["pass"] for row in rows),
                "failCount": sum(
                    row["recordedOutcome"] != "BLOCKED_BY_ENVIRONMENT" and not row["pass"]
                    for row in rows
                ),
                "blockedCount": sum(
                    row["recordedOutcome"] == "BLOCKED_BY_ENVIRONMENT" for row in rows
                ),
                "pass": case_pass,
                "attempts": rows,
            }
        )
    included_attempt_count = sum(attempt["manifest"]["includedInStats"] for attempt in attempts)
    metrics = []
    for metric_id in METRIC_IDS:
        rows = per_metric_rows[metric_id]
        pass_count = sum(row["pass"] for row in rows)
        fail_count = sum(not row["pass"] for row in rows)
        metrics.append(
            {
                "metricId": metric_id,
                "attemptCount": len(rows),
                "passCount": pass_count,
                "failCount": fail_count,
                "pass": len(rows) == included_attempt_count and included_attempt_count > 0 and fail_count == 0,
            }
        )
    attempt_pass_count = sum(row["pass"] for rows in per_case_rows.values() for row in rows)
    attempt_fail_count = sum(
        row["recordedOutcome"] != "BLOCKED_BY_ENVIRONMENT" and not row["pass"]
        for rows in per_case_rows.values()
        for row in rows
    )
    matrix_pass = all(item["pass"] for item in cases) and all(item["pass"] for item in metrics)
    summary = {
        "featureId": "ZH-F03",
        "experimentId": EXPERIMENT_ID,
        "baselineId": BASELINE_ID,
        "runType": args.run_type,
        "minimumAttempts": minimum,
        "casePassCount": sum(item["pass"] for item in cases),
        "caseTotal": len(CASE_IDS),
        "attemptPassCount": attempt_pass_count,
        "attemptFailCount": attempt_fail_count,
        "attemptBlockedCount": outcomes["BLOCKED_BY_ENVIRONMENT"],
        "attemptTotal": len(attempts),
        "matrixPass": matrix_pass,
        "countsByCase": counts,
        "cases": cases,
        "metrics": metrics,
    }
    write_json(run_type_dir / "stat-summary.json", summary)

    lines = [
        "# ZH-F03 轻量可靠闭环统计",
        "",
        f"- runType: `{args.run_type}`",
        f"- minimumAttempts: `{minimum}`",
        f"- case: `{summary['casePassCount']}/{summary['caseTotal']}`",
        f"- attempts: PASS={outcomes['PASS']} FAIL={outcomes['FAIL']} BLOCKED={outcomes['BLOCKED_BY_ENVIRONMENT']}",
        f"- matrixPass: `{matrix_pass}`",
        "",
        "| caseId | attempts | PASS | FAIL | BLOCKED | 判定 |",
        "|---|---:|---:|---:|---:|---|",
    ]
    for item in cases:
        lines.append(
            f"| {item['caseId']} | {item['attemptCount']} | {item['passCount']} | "
            f"{item['failCount']} | {item['blockedCount']} | {'PASS' if item['pass'] else 'FAIL'} |"
        )
    lines.extend(
        [
            "",
            "| metricId | attempts | PASS | FAIL | 判定 |",
            "|---|---:|---:|---:|---|",
        ]
    )
    for item in metrics:
        lines.append(
            f"| {item['metricId']} | {item['attemptCount']} | {item['passCount']} | "
            f"{item['failCount']} | {'PASS' if item['pass'] else 'FAIL'} |"
        )
    (run_type_dir / "stat-report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(
        f"[stat] runType={args.run_type} cases={summary['casePassCount']}/6 "
        f"attempts={attempt_pass_count}/{len(attempts)} matrixPass={matrix_pass}"
    )
    return 0 if matrix_pass else 4


if __name__ == "__main__":
    raise SystemExit(main())
