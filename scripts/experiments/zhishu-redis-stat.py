#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ZH-F05 G4 EXPERIMENT 统计与 MME 判定
experimentId: ZH-EXP-F05-REDIS-STREAM-BUFFER
runType: REAL

消费 zhishu-redis-stream.sh 产出的 result-{S,M,L}.json, 计算:
  - commands/answer (baseline vs after) + 降幅%
  - bytes/answer (net input/output, baseline vs after) + 降幅%
  - per-chunk P50/P95/min/max/mean (ns)
  - L 档退化斜率 (P95 vs N 的斜率, baseline vs after)
  - canonical diff 一致性 %
  - MME 判定: ZH-M-F05-01 (commands/answer 降≥30% M档)
              ZH-M-F05-02 (bytes/answer 降≥30% M档)
              ZH-M-F05-03 (P95 不恶化>10% OR L档斜率降≥20%)
              ZH-M-F05-04 (canonical diff 100% M档)

输出:
  stat-report.md   (人类可读)
  stat-summary.json (机器可读, 供 verify.py 独立复核 + G5 审查)

不做任何 Redis 调用, 纯本地 JSON 统计. 可离线运行.
"""

import argparse
import hashlib
import json
import os
import sys


def load_result(results_dir, scale):
    path = os.path.join(results_dir, 'result-' + scale + '.json')
    if not os.path.exists(path):
        return None
    with open(path, 'r', encoding='utf-8') as f:
        return json.load(f)


def reduction_pct(baseline, after):
    if baseline == 0:
        return 0.0
    return 100.0 * (baseline - after) / baseline


def slope(n_hi, p95_hi, n_lo, p95_lo):
    """每单位 N 的 P95 斜率 (ns per visible-message)."""
    if n_hi == n_lo:
        return 0.0
    return (p95_hi - p95_lo) / (n_hi - n_lo)


def main():
    ap = argparse.ArgumentParser(description='ZH-F05 G4 stat + MME judgment')
    ap.add_argument('--results-dir', required=True, help='result-{S,M,L}.json 所在目录')
    ap.add_argument('--out-dir', default=None, help='输出目录 (默认 = results-dir)')
    args = ap.parse_args()
    out_dir = args.out_dir or args.results_dir
    os.makedirs(out_dir, exist_ok=True)

    scales_data = {}
    for scale in ['S', 'M', 'L']:
        r = load_result(args.results_dir, scale)
        if r is not None:
            scales_data[scale] = r

    if not scales_data:
        print('ERROR: 无 result-*.json 在 ' + args.results_dir, file=sys.stderr)
        sys.exit(2)

    # ---- 逐档统计 ----
    per_scale = {}
    for scale, r in scales_data.items():
        b = r['armBaseline']
        a = r['armAfter']
        n = r['n']
        b_cmds = b['commandsPerAnswer']
        a_cmds = a['commandsPerAnswer']
        b_bin = b['netInputBytesPerAnswer']
        a_bin = a['netInputBytesPerAnswer']
        b_bout = b['netOutputBytesPerAnswer']
        a_bout = a['netOutputBytesPerAnswer']
        cd = r['canonicalDiff']
        per_scale[scale] = {
            'n': n,
            'chunkCount': r['chunkCount'],
            'historySize': r['historySize'],
            'historyEffectiveN': r['historyEffectiveN'],
            'commandsPerAnswer': {'baseline': b_cmds, 'after': a_cmds, 'reductionPct': reduction_pct(b_cmds, a_cmds)},
            'netInputBytesPerAnswer': {'baseline': b_bin, 'after': a_bin, 'reductionPct': reduction_pct(b_bin, a_bin)},
            'netOutputBytesPerAnswer': {'baseline': b_bout, 'after': a_bout, 'reductionPct': reduction_pct(b_bout, a_bout)},
            'perChunkP95Nanos': {'baseline': b['perChunkP95Nanos'], 'after': a['perChunkP95Nanos']},
            'perChunkP50Nanos': {'baseline': b['perChunkP50Nanos'], 'after': a['perChunkP50Nanos']},
            'perChunkMeanNanos': {'baseline': b['perChunkMeanNanos'], 'after': a['perChunkMeanNanos']},
            'armElapsedMs': {'baseline': b['armElapsedMs'], 'after': a['armElapsedMs']},
            'canonicalDiff': cd,
            'commandTotalsBaseline': b['commandTotals'],
            'commandTotalsAfter': a['commandTotals'],
        }

    # ---- MME 判定 ----
    mme = {}
    m_data = scales_data.get('M')
    if m_data:
        b_cmds_m = m_data['armBaseline']['commandsPerAnswer']
        a_cmds_m = m_data['armAfter']['commandsPerAnswer']
        m_red_cmd = reduction_pct(b_cmds_m, a_cmds_m)
        mme['ZH-M-F05-01'] = {
            'name': 'M档 commands/answer 降 >= 30%',
            'baseline': b_cmds_m, 'after': a_cmds_m,
            'reductionPct': m_red_cmd, 'threshold': 30.0,
            'pass': m_red_cmd >= 30.0,
        }

        b_bin_m = m_data['armBaseline']['netInputBytesPerAnswer']
        a_bin_m = m_data['armAfter']['netInputBytesPerAnswer']
        m_red_bin = reduction_pct(b_bin_m, a_bin_m)
        mme['ZH-M-F05-02'] = {
            'name': 'M档 bytes/answer (net input) 降 >= 30%',
            'baseline': b_bin_m, 'after': a_bin_m,
            'reductionPct': m_red_bin, 'threshold': 30.0,
            'pass': m_red_bin >= 30.0,
        }

        cd_m = m_data['canonicalDiff']
        mme['ZH-M-F05-04'] = {
            'name': 'M档最终消息 canonical diff 100%',
            'match': cd_m['match'], 'total': cd_m['total'], 'pct': cd_m['pct'],
            'threshold': 100.0,
            'pass': abs(cd_m['pct'] - 100.0) < 1e-9,
        }

    # ZH-M-F05-03: P95 不恶化>10% OR L档斜率降≥20%
    p95_pass = None
    p95_detail = {}
    if m_data:
        b_p95_m = m_data['armBaseline']['perChunkP95Nanos']
        a_p95_m = m_data['armAfter']['perChunkP95Nanos']
        # after P95 应 <= baseline (after 更快). 不恶化 = after <= baseline * 1.1
        ratio = (a_p95_m / b_p95_m) if b_p95_m > 0 else 0
        p95_pass_a = ratio <= 1.10
        p95_detail['M_p95Ratio_afterOverBaseline'] = ratio
        p95_detail['M_p95BaselineNs'] = b_p95_m
        p95_detail['M_p95AfterNs'] = a_p95_m
        p95_detail['criterionA_pass'] = p95_pass_a
        p95_pass = p95_pass_a

    slope_pass = None
    slope_detail = {}
    if 'M' in scales_data and 'L' in scales_data:
        n_m = scales_data['M']['historyEffectiveN']
        n_l = scales_data['L']['historyEffectiveN']
        p95_m_b = scales_data['M']['armBaseline']['perChunkP95Nanos']
        p95_m_a = scales_data['M']['armAfter']['perChunkP95Nanos']
        p95_l_b = scales_data['L']['armBaseline']['perChunkP95Nanos']
        p95_l_a = scales_data['L']['armAfter']['perChunkP95Nanos']
        slope_b = slope(n_l, p95_l_b, n_m, p95_m_b)  # baseline 斜率 (应 >0, P95 随 N 增长)
        slope_a = slope(n_l, p95_l_a, n_m, p95_m_a)  # after 斜率 (应~0, P95 平坦)
        slope_red = reduction_pct(slope_b, slope_a) if slope_b > 0 else (100.0 if slope_b <= 0 >= slope_a else 0.0)
        slope_pass = slope_red >= 20.0
        slope_detail = {
            'N_M': n_m, 'N_L': n_l,
            'slopeBaselineNsPerN': slope_b, 'slopeAfterNsPerN': slope_a,
            'slopeReductionPct': slope_red, 'threshold': 20.0,
            'criterionB_pass': slope_pass,
        }

    # ZH-M-F05-03 PASS = criterionA OR criterionB
    f03_criteria = [c for c in [p95_pass, slope_pass] if c is not None]
    mme['ZH-M-F05-03'] = {
        'name': 'P95 不恶化>10% OR L档斜率降>=20%',
        'criterionA_p95NotWorsen': p95_detail,
        'criterionB_lSlopeReduction': slope_detail,
        'pass': any(f03_criteria),
    }

    all_pass = all(m['pass'] for m in mme.values()) if mme else False

    # ---- 汇总 ----
    summary = {
        'featureId': 'ZH-F05',
        'experimentId': 'ZH-EXP-F05-REDIS-STREAM-BUFFER',
        'mme': mme,
        'allMmePass': all_pass,
        'perScale': per_scale,
    }
    summary_path = os.path.join(out_dir, 'stat-summary.json')
    with open(summary_path, 'w', encoding='utf-8') as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    summary_sha = hashlib.sha256(open(summary_path, 'rb').read()).hexdigest().upper()

    # ---- 人类可读报告 ----
    lines = []
    lines.append('# ZH-F05 G4 实验统计报告 (MME 判定)')
    lines.append('')
    lines.append('```text')
    lines.append('experimentId: ZH-EXP-F05-REDIS-STREAM-BUFFER')
    lines.append('baselineId:   ZH-BL-F05-PER-CHUNK-SAVE-V1')
    lines.append('runType: REAL')
    lines.append('allMmePass: ' + str(all_pass))
    lines.append('statSummarySha256: ' + summary_sha)
    lines.append('```')
    lines.append('')
    lines.append('## 1. 逐档统计')
    lines.append('')
    lines.append('| 档 | n | chunkCount | histN | cmds/answer baseline | cmds/answer after | 降幅% | bytesIn/answer baseline | bytesIn/answer after | 降幅% | P95ns baseline | P95ns after | canonical% |')
    lines.append('|---|---|---|---|---|---|---|---|---|---|---|---|---|')
    for scale in ['S', 'M', 'L']:
        if scale not in per_scale:
            continue
        p = per_scale[scale]
        lines.append('| {scale} | {n} | {cc} | {hn} | {bc:.1f} | {ac:.1f} | {rc:.2f}% | {bbi:.0f} | {abi:.0f} | {rbi:.2f}% | {bp} | {ap} | {cdp:.2f}% |'.format(
            scale=scale, n=p['n'], cc=p['chunkCount'], hn=p['historyEffectiveN'],
            bc=p['commandsPerAnswer']['baseline'], ac=p['commandsPerAnswer']['after'], rc=p['commandsPerAnswer']['reductionPct'],
            bbi=p['netInputBytesPerAnswer']['baseline'], abi=p['netInputBytesPerAnswer']['after'], rbi=p['netInputBytesPerAnswer']['reductionPct'],
            bp=p['perChunkP95Nanos']['baseline'], ap=p['perChunkP95Nanos']['after'],
            cdp=p['canonicalDiff']['pct']))
    lines.append('')
    lines.append('## 2. MME 判定')
    lines.append('')
    lines.append('| 护栏 | 目标 | 实测 | 判定 |')
    lines.append('|---|---|---|---|')
    for k in ['ZH-M-F05-01', 'ZH-M-F05-02', 'ZH-M-F05-03', 'ZH-M-F05-04']:
        if k not in mme:
            continue
        m = mme[k]
        if k == 'ZH-M-F05-01':
            detail = '降幅 {r:.2f}%'.format(r=m['reductionPct'])
        elif k == 'ZH-M-F05-02':
            detail = '降幅 {r:.2f}%'.format(r=m['reductionPct'])
        elif k == 'ZH-M-F05-03':
            a = m['criterionA_p95NotWorsen']
            b = m['criterionB_lSlopeReduction']
            detail = 'P95比 {ra:.3f} (≤1.1: {pa}) | L斜率降 {sb:.2f}% (≥20%: {pb})'.format(
                ra=a.get('M_p95Ratio_afterOverBaseline', 0), pa=a.get('criterionA_pass'),
                sb=b.get('slopeReductionPct', 0), pb=b.get('criterionB_pass'))
        else:
            detail = '{match}/{total} = {pct:.2f}%'.format(match=m['match'], total=m['total'], pct=m['pct'])
        verdict = 'PASS' if m['pass'] else 'FAIL'
        lines.append('| {k} {name} | {thr} | {detail} | **{verdict}** |'.format(
            k=k, name=m['name'], thr=('降≥30%' if '30' in m['name'] else ('100%' if '100' in m['name'] else 'A或B')), detail=detail, verdict=verdict))
    lines.append('')
    lines.append('## 3. 命令数分解 (after arm, 验证 APPEND buffer 路径)')
    lines.append('')
    for scale in ['M']:
        if scale not in per_scale:
            continue
        p = per_scale[scale]
        lines.append('### M 档 after arm commandTotals (n={n}, chunkCount={cc})'.format(n=p['n'], cc=p['chunkCount']))
        lines.append('')
        lines.append('| 命令 | baseline 总数 | after 总数 |')
        lines.append('|---|---|---|')
        all_cmds = sorted(set(list(p['commandTotalsBaseline'].keys()) + list(p['commandTotalsAfter'].keys())))
        for c in all_cmds:
            lines.append('| {c} | {b} | {a} |'.format(c=c, b=p['commandTotalsBaseline'].get(c, 0), a=p['commandTotalsAfter'].get(c, 0)))
        lines.append('')
    lines.append('## 4. 总判定')
    lines.append('')
    lines.append('**allMmePass = {v}**'.format(v=all_pass))
    lines.append('')
    lines.append('stat-summary.json sha256: `{s}`'.format(s=summary_sha))

    report_path = os.path.join(out_dir, 'stat-report.md')
    with open(report_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))

    print('[stat] wrote ' + report_path)
    print('[stat] wrote ' + summary_path + ' (sha256=' + summary_sha + ')')
    print('[stat] allMmePass = ' + str(all_pass))
    for k, m in mme.items():
        print('[stat] ' + k + ': ' + ('PASS' if m['pass'] else 'FAIL'))


if __name__ == '__main__':
    main()
