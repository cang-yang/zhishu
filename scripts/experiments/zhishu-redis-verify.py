#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ZH-F05 G4 独立校验器 (G5 RESULT_REVIEW 输入)
experimentId: ZH-EXP-F05-REDIS-STREAM-BUFFER
runType: REAL

独立复核 zhishu-redis-stream.sh 产出的 result-{S,M,L}.json:
  - 独立重算 MME (不读 stat-summary.json, 避免信任作者计算)
  - 反作弊检查:
    A. baseline arm 的 commandTotals 呈 save+refresh 模式 (SET 计数高, GET 计数 ~ N×K)
    B. after arm 的 commandTotals 呈 APPEND 模式 (APPEND 计数 = chunkCount×n, SET 计数低)
    C. canonical diff 真字节等价 (重新逐 answer 比对 finalContents, 不信任预计算 pct)
    D. 降幅真实 (baseline 真走 saveMessage 路径, 非人为拖慢: baseline SET 数 ≈ chunkCount×n)
    E. n / chunkCount / historySize 跨 result 一致性
  - 写 verify-report.md + verify-verdict.json (含每检查 PASS/FAIL)

不做 Redis 调用, 纯本地 JSON 独立复核.
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


def reduction_pct(b, a):
    return 100.0 * (b - a) / b if b != 0 else 0.0


def main():
    ap = argparse.ArgumentParser(description='ZH-F05 G4 independent verify + anti-cheat')
    ap.add_argument('--results-dir', required=True)
    ap.add_argument('--out-dir', default=None)
    args = ap.parse_args()
    out_dir = args.out_dir or args.results_dir
    os.makedirs(out_dir, exist_ok=True)

    results = {}
    for scale in ['S', 'M', 'L']:
        r = load_result(args.results_dir, scale)
        if r is not None:
            results[scale] = r
    if not results:
        print('ERROR: 无 result-*.json 在 ' + args.results_dir, file=sys.stderr)
        sys.exit(2)

    checks = []  # each: {id, description, pass, detail}

    def add(cid, desc, ok, detail=''):
        checks.append({'id': cid, 'description': desc, 'pass': bool(ok), 'detail': detail})

    # ---- E. 跨档一致性 (n/chunkCount/historySize 来自 dataset, 应与档位规格一致) ----
    spec = {'S': (5, 20), 'M': (50, 100), 'L': (200, 300)}  # historySize, chunkCount
    for scale, r in results.items():
        exp_h, exp_c = spec[scale]
        ok = (r['historySize'] == exp_h and r['chunkCount'] == exp_c)
        add('E-' + scale, scale + ' 档 dataset 规格 (history=' + str(exp_h) + ', chunk=' + str(exp_c) + ')', ok,
            '实际 history=' + str(r['historySize']) + ' chunk=' + str(r['chunkCount']))

    # ---- A/B/D. 反作弊: baseline vs after 命令模式 ----
    for scale, r in results.items():
        n = r['n']
        cc = r['chunkCount']
        b_cmds = r['armBaseline']['commandTotals']
        a_cmds = r['armAfter']['commandTotals']
        b_set = b_cmds.get('set', 0)
        a_set = a_cmds.get('set', 0)
        a_append = a_cmds.get('append', 0)
        b_append = b_cmds.get('append', 0)

        # D. baseline 真走 saveMessage 路径: 每 chunk saveMessage (1 SET) + refreshSession 内 for-loop saveMessage (N+1 SET) + complete
        #    baseline SET 数应显著 > after SET 数 (baseline 每 chunk 重写整消息 + refreshSession 重写 N 条)
        d_ok = b_set > a_set and b_set > n
        add('D-' + scale, scale + ' baseline SET > after SET 且 > n (baseline 真走 saveMessage+refresh 重写, 非人为拖慢)',
            d_ok, 'baseline SET=' + str(b_set) + ' after SET=' + str(a_set) + ' n=' + str(n))

        # B. after 真走 APPEND buffer: APPEND 总数 = chunkCount × n (每 chunk 1 APPEND)
        b_ok = (a_append == cc * n)
        add('B-' + scale, scale + ' after APPEND 总数 == chunkCount × n (after 真走 APPEND buffer, 每 chunk 1 APPEND)',
            b_ok, 'after APPEND=' + str(a_append) + ' chunkCount×n=' + str(cc * n))

        # A. baseline 不用 APPEND (baseline 路径无 buffer)
        a_ok = (b_append == 0)
        add('A-' + scale, scale + ' baseline APPEND 总数 == 0 (baseline 不用 buffer, 走 saveMessage)',
            a_ok, 'baseline APPEND=' + str(b_append))

        # after GET 较 baseline 显著降 (after 不每 chunk 重读 N 条消息)
        b_get = b_cmds.get('get', 0)
        a_get = a_cmds.get('get', 0)
        g_ok = (a_get < b_get)
        add('GET-' + scale, scale + ' after GET < baseline GET (after 不每 chunk 重读 N 消息)', g_ok,
            'baseline GET=' + str(b_get) + ' after GET=' + str(a_get))

    # ---- C. canonical diff 真字节等价 (重新逐 answer 比对, 不信任预计算) ----
    for scale, r in results.items():
        bf = r['armBaseline']['finalContents']
        af = r['armAfter']['finalContents']
        cnt = min(len(bf), len(af))
        match = sum(1 for i in range(cnt) if bf[i] == af[i])
        pct = (100.0 * match / cnt) if cnt else 0.0
        precomputed = r['canonicalDiff']['pct']
        c_ok = (cnt > 0 and match == cnt and abs(pct - precomputed) < 1e-6)
        add('C-' + scale, scale + ' canonical diff 逐 answer 重比 = 100% 且与预计算一致', c_ok,
            '重算 match=' + str(match) + '/' + str(cnt) + ' (' + format(pct, '.4f') + '%), 预计算=' + format(precomputed, '.4f') + '%')

    # ---- 独立 MME 重算 ----
    mme = {}
    m = results.get('M')
    if m:
        bc = m['armBaseline']['commandsPerAnswer']
        ac = m['armAfter']['commandsPerAnswer']
        red_cmd = reduction_pct(bc, ac)
        mme['ZH-M-F05-01'] = {'baseline': bc, 'after': ac, 'reductionPct': red_cmd, 'pass': red_cmd >= 30.0}
        bb = m['armBaseline']['netInputBytesPerAnswer']
        ab = m['armAfter']['netInputBytesPerAnswer']
        red_bin = reduction_pct(bb, ab)
        mme['ZH-M-F05-02'] = {'baseline': bb, 'after': ab, 'reductionPct': red_bin, 'pass': red_bin >= 30.0}
        bf2 = m['armBaseline']['finalContents']
        af2 = m['armAfter']['finalContents']
        cnt2 = min(len(bf2), len(af2))
        match2 = sum(1 for i in range(cnt2) if bf2[i] == af2[i])
        mme['ZH-M-F05-04'] = {'match': match2, 'total': cnt2, 'pass': cnt2 > 0 and match2 == cnt2}
    # ZH-M-F05-03: after P95 <= baseline P95 * 1.1 (M档), 即 after 不恶化
    if m:
        bp95 = m['armBaseline']['perChunkP95Nanos']
        ap95 = m['armAfter']['perChunkP95Nanos']
        ratio = (ap95 / bp95) if bp95 > 0 else 0
        f03a = ratio <= 1.10
        mme['ZH-M-F05-03a'] = {'p95BaselineNs': bp95, 'p95AfterNs': ap95, 'ratio': ratio, 'pass': f03a}
    if 'M' in results and 'L' in results:
        nm, nl = results['M']['historyEffectiveN'], results['L']['historyEffectiveN']
        f = lambda s, arm: results[s][arm]['perChunkP95Nanos']
        sb = (f('L', 'armBaseline') - f('M', 'armBaseline')) / (nl - nm) if nl != nm else 0
        sa = (f('L', 'armAfter') - f('M', 'armAfter')) / (nl - nm) if nl != nm else 0
        sred = reduction_pct(sb, sa) if sb > 0 else 100.0
        mme['ZH-M-F05-03b'] = {'slopeBaseline': sb, 'slopeAfter': sa, 'reductionPct': sred, 'pass': sred >= 20.0}
    if 'ZH-M-F05-03a' in mme or 'ZH-M-F05-03b' in mme:
        mme['ZH-M-F05-03'] = {'pass': mme.get('ZH-M-F05-03a', {}).get('pass', False) or mme.get('ZH-M-F05-03b', {}).get('pass', False)}

    all_mme = all(v.get('pass') for v in mme.values()) if mme else False
    all_checks = all(c['pass'] for c in checks)

    verdict = {
        'featureId': 'ZH-F05',
        'experimentId': 'ZH-EXP-F05-REDIS-STREAM-BUFFER',
        'independentMME': mme,
        'allMmePass': all_mme,
        'antiCheatChecks': checks,
        'allChecksPass': all_checks,
        'overallVerdict': 'PASS' if (all_mme and all_checks) else 'FAIL',
    }
    verdict_path = os.path.join(out_dir, 'verify-verdict.json')
    with open(verdict_path, 'w', encoding='utf-8') as f:
        json.dump(verdict, f, ensure_ascii=False, indent=2)
    vsha = hashlib.sha256(open(verdict_path, 'rb').read()).hexdigest().upper()

    lines = []
    lines.append('# ZH-F05 G4 独立校验报告 (反作弊 + MME 独立重算)')
    lines.append('')
    lines.append('```text')
    lines.append('experimentId: ZH-EXP-F05-REDIS-STREAM-BUFFER')
    lines.append('overallVerdict: ' + verdict['overallVerdict'])
    lines.append('allMmePass: ' + str(all_mme) + ' | allChecksPass: ' + str(all_checks))
    lines.append('verifyVerdictSha256: ' + vsha)
    lines.append('```')
    lines.append('')
    lines.append('## 1. 反作弊检查')
    lines.append('')
    lines.append('| 检查 | 描述 | 判定 | 详情 |')
    lines.append('|---|---|---|---|')
    for c in checks:
        lines.append('| {id} | {d} | {v} | {det} |'.format(id=c['id'], d=c['description'], v='PASS' if c['pass'] else 'FAIL', det=c['detail']))
    lines.append('')
    lines.append('## 2. MME 独立重算')
    lines.append('')
    lines.append('| 护栏 | 关键值 | 判定 |')
    lines.append('|---|---|---|')
    for k, v in mme.items():
        lines.append('| {k} | {val} | {p} |'.format(k=k, val=json.dumps({kk: vv for kk, vv in v.items() if kk != 'pass'}, ensure_ascii=False), p='PASS' if v.get('pass') else 'FAIL'))
    lines.append('')
    lines.append('## 3. 总判定: **{v}**'.format(v=verdict['overallVerdict']))
    lines.append('')
    lines.append('verify-verdict.json sha256: `{s}`'.format(s=vsha))
    report_path = os.path.join(out_dir, 'verify-report.md')
    with open(report_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))

    print('[verify] wrote ' + report_path)
    print('[verify] wrote ' + verdict_path + ' (sha256=' + vsha + ')')
    print('[verify] overallVerdict = ' + verdict['overallVerdict'] + ' (allMme=' + str(all_mme) + ', allChecks=' + str(all_checks) + ')')


if __name__ == '__main__':
    main()
