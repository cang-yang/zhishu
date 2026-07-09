#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ZH-F02 实验数据集 seed 生成器 (分片上传有界并发实验)
experimentId: ZH-EXP-F02-UPLOAD
baselineId:   ZH-BL-F02-SERIAL-UPLOAD-V1
runType: REAL

生成三档 deterministic synthetic 上传文件:
  - upload-<scale>.txt: 16/64/256 MiB 固定内容 (seed=42, 可公开, 无真实数据/密钥)
    (.txt 而非 .bin — 后端 UploadController 仅放行文档类型扩展名, .bin 会被 FileTypeValidationService 拒 400; 内容为 deterministic 随机字节, 仅作上传吞吐载体, 与异步 Tika 解析无关)
  - dataset-manifest.json: 档位规格 + 每文件 sha256 (完整性) + md5 (fileMd5 引用)

档位 (02 §6.1):
  S:  16  MiB (诊断小档)
  M:  64  MiB (主性能档, MME ZH-M-F02-01/02/07)
  L:  256 MiB (诊断大档, 退化/资源诊断)

算法: 生成 1 MiB seeded 字节块 (random.Random(42).randbytes), 平铺至目标大小截断。
  - deterministic: 跨 run 跨机器可复现, sha256 固定
  - synthetic: 无真实用户数据/无密钥 (内容为伪随机字节, 非文本)

用法:
  python zhishu-upload-seed.py --out-dir .local/项目功能改造与指标评审/runs/zhishu/ZH-EXP-F02-UPLOAD/dataset
"""

import argparse
import hashlib
import json
import os
import random
import sys

# 1 MiB seeded 块 (平铺单元); random.Random.randbytes Python 3.9+
BLOCK_BYTES = 1024 * 1024

SCALES = {
    # scale: (size_mib, role)
    'S': (16, '诊断小档'),
    'M': (64, '主性能档 (MME 主指标)'),
    'L': (256, '诊断大档 (退化/资源)'),
}


def gen_block(seed):
    """生成 1 MiB seeded 字节块 (deterministic, synthetic)."""
    return random.Random(seed).randbytes(BLOCK_BYTES)


def write_file_tiled(path, size_bytes, seed):
    """平铺 seed 块至 size_bytes (写文件, 避免全量驻留内存 for 大档)."""
    block = gen_block(seed)
    written = 0
    with open(path, 'wb') as f:
        while written < size_bytes:
            remain = size_bytes - written
            chunk = block if remain >= len(block) else block[:remain]
            f.write(chunk)
            written += len(chunk)


def hash_file(path, algo):
    """流式计算 hash (8192 块), 避免大档全量读."""
    h = hashlib.new(algo)
    with open(path, 'rb') as f:
        for blk in iter(lambda: f.read(65536), b''):
            h.update(blk)
    return h.hexdigest().upper()


def write_json(path, obj):
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, separators=(',', ':'))


def main():
    ap = argparse.ArgumentParser(description='ZH-F02 upload experiment dataset seed')
    ap.add_argument('--out-dir', required=True, help='输出目录')
    ap.add_argument('--scales', default='S,M,L', help='档位 (逗号分隔, 默认 S,M,L)')
    ap.add_argument('--seed', type=int, default=42, help='随机种子 (默认 42)')
    args = ap.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    selected = [s.strip().upper() for s in args.scales.split(',') if s.strip()]

    manifest = {
        'experimentId': 'ZH-EXP-F02-UPLOAD',
        'baselineId': 'ZH-BL-F02-SERIAL-UPLOAD-V1',
        'datasetId': 'ZH-DS-F02-UPLOAD-FILES',
        'producer': 'zhishu-upload-seed.py',
        'seed': args.seed,
        'chunkSizeBytes': 5 * 1024 * 1024,  # 与前端 constants/common.ts:15 + backend DEFAULT_CHUNK_SIZE_BYTES 一致
        'contentPolicy': 'synthetic, public, no real user data or secrets',
        'scales': {},
    }

    for scale in selected:
        if scale not in SCALES:
            print('ERROR: unknown scale ' + scale, file=sys.stderr)
            sys.exit(2)
        size_mib, role = SCALES[scale]
        size_bytes = size_mib * 1024 * 1024
        # 每 scale 独立 seed (seed + scale 偏移), 档间内容不同, 跨 run 可复现
        file_seed = args.seed + sum(ord(c) for c in scale)
        fname = 'upload-' + scale + '.txt'
        fpath = os.path.join(args.out_dir, fname)
        write_file_tiled(fpath, size_bytes, file_seed)
        sha = hash_file(fpath, 'sha256')
        md5 = hash_file(fpath, 'md5')  # fileMd5 引用 (harness 自算以匹配前端 SparkMD5; 此处记录供一致性核对)
        total_chunks = (size_bytes + manifest['chunkSizeBytes'] - 1) // manifest['chunkSizeBytes']
        manifest['scales'][scale] = {
            'role': role,
            'sizeBytes': size_bytes,
            'sizeMiB': size_mib,
            'totalChunks': total_chunks,
            'file': fname,
            'sha256': sha,
            'md5': md5,
        }
        print('[seed] ' + scale + ': ' + str(size_mib) + 'MiB ' + fname + ' sha256=' + sha[:16] + '... md5=' + md5[:16] + '... chunks=' + str(total_chunks))

    manifest_path = os.path.join(args.out_dir, 'dataset-manifest.json')
    write_json(manifest_path, manifest)
    manifest_sha = hash_file(manifest_path, 'sha256')
    print('[seed] dataset-manifest.json sha256=' + manifest_sha)
    print('[seed] out-dir=' + args.out_dir)
    print('[seed] done.')


if __name__ == '__main__':
    main()
