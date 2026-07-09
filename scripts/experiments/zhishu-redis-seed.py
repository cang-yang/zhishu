#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ZH-F05 实验数据集 seed 生成器 (Redis 流式会话写放大实验)
experimentId: ZH-EXP-F05-REDIS-STREAM-BUFFER
baselineId:   ZH-BL-F05-PER-CHUNK-SAVE-V1
runType: REAL

生成三档 (S/M/L) deterministic synthetic 数据集:
  - chunks-<scale>.json:  chunk-count 个 chunk, 每个 ~chunk-bytes UTF-8 字节, seed=42 可复现
  - history-<scale>.json: history-size 条历史消息 (role 交替 user/assistant, content 固定)
  - fault-cases.json:     RS-F-001/002/003 故障 case manifest (02 §6.2)
  - dataset-manifest.json: 档位规格 + 每文件 sha256

档位 (02 §6.1):
  S: 5 历史消息 + 20 chunk × 80 字节 (smoke)
  M: 50 历史消息 + 100 chunk × 120 字节 (主性能, N=50 对应命令数分析)
  L: 200 历史消息 + 300 chunk × 160 字节 (退化斜率, N=200 验证 O(1) vs O(N))

所有内容 synthetic 可公开, 无真实用户数据/密钥。
用法:
  python zhishu-redis-seed.py --out-dir .local/项目功能改造与指标评审/runs/zhishu/ZH-EXP-F05-REDIS-STREAM-BUFFER/dataset
"""

import argparse
import hashlib
import json
import os
import random
import sys

SCALES = {
    # scale: (history_size, chunk_count, chunk_bytes)
    'S': (5, 20, 80),
    'M': (50, 100, 120),
    'L': (200, 300, 160),
}

# synthetic 字符池 (中文 + ASCII, 模拟真实 AI 流式回复的 token 分布)
# 固定池, deterministic; 公开无敏感
CN_POOL = '人工智能流式回复缓冲区优化实验数据合成片段模型推理生成内容'
ASCII_POOL = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 '

FAULT_CASES = [
    {
        'caseId': 'RS-F-001',
        'name': 'stop@30%',
        'description': 'chunk 30/100 时 stopFlags=true, handleChunk return, completionMonitor completeAssistantMessage(buffer 30%)',
        'triggerChunkIndex': 30,
        'expectedFinalContentSource': 'buffer',
        'expectedBufferRatio': 0.30,
    },
    {
        'caseId': 'RS-F-002',
        'name': 'ws-close@50%',
        'description': 'chunk 50/100 时 WS 连接断开, completionMonitor 独立线程 completeAssistantMessage(buffer 50%)',
        'triggerChunkIndex': 50,
        'expectedFinalContentSource': 'buffer',
        'expectedBufferRatio': 0.50,
    },
    {
        'caseId': 'RS-F-003',
        'name': 'redis-transient-fail@70%',
        'description': 'chunk 70/100 时 Redis 短暂拒绝 100ms, appendWithRetry 重试/appendBaselinePath 降级, 不丢 chunk',
        'triggerChunkIndex': 70,
        'expectedFinalContentSource': 'buffer',
        'expectedBufferRatio': 1.00,
        'redisDownMs': 100,
    },
]


def gen_chunk(rng, target_bytes):
    """生成 ~target_bytes UTF-8 字节的 chunk (中 ASCII 混合, 模拟 token 流)。"""
    buf = []
    cur = 0
    while cur < target_bytes:
        if rng.random() < 0.5:
            ch = rng.choice(CN_POOL)  # 中文 3 UTF-8 字节
        else:
            ch = rng.choice(ASCII_POOL)  # ASCII 1 字节
        buf.append(ch)
        cur += len(ch.encode('utf-8'))
    return ''.join(buf)


def gen_chunks(scale, chunk_count, chunk_bytes):
    """deterministic: 每 scale 独立 seed (scale+42), 保证跨 run 可复现且档间不同。"""
    rng = random.Random(42 + sum(ord(c) for c in scale))
    return [gen_chunk(rng, chunk_bytes) for _ in range(chunk_count)]


def gen_history(history_size):
    """history_size 条历史消息, role 交替 user/assistant, content 固定 synthetic。"""
    messages = []
    rng = random.Random(4242)  # 历史消息独立 seed, 与 chunks 不重叠
    for i in range(history_size):
        role = 'user' if i % 2 == 0 else 'assistant'
        # content 长度交替, 模拟真实对话
        length = 40 + (i % 5) * 20
        if role == 'user':
            content = '历史问题 ' + str(i) + ': ' + ''.join(rng.choice(ASCII_POOL) for _ in range(length))
        else:
            content = '历史回复 ' + str(i) + ': ' + ''.join(rng.choice(CN_POOL) for _ in range(length // 3))
        messages.append({'seq': i, 'role': role, 'content': content})
    return messages


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(8192), b''):
            h.update(block)
    return h.hexdigest().upper()


def write_json(path, obj):
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, separators=(',', ':'))


def main():
    parser = argparse.ArgumentParser(description='ZH-F05 Redis stream experiment dataset seed')
    parser.add_argument('--out-dir', required=True, help='输出目录')
    parser.add_argument('--scales', default='S,M,L', help='档位 (逗号分隔, 默认 S,M,L)')
    args = parser.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    selected = [s.strip().upper() for s in args.scales.split(',') if s.strip()]

    manifest = {
        'experimentId': 'ZH-EXP-F05-REDIS-STREAM-BUFFER',
        'baselineId': 'ZH-BL-F05-PER-CHUNK-SAVE-V1',
        'datasetId': 'ZH-DS-F05-REDIS-STREAM',
        'producer': 'zhishu-redis-seed.py',
        'seed': 42,
        'contentPolicy': 'synthetic, public, no real user data or secrets',
        'scales': {},
        'faultCasesFile': 'fault-cases.json',
    }

    for scale in selected:
        if scale not in SCALES:
            print('ERROR: unknown scale ' + scale, file=sys.stderr)
            sys.exit(2)
        history_size, chunk_count, chunk_bytes = SCALES[scale]

        chunks = gen_chunks(scale, chunk_count, chunk_bytes)
        history = gen_history(history_size)

        chunks_path = os.path.join(args.out_dir, 'chunks-' + scale + '.json')
        history_path = os.path.join(args.out_dir, 'history-' + scale + '.json')
        write_json(chunks_path, chunks)
        write_json(history_path, history)

        # 验证 chunk 字节分布 (sanity)
        chunk_byte_sizes = [len(c.encode('utf-8')) for c in chunks]
        total_chunk_bytes = sum(chunk_byte_sizes)

        manifest['scales'][scale] = {
            'historySize': history_size,
            'chunkCount': chunk_count,
            'targetChunkBytes': chunk_bytes,
            'actualChunkBytesMin': min(chunk_byte_sizes),
            'actualChunkBytesMax': max(chunk_byte_sizes),
            'totalChunkBytes': total_chunk_bytes,
            'chunksFile': 'chunks-' + scale + '.json',
            'chunksSha256': sha256_file(chunks_path),
            'historyFile': 'history-' + scale + '.json',
            'historySha256': sha256_file(history_path),
        }
        print('[seed] ' + scale + ': history=' + str(history_size) + ' chunks=' + str(chunk_count)
              + ' ~' + str(chunk_bytes) + 'B/chunk total=' + str(total_chunk_bytes) + 'B')

    # 故障 case manifest (与档位无关, 全局共享)
    fault_path = os.path.join(args.out_dir, 'fault-cases.json')
    write_json(fault_path, FAULT_CASES)
    manifest['faultCasesSha256'] = sha256_file(fault_path)

    manifest_path = os.path.join(args.out_dir, 'dataset-manifest.json')
    write_json(manifest_path, manifest)
    manifest_sha = sha256_file(manifest_path)

    print('[seed] dataset-manifest.json sha256=' + manifest_sha)
    print('[seed] out-dir=' + args.out_dir)
    print('[seed] done.')


if __name__ == '__main__':
    main()
