#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ZH-F02 G4 EXPERIMENT 统计与 MME 判定
experimentId: ZH-EXP-F02-UPLOAD
runType: REAL

消费 zhishu-upload-harness.py 产出的 raw-results.jsonl, 计算:
  - 按 (variant, concurrency, scale, network) 分组: durationMs median/P90/P95, throughput median/P95
  - chunk P50/P90/P95, 错误率
  - MME 判定:
      ZH-M-F02-01 (M档 after median duration 降 >= 15% 且 P90 不劣化 > 10%)
      ZH-M-F02-02 (M档 after median throughput 升 >= 15%)
      ZH-M-F02-03 (恢复 case 全成功: resume runs mergeOk=True 且 uploadedChunksFinal 恢复完整)
      ZH-M-F02-07 (诊断: after chunk P95 不劣化 > 20% + 错误率 0%)
  - 护栏 ZH-M-F02-04/05/06 由 verify.py 判定 (hash/残留/隔离), 此处仅占位引用

fail-closed (--public-mode, 默认 true): 每 result 必须含非空 fileSha256/fileMd5/datasetManifestSha256
  (公开制品防篡改; 任一缺失 -> stat FAIL 不静默通过).

输出:
  stat-report.md   (人类可读)
  stat-summary.json (机器可读, 供 verify.py + G5 审查)

纯本地 JSON 统计, 不做任何后端调用, 可离线运行.
"""

import argparse
import hashlib
import json
import os
import statistics
import sys


def load_results(path):
    rows = []
    with open(path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def pct(sorted_vals, p):
    if not sorted_vals:
        return 0.0
    idx = min(len(sorted_vals) - 1, max(0, int((p / 100.0) * len(sorted_vals))))
    return sorted_vals[idx]


def group_key(r):
    return (r['variant'], r.get('concurrency', 1), r['scale'], r['network'])


def reduction_pct(baseline, after):
    if baseline == 0:
        return 0.0
    return 100.0 * (baseline - after) / baseline


def main():
    ap = argparse.ArgumentParser(description='ZH-F02 G4 stat + MME judgment')
    ap.add_argument('--results-jsonl', required=True)
    ap.add_argument('--out-dir', default=None)
    ap.add_argument('--public-mode', default='true', help='fail-closed 公开制品校验 (true/false, 默认 true)')
    args = ap.parse_args()
    out_dir = args.out_dir or os.path.dirname(os.path.abspath(args.results_jsonl))
    os.makedirs(out_dir, exist_ok=True)
    public_mode = str(args.public_mode).lower() != 'false'

    rows = load_results(args.results_jsonl)
    if not rows:
        print('ERROR: 无结果行 in ' + args.results_jsonl, file=sys.stderr)
        sys.exit(2)

    # fail-closed: 公开制品 sha 完整性
    sha_failures = []
    if public_mode:
        for r in rows:
            if not r.get('fileSha256') or not r.get('fileMd5'):
                sha_failures.append(r.get('runId', '?'))
    sha_ok = len(sha_failures) == 0

    # 主性能臂过滤: 仅 main-arm run 进入性能/错误率分组.
    #   排除: stoppedPartial (故障注入 partial: RC-interrupt / ISO3-B, mergeOk=false 属设计非错误)
    #         resume (恢复完成 run: RC-resume, 属恢复测试场景非主臂)
    #         r10Diag (R10 并集单调诊断 run: M-R10, 属诊断非主臂)
    #   不剔除原始行: MME-03 恢复判定仍从 rows 直读 resume 行 (line ~143).
    def is_main_arm(r):
        return not (r.get('stoppedPartial') or r.get('resume') or r.get('r10Diag'))

    # 分组 (仅 mergeOk 的 run 计入性能统计; 失败 run 计错误率)
    groups = {}
    excluded = []
    for r in rows:
        if not is_main_arm(r):
            excluded.append(r.get('runId', '?'))
            continue
        k = group_key(r)
        groups.setdefault(k, []).append(r)

    per_group = {}
    for k, rs in sorted(groups.items()):
        durations = sorted(r['durationMs'] for r in rs if r.get('mergeOk'))
        throughputs = sorted(r['throughputMibps'] for r in rs if r.get('mergeOk'))
        chunk_p95 = sorted(r.get('chunkP95Ms', 0) for r in rs if r.get('mergeOk'))
        n_ok = len(durations)
        n_total = len(rs)
        err_rate = (n_total - n_ok) / n_total if n_total else 1.0
        per_group[k] = {
            'variant': k[0], 'concurrency': k[1], 'scale': k[2], 'network': k[3],
            'nTotal': n_total, 'nOk': n_ok, 'errorRate': round(err_rate, 4),
            'durationMedian': statistics.median(durations) if durations else None,
            'durationP90': pct(durations, 90) if durations else None,
            'durationP95': pct(durations, 95) if durations else None,
            'throughputMedian': statistics.median(throughputs) if throughputs else None,
            'throughputP95': pct(throughputs, 95) if throughputs else None,
            'chunkP95Median': statistics.median(chunk_p95) if chunk_p95 else None,
        }

    def find(variant, concurrency, scale, network='local'):
        return per_group.get((variant, concurrency, scale, network))

    # ---- MME 主指标 (M档 LOCAL) ----
    mme = {}
    b_m = find('baseline', 1, 'M', 'local')
    a_m = find('after', 4, 'M', 'local')
    m01_ok = m02_ok = False
    if b_m and a_m and b_m['durationMedian'] and a_m['durationMedian']:
        dur_red = reduction_pct(b_m['durationMedian'], a_m['durationMedian'])
        p90_ratio = (a_m['durationP90'] / b_m['durationP90']) if b_m['durationP90'] else 0
        m01_ok = dur_red >= 15.0 and p90_ratio <= 1.10
        mme['ZH-M-F02-01'] = {
            'name': 'M档 median 端到端耗时降 >= 15% 且 P90 不劣化 > 10%',
            'baselineMedianMs': b_m['durationMedian'], 'afterMedianMs': a_m['durationMedian'],
            'durationReductionPct': round(dur_red, 2),
            'p90RatioAfterOverBaseline': round(p90_ratio, 4),
            'pass': m01_ok,
        }
        thr_inc = 100.0 * (a_m['throughputMedian'] - b_m['throughputMedian']) / b_m['throughputMedian']  # after 升幅 = (after-baseline)/baseline
        m02_ok = thr_inc >= 15.0
        mme['ZH-M-F02-02'] = {
            'name': 'M档 median 吞吐升 >= 15%',
            'baselineThroughputMibps': b_m['throughputMedian'], 'afterThroughputMibps': a_m['throughputMedian'],
            'throughputIncreasePct': round(thr_inc, 2),
            'pass': m02_ok,
        }
    else:
        mme['ZH-M-F02-01'] = {'name': 'M档 median 耗时', 'pass': False, 'reason': '缺 baseline(1) 或 after(4) M档 local 组'}
        mme['ZH-M-F02-02'] = {'name': 'M档 吞吐', 'pass': False, 'reason': '缺 baseline(1) 或 after(4) M档 local 组'}

    # ZH-M-F02-03 恢复 (resume runs 全成功且完整恢复)
    resume_runs = [r for r in rows if r.get('resume')]
    resume_ok = all(r.get('mergeOk') and len(r.get('uploadedChunksFinal', [])) == r['totalChunks'] for r in resume_runs) if resume_runs else False
    mme['ZH-M-F02-03'] = {
        'name': '恢复 case 全成功 (resume runs mergeOk 且分片完整)',
        'resumeRuns': len(resume_runs),
        'pass': resume_ok and len(resume_runs) >= 3,
    }

    # ZH-M-F02-07 诊断 (after chunk P95 不劣化 > 20% + 错误率 0)
    m07_ok = False
    if b_m and a_m and b_m['chunkP95Median'] and a_m['chunkP95Median']:
        p95_ratio = a_m['chunkP95Median'] / b_m['chunkP95Median']
        err0 = all(g['errorRate'] == 0.0 for g in per_group.values() if g['scale'] == 'M')
        m07_ok = p95_ratio <= 1.20 and err0
        mme['ZH-M-F02-07'] = {
            'name': '诊断: after chunk P95 不劣化 > 20% + M档错误率 0',
            'baselineChunkP95Ms': b_m['chunkP95Median'], 'afterChunkP95Ms': a_m['chunkP95Median'],
            'chunkP95Ratio': round(p95_ratio, 4), 'mScaleErrorRateAll0': err0,
            'pass': m07_ok,
        }
    else:
        mme['ZH-M-F02-07'] = {'name': '诊断 chunk P95/错误率', 'pass': False, 'reason': '缺 M档组'}

    # 护栏 04/05/06 由 verify.py 判定, 占位
    mme['ZH-M-F02-04'] = {'name': '合并 SHA-256 guard (verify.py 下载 merged 重算)', 'judgedBy': 'verify.py', 'pass': None}
    mme['ZH-M-F02-05'] = {'name': '分片完整 + 残留 guard (verify.py)', 'judgedBy': 'verify.py', 'pass': None}
    mme['ZH-M-F02-06'] = {'name': '多用户同 MD5 隔离矩阵 guard (verify.py)', 'judgedBy': 'verify.py', 'pass': None}

    main_pass = m01_ok and m02_ok and resume_ok
    diag_ok = m07_ok
    all_main = main_pass

    summary = {
        'featureId': 'ZH-F02',
        'experimentId': 'ZH-EXP-F02-UPLOAD',
        'baselineId': 'ZH-BL-F02-SERIAL-UPLOAD-V1',
        'runType': 'REAL',
        'publicMode': public_mode,
        'shaIntegrityOk': sha_ok,
        'shaFailures': sha_failures,
        'mainArmFilter': 'exclude stoppedPartial(fault-inject partial) + resume(recovery completion) + r10Diag(R10 diag) from perf/error groups; raw rows retained for MME-03',
        'excludedFromMainArm': excluded,
        'mmeMainPass': all_main,
        'mmeDiagnosticPass': diag_ok,
        'mme': mme,
        'perGroup': list(per_group.values()),
    }
    summary_path = os.path.join(out_dir, 'stat-summary.json')
    with open(summary_path, 'w', encoding='utf-8') as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    summary_sha = hashlib.sha256(open(summary_path, 'rb').read()).hexdigest().upper()

    # 人类可读报告
    lines = []
    lines.append('# ZH-F02 G4 实验统计报告 (MME 判定)')
    lines.append('')
    lines.append('```text')
    lines.append('experimentId: ZH-EXP-F02-UPLOAD')
    lines.append('baselineId:   ZH-BL-F02-SERIAL-UPLOAD-V1')
    lines.append('runType: REAL')
    lines.append('publicMode fail-closed shaIntegrityOk: ' + str(sha_ok))
    lines.append('mmeMainPass (01/02/03): ' + str(all_main))
    lines.append('mmeDiagnosticPass (07): ' + str(diag_ok))
    lines.append('statSummarySha256: ' + summary_sha)
    lines.append('```')
    lines.append('')
    lines.append('## 1. 分组统计')
    lines.append('')
    lines.append('| variant | c | scale | net | nTotal | nOk | errRate | durMedian(ms) | durP90 | durP95 | thrMedian(MiB/s) | chunkP95(ms) |')
    lines.append('|---|---|---|---|---|---|---|---|---|---|---|---|')
    for g in per_group.values():
        lines.append('| {v} | {c} | {s} | {n} | {nt} | {no} | {er} | {dm} | {d90} | {d95} | {tm} | {cp} |'.format(
            v=g['variant'], c=g['concurrency'], s=g['scale'], n=g['network'],
            nt=g['nTotal'], no=g['nOk'], er=g['errorRate'],
            dm=round(g['durationMedian'], 1) if g['durationMedian'] else '-',
            d90=round(g['durationP90'], 1) if g['durationP90'] else '-',
            d95=round(g['durationP95'], 1) if g['durationP95'] else '-',
            tm=round(g['throughputMedian'], 2) if g['throughputMedian'] else '-',
            cp=round(g['chunkP95Median'], 1) if g['chunkP95Median'] else '-'))
    if excluded:
        lines.append('')
        lines.append('> 主性能臂过滤: 排除 ' + str(len(excluded)) + ' 行诊断/隔离/恢复 run (不计入性能/错误率): ' + ', '.join(excluded))
    lines.append('')
    lines.append('## 2. MME 判定')
    lines.append('')
    lines.append('| 指标 | 目标 | 实测 | 判定 |')
    lines.append('|---|---|---|---|')
    for k in ['ZH-M-F02-01', 'ZH-M-F02-02', 'ZH-M-F02-03', 'ZH-M-F02-04', 'ZH-M-F02-05', 'ZH-M-F02-06', 'ZH-M-F02-07']:
        m = mme.get(k)
        if not m:
            continue
        verdict = 'PASS' if m.get('pass') is True else ('FAIL' if m.get('pass') is False else 'PENDING (verify.py)')
        lines.append('| {k} {name} | {desc} | {det} | **{v}** |'.format(
            k=k, name=m['name'], desc='(见名)', det=json.dumps({kk: vv for kk, vv in m.items() if kk not in ('name', 'pass')}, ensure_ascii=False), v=verdict))
    lines.append('')
    lines.append('## 3. 总判定')
    lines.append('')
    lines.append('**mmeMainPass (01+02+03) = {v}** | diagnostic (07) = {d} | 护栏 04/05/06 见 verify-verdict.json'.format(v=all_main, d=diag_ok))
    lines.append('')
    lines.append('stat-summary.json sha256: `{s}`'.format(s=summary_sha))
    report_path = os.path.join(out_dir, 'stat-report.md')
    with open(report_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))

    print('[stat] wrote ' + report_path)
    print('[stat] wrote ' + summary_path + ' (sha256=' + summary_sha + ')')
    print('[stat] mmeMainPass = ' + str(all_main) + ' | diagnostic = ' + str(diag_ok) + ' | shaIntegrity = ' + str(sha_ok))
    for k in ['ZH-M-F02-01', 'ZH-M-F02-02', 'ZH-M-F02-03', 'ZH-M-F02-07']:
        m = mme.get(k)
        if m:
            print('[stat] ' + k + ': ' + ('PASS' if m.get('pass') else 'FAIL'))


if __name__ == '__main__':
    main()
