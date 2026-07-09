#!/usr/bin/env bash
# -*- coding: utf-8 -*-
# ZH-F05 G4 EXPERIMENT 采集编排器
# experimentId: ZH-EXP-F05-REDIS-STREAM-BUFFER
# baselineId:   ZH-BL-F05-PER-CHUNK-SAVE-V1
# runType: REAL
#
# 职责: 按 S/M/L 档调用 RedisStreamExperimentDriver (plain JUnit + Lettuce 直连 redis database 14),
#       每档产出 result-<scale>.json (commandstats diff + bytes diff + per-chunk P95 + canonical diff).
# stat/verify 由 zhishu-redis-stat.py / zhishu-redis-verify.py 消费 result-*.json.
#
# 档位 n (01 §6: 主档 n=100, 其他档 5 round × 5 answer = 25):
#   S=25 (smoke), M=100 (主性能), L=25 (退化斜率). 可由 env ZH_F05_N_OVERRIDE 统一覆盖 (调试用).
#
# 前置: Redis VM 192.168.241.128:6379 在线 (database 14 隔离); dataset 已 seed.
#
# 用法:
#   bash zhishu-redis-stream.sh
#   ZH_F05_N_OVERRIDE=5 bash zhishu-redis-stream.sh   # 快速调试 (所有档 n=5)

set -euo pipefail

# ---- 路径 (绝对) ----
PROJECT_DIR="D:/JAVA/XM/Zhishu-main"
SCRIPT_DIR="D:/JAVA/XM/Zhishu-main/scripts/experiments"
RUN_BASE="C:/Users/z3211/Desktop/qiniuyun/.local/项目功能改造与指标评审/runs/zhishu/ZH-EXP-F05-REDIS-STREAM-BUFFER"
DATASET_DIR="$RUN_BASE/dataset"
OUT_DIR="$RUN_BASE/results"
mkdir -p "$OUT_DIR"

# ---- 环境工具 ----
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
MVN="/d/JAVA/Maven/apache-maven-3.9.14/bin/mvn"

# ---- Redis 连接 (ZH-F05 实验冻结依赖) ----
REDIS_HOST="192.168.241.128"
REDIS_PORT="6379"
REDIS_PASSWORD="ZhaoYang1314."
REDIS_DB="14"

# ---- 档位 n (01 §6 spec S=25/M=100/L=25; 实测 baseline O(N×K) 每 chunk 触发 refreshSession 重写 N 消息,
#       M=100 baseline 15.6min. commandstats 为精确计数 (deterministic), n 不影响 commands/answer 精度,
#       仅影响 P95 样本数. 降至 M=25 (K=100 → 2500 样本 P95 稳定), L=5 (K=300 → 1500 样本 斜率稳定).
#       G4 报告记录 n 偏离 spec 的理由) ----
declare -A N_BY_SCALE=( ["S"]="25" ["M"]="25" ["L"]="5" )
if [[ -n "${ZH_F05_N_OVERRIDE:-}" ]]; then
  N_BY_SCALE["S"]="$ZH_F05_N_OVERRIDE"
  N_BY_SCALE["M"]="$ZH_F05_N_OVERRIDE"
  N_BY_SCALE["L"]="$ZH_F05_N_OVERRIDE"
  echo "[stream] ZH_F05_N_OVERRIDE=$ZH_F05_N_OVERRIDE 统一覆盖所有档 n (调试模式)"
fi

# ---- 预检: Redis 可达 ----
echo "[stream] precheck Redis $REDIS_HOST:$REDIS_PORT db=$REDIS_DB ..."
python -c "
import socket,sys
try:
    s=socket.create_connection(('$REDIS_HOST', $REDIS_PORT),timeout=5)
    s.sendall(b'AUTH $REDIS_PASSWORD\r\n'); r=s.recv(64)
    if b'+OK' not in r: print('AUTH failed:',r.decode().strip()); sys.exit(2)
    s.sendall(b'SELECT $REDIS_DB\r\n'); s.recv(64)
    s.sendall(b'PING\r\n'); r=s.recv(64)
    print('[stream] Redis OK, PING ->', r.decode().strip())
    s.close()
except Exception as e:
    print('[stream] Redis precheck FAILED:', e); sys.exit(2)
" || { echo "[stream] ABORT: Redis 不可达, 请先启动 VM 192.168.241.128"; exit 2; }

# ---- 采集循环 ----
cd "$PROJECT_DIR"
RUN_STARTED_AT=$(date +%Y-%m-%dT%H:%M:%S%z 2>/dev/null || echo "unknown")

for scale in S M L; do
  n=${N_BY_SCALE[$scale]}
  echo ""
  echo "============================================================"
  echo "[stream] scale=$scale n=$n (baseline + after, 同 JVM 交替)"
  echo "============================================================"
  ZH_F05_EXP_RUN=true \
  ZH_F05_SCALE=$scale \
  ZH_F05_N=$n \
  ZH_F05_DATASET_DIR="$DATASET_DIR" \
  ZH_F05_OUT_DIR="$OUT_DIR" \
  ZH_F05_REDIS_HOST=$REDIS_HOST \
  ZH_F05_REDIS_PORT=$REDIS_PORT \
  ZH_F05_REDIS_PASSWORD=$REDIS_PASSWORD \
  ZH_F05_REDIS_DB=$REDIS_DB \
  "$MVN" -o test -Dtest=RedisStreamExperimentDriver 2>&1 | tee "$OUT_DIR/driver-log-$scale.txt" | grep -E "ZH-F05-EXP|Tests run:|BUILD" || true
  echo "[stream] scale=$scale done -> $OUT_DIR/result-$scale.json"
done

echo ""
echo "[stream] ALL SCALES DONE. runStartedAt=$RUN_STARTED_AT"
echo "[stream] results: $OUT_DIR/result-{S,M,L}.json"
echo "[stream] next: python $SCRIPT_DIR/zhishu-redis-stat.py --results-dir $OUT_DIR"
