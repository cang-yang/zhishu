#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ZH-F02 G4 EXPERIMENT 采集 harness (分片上传有界并发)
experimentId: ZH-EXP-F02-UPLOAD
baselineId:   ZH-BL-F02-SERIAL-UPLOAD-V1
runType: REAL

驱动真实后端 HTTP (非 mock), 忠实复现前端 store worker pool 算法 (R8 等价性):
  - baseline arm: 串行 for-await (复现当前生产; concurrency=1)
  - after arm:    ThreadPoolExecutor 有界并发 pool + cursor 派发 + fail-fast + union merge
                  (复现补丁后生产: frontend/src/utils/uploadScheduler.ts runUploadPool
                   + store/modules/knowledge-base/index.ts uploadChunk/startUpload)

后端 API (G2 确认, UploadController / UserController):
  POST /api/v1/users/login      {username,password} -> data.token
  POST /api/v1/upload/chunk     multipart(fileMd5,chunkIndex,totalSize,fileName,file,isPublic) -> data.uploaded,data.progress
  GET  /api/v1/upload/status?file_md5=<md5> -> data.uploaded,data.fileName,data.fileType
  POST /api/v1/upload/merge     {fileMd5,fileName} -> data.object_url  (非所有者 -> RuntimeException -> 500, line 296)

认证: Bearer JWT header. fileMd5 = hashlib.md5(全文件) (与前端 SparkMD5 一致, R9).

R10 响应乱序确定性诊断 (--r10-diag): after arm 额外 1 run, 对 chunkIndex=0 worker
  在响应处理前 sleep(0.2~0.5s) 强制其响应最后到达; 断言 uploadedChunks 单调 (并集)
  且 merge 200. ThreadPoolExecutor 自然非确定序已概率覆盖, 此 run 提供确定性证据.

单次调用完成 1 个 run (1 文件 / 1 arm / 1 并发度), 追加 1 行 JSON 到 --results-jsonl.
wrapper (zhishu-upload.sh) 交替调用 baseline/after, n=20 主档 + 5 诊断.

用法:
  python zhishu-upload-harness.py --variant baseline --file upload-M.bin --base-url http://localhost:18083 \\
      --username u_a --password P_a --results-jsonl raw-results.jsonl --run-id R001
  python zhishu-upload-harness.py --variant after --file upload-M.bin --concurrency 4 ... --r10-diag
"""

import argparse
import hashlib
import json
import os
import random
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import requests

CHUNK_SIZE = 5 * 1024 * 1024  # 与前端 + 后端一致 (constants/common.ts:15 / DEFAULT_CHUNK_SIZE_BYTES)
LOGIN_PATH = '/api/v1/users/login'
CHUNK_PATH = '/api/v1/upload/chunk'
STATUS_PATH = '/api/v1/upload/status'
MERGE_PATH = '/api/v1/upload/merge'


def md5_file(path):
    h = hashlib.md5()
    with open(path, 'rb') as f:
        for blk in iter(lambda: f.read(65536), b''):
            h.update(blk)
    return h.hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for blk in iter(lambda: f.read(65536), b''):
            h.update(blk)
    return h.hexdigest().upper()


def login(base_url, username, password):
    r = requests.post(base_url + LOGIN_PATH, json={'username': username, 'password': password}, timeout=30)
    if r.status_code != 200:
        raise RuntimeError('login HTTP ' + str(r.status_code) + ': ' + r.text[:200])
    body = r.json()
    if body.get('code') != 200:
        raise RuntimeError('login code ' + str(body.get('code')) + ': ' + str(body.get('message')))
    return body['data']['token']


def union_merge(prev, incoming):
    """R10 并集 (monotonic): frontend index.ts:55 同公式. 并发下响应非确定序到达,
    data.uploaded 为后端 bitmap 当时刻快照, 并集保证 uploadedChunks 只增不倒退."""
    return sorted(set(prev) | set(incoming))


def upload_chunk(session, base_url, md5, chunk_index, total_size, file_name, chunk_bytes):
    """单分片上传, 返回 (ok, uploaded_list, duration_ms, status_code). 复现 store uploadChunk."""
    t0 = time.perf_counter()
    files = {'file': (file_name, chunk_bytes, 'application/octet-stream')}
    data = {
        'fileMd5': md5,
        'chunkIndex': str(chunk_index),
        'totalSize': str(total_size),
        'fileName': file_name,
        'isPublic': 'false',
    }
    try:
        r = session.post(base_url + CHUNK_PATH, files=files, data=data, timeout=600)
    except requests.RequestException as e:
        return False, [], (time.perf_counter() - t0) * 1000, 0, str(e)
    dur = (time.perf_counter() - t0) * 1000
    if r.status_code != 200:
        return False, [], dur, r.status_code, 'HTTP ' + str(r.status_code)
    try:
        body = r.json()
    except ValueError:
        return False, [], dur, r.status_code, 'non-json'
    if body.get('code') != 200:
        return False, [], dur, r.status_code, 'code ' + str(body.get('code'))
    return True, body.get('data', {}).get('uploaded', []), dur, r.status_code, 'ok'


def run_pool(chunk_indices, concurrency, worker_fn):
    """复现 runUploadPool (uploadScheduler.ts): cursor 派发 + fail-fast.
    concurrency<=1 串行 (baseline 兼容). 任一 worker 抛错/失败 -> 停止派发, 返回首个错误."""
    first_error = [None]

    if concurrency <= 1:
        for i in chunk_indices:
            err = worker_fn(i)
            if err is not None:
                first_error[0] = err
                break
        return first_error[0]

    cursor = [0]
    lock = threading.Lock()

    def spawn():
        while True:
            with lock:
                if first_error[0] is not None:
                    return
                idx_pos = cursor[0]
                cursor[0] += 1
            if idx_pos >= len(chunk_indices):
                return
            err = worker_fn(chunk_indices[idx_pos])
            if err is not None:
                with lock:
                    if first_error[0] is None:
                        first_error[0] = err
                return

    n = min(concurrency, len(chunk_indices))
    with ThreadPoolExecutor(max_workers=n) as ex:
        futs = [ex.submit(spawn) for _ in range(n)]
        for f in futs:
            f.result()
    return first_error[0]


def main():
    ap = argparse.ArgumentParser(description='ZH-F02 G4 upload harness (single run)')
    ap.add_argument('--variant', required=True, choices=['baseline', 'after'])
    ap.add_argument('--file', required=True, help='上传文件路径 (seed 产物)')
    ap.add_argument('--base-url', required=True, help='后端 base URL (含端口, 如 http://localhost:18083)')
    ap.add_argument('--username', required=True)
    ap.add_argument('--password', required=True)
    ap.add_argument('--token', default=None, help='预取 token (覆盖 login)')
    ap.add_argument('--concurrency', type=int, default=4, help='after arm 并发度 (baseline 忽略, 恒串行)')
    ap.add_argument('--network', default='local', help='网络档 (local/weak1/weak2, 仅记录; 整形由 Toxiproxy 外部配)')
    ap.add_argument('--scale', default='M', help='档位标识 (S/M/L, 记录用)')
    ap.add_argument('--results-jsonl', required=True, help='结果追加文件 (raw-results.jsonl)')
    ap.add_argument('--run-id', required=True)
    ap.add_argument('--resume', action='store_true', help='恢复模式: 先 GET /status 取已传分片, 跳过 (RC-001/003)')
    ap.add_argument('--stop-after-chunk', type=int, default=None, help='RC 故障注入: 上传至该 chunkIndex (含) 后停止, 不 merge (模拟中断)')
    ap.add_argument('--r10-diag', action='store_true', help='R10 响应乱序确定性诊断 run (after arm)')
    ap.add_argument('--seed', type=int, default=42)
    args = ap.parse_args()

    file_name = os.path.basename(args.file)
    total_size = os.path.getsize(args.file)
    total_chunks = (total_size + CHUNK_SIZE - 1) // CHUNK_SIZE
    md5 = md5_file(args.file)  # R9: 与前端 SparkMD5 一致
    sha = sha256_file(args.file)

    # 认证
    token = args.token or login(args.base_url, args.username, args.password)
    session = requests.Session()
    session.headers.update({'Authorization': 'Bearer ' + token})

    # 预读文件分片索引 (恢复友好: resume 时 GET /status 取已传)
    uploaded_chunks = []
    if args.resume:
        r = session.get(args.base_url + STATUS_PATH, params={'file_md5': md5}, timeout=30)
        if r.status_code == 200 and r.json().get('code') == 200:
            uploaded_chunks = sorted(r.json().get('data', {}).get('uploaded', []))
    remaining = [i for i in range(total_chunks) if i not in set(uploaded_chunks)]

    # RC 故障注入: 限制只上传到 stop_after_chunk (含), 不触 merge (模拟中断)
    stop_after = args.stop_after_chunk
    if stop_after is not None:
        remaining = [i for i in remaining if i <= stop_after]

    # 预切分片 bytes (单 run 内复用; 大档 L=256MiB 全量驻留可接受)
    def read_chunk(i):
        with open(args.file, 'rb') as f:
            f.seek(i * CHUNK_SIZE)
            return f.read(min(CHUNK_SIZE, total_size - i * CHUNK_SIZE))

    chunk_durations = []
    state_lock = threading.Lock()
    r10_monotonic_ok = True  # R10: 并集单调 ( uploaded_chunks 长度只增 )
    last_len = [len(uploaded_chunks)]

    def worker(i):
        nonlocal r10_monotonic_ok
        chunk_bytes = read_chunk(i)
        # R10 诊断: chunkIndex=0 worker 响应处理前 sleep, 强制其响应最后到达
        r10_sleep = 0.0
        if args.r10_diag and i == 0:
            r10_sleep = random.Random(args.seed + int(time.time() * 1000) % 1000).uniform(0.2, 0.5)
        ok, upl, dur, sc, msg = upload_chunk(session, args.base_url, md5, i, total_size, file_name, chunk_bytes)
        if r10_sleep > 0:
            time.sleep(r10_sleep)
        with state_lock:
            chunk_durations.append(dur)
            if ok:
                new_merged = union_merge(uploaded_chunks, upl)
                # R10 单调性断言 (并集下长度只增)
                if len(new_merged) < last_len[0]:
                    r10_monotonic_ok = False
                uploaded_chunks.clear()
                uploaded_chunks.extend(new_merged)
                last_len[0] = len(uploaded_chunks)
            if not ok:
                return 'chunk ' + str(i) + ' failed: ' + msg + ' (HTTP ' + str(sc) + ')'
        return None

    t_start = time.perf_counter()
    concurrency = 1 if args.variant == 'baseline' else args.concurrency
    pool_error = run_pool(remaining, concurrency, worker)
    # merge 收口 (R2: pool 全成功后单次 merge). stop-after 模式 (RC 故障注入 / 隔离 case-3) 抑制 merge.
    merge_suppressed = stop_after is not None
    merge_status = None
    merge_ok = False
    merge_obj_url = None
    if pool_error is None and not merge_suppressed and len(uploaded_chunks) == total_chunks:
        t_merge0 = time.perf_counter()
        try:
            r = session.post(args.base_url + MERGE_PATH, json={'fileMd5': md5, 'fileName': file_name}, timeout=600)
            merge_status = r.status_code
            if r.status_code == 200 and r.json().get('code') == 200:
                merge_ok = True
                merge_obj_url = r.json().get('data', {}).get('object_url')
        except requests.RequestException as e:
            merge_status = 'exception: ' + str(e)
    else:
        merge_status = 'skipped (pool_error=' + str(pool_error) + ' or incomplete: ' + str(len(uploaded_chunks)) + '/' + str(total_chunks) + ')'
    t_end = time.perf_counter()

    duration_ms = (t_end - t_start) * 1000.0
    duration_sec = max(t_end - t_start, 1e-9)
    throughput_mibps = (total_size / (1024 * 1024)) / duration_sec

    chunk_durations.sort()
    def pct(p):
        if not chunk_durations:
            return 0.0
        idx = min(len(chunk_durations) - 1, max(0, int((p / 100.0) * len(chunk_durations))))
        return chunk_durations[idx]
    chunk_p50 = pct(50)
    chunk_p90 = pct(90)
    chunk_p95 = pct(95)

    result = {
        'runId': args.run_id,
        'experimentId': 'ZH-EXP-F02-UPLOAD',
        'baselineId': 'ZH-BL-F02-SERIAL-UPLOAD-V1',
        'variant': args.variant,
        'concurrency': concurrency,
        'scale': args.scale,
        'network': args.network,
        'fileName': file_name,
        'fileSizeBytes': total_size,
        'fileSha256': sha,
        'fileMd5': md5,
        'totalChunks': total_chunks,
        'uploadedChunksFinal': sorted(uploaded_chunks),
        'durationMs': round(duration_ms, 2),
        'throughputMibps': round(throughput_mibps, 4),
        'chunkP50Ms': round(chunk_p50, 2),
        'chunkP90Ms': round(chunk_p90, 2),
        'chunkP95Ms': round(chunk_p95, 2),
        'poolError': pool_error,
        'mergeStatus': merge_status,
        'mergeOk': merge_ok,
        'mergeObjectUrl': merge_obj_url,
        'resume': args.resume,
        'stoppedPartial': stop_after is not None,
        'stopAfterChunk': stop_after,
        'r10Diag': args.r10_diag,
        'r10MonotonicOk': r10_monotonic_ok if args.r10_diag else None,
        'user': args.username,
        'producedAt': int(time.time()),
    }

    os.makedirs(os.path.dirname(os.path.abspath(args.results_jsonl)), exist_ok=True)
    with open(args.results_jsonl, 'a', encoding='utf-8') as f:
        f.write(json.dumps(result, ensure_ascii=False) + '\n')

    # 控制台摘要
    print('[harness] run=' + args.run_id + ' ' + args.variant + ' c=' + str(concurrency)
          + ' ' + args.scale + ' dur=' + str(round(duration_ms, 0)) + 'ms'
          + ' thr=' + str(round(throughput_mibps, 2)) + 'MiB/s'
          + ' merge=' + ('OK' if merge_ok else 'FAIL')
          + (' r10monotonic=' + str(r10_monotonic_ok) if args.r10_diag else ''))
    if pool_error:
        print('[harness] POOL_ERROR: ' + pool_error, file=sys.stderr)
        sys.exit(3)


if __name__ == '__main__':
    main()
