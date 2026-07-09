#!/usr/bin/env bash
# -*- coding: utf-8 -*-
# ZH-F02 G4 EXPERIMENT 采集 wrapper (分片上传有界并发)
# experimentId: ZH-EXP-F02-UPLOAD
# baselineId:   ZH-BL-F02-SERIAL-UPLOAD-V1
# runType: REAL
#
# 编排: seed -> login A/B -> 三档交替 baseline/after (cleanup-between-runs) ->
#       恢复 RC-001 (partial -> resume) -> R10 诊断 -> 隔离矩阵 ->
#       stat + verify
#
# 关键: 每 run 间 MUST cleanup (秒传 dedup 命中会污染下一 run 计时 — 同 md5 文件二次上传触发 instantUpload).
#
# 依赖 (用户在 G4 启动): 后端 :18083 (application-experiment-f02.yml), MySQL zhishu_exp_f02,
#   Redis 192.168.241.128 db=15, MinIO zhishu-exp, Kafka :9092, Toxiproxy (弱网诊断, 可选).
#   A/B 测试用户须预创建于 zhishu_exp_f02 (INVITE_ONLY; admin 邀请码流程), 凭据由环境变量传入.
#   Python 依赖: pip install requests redis pymysql minio
#
# 用法:
#   BASE_URL=http://localhost:18083 USER_A=u_a PASS_A=P_a USER_B=u_b PASS_B=P_b bash zhishu-upload.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNS_ROOT="${ZH_F02_RUNS_ROOT:-$HOME/.local/项目功能改造与指标评审/runs/zhishu/ZH-EXP-F02-UPLOAD}"
RUN_ID="$(date +%Y%m%d-%H%M%S 2>/dev/null || echo manual)"
OUT_DIR="$RUNS_ROOT/REAL/$RUN_ID"
DATASET_DIR="$RUNS_ROOT/dataset"
RESULTS="$OUT_DIR/raw-results.jsonl"
LOG="$OUT_DIR/wrapper.log"

BASE_URL="${BASE_URL:-http://localhost:18083}"
USER_A="${USER_A:-u_a}"; PASS_A="${PASS_A:-P_a}"
USER_B="${USER_B:-u_b}"; PASS_B="${PASS_B:-P_b}"
N_MAIN="${N_MAIN:-20}"      # M档主 每方样本 (baseline+after 各 N_MAIN)
N_DIAG="${N_DIAG:-5}"       # S/L诊断 每方样本
N_RECOVERY="${N_RECOVERY:-3}"

# 直连客户端参数 (与 application-experiment-f02.yml 一致)
REDIS_HOST="${REDIS_HOST:-192.168.241.128}"; REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_PASS="${REDIS_PASS:-ZhaoYang1314.}"; REDIS_DB="${REDIS_DB:-15}"
MYSQL_HOST="${MYSQL_HOST:-localhost}"; MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"; MYSQL_PASS="${MYSQL_PASS:-ZhaoYang1314.}"; MYSQL_DB="${MYSQL_DB:-zhishu_exp_f02}"
MINIO_EP="${MINIO_EP:-http://localhost:9000}"; MINIO_AK="${MINIO_AK:-minioadmin}"; MINIO_SK="${MINIO_SK:-minioadmin}"
MINIO_BUCKET="${MINIO_BUCKET:-zhishu-exp}"

PY="${PYTHON:-python3}"

VERIFY_COMMON=(--base-url "$BASE_URL" --user-a "$USER_A" --user-b "$USER_B" \
  --redis-host "$REDIS_HOST" --redis-port "$REDIS_PORT" --redis-password "$REDIS_PASS" --redis-db "$REDIS_DB" \
  --mysql-host "$MYSQL_HOST" --mysql-port "$MYSQL_PORT" --mysql-user "$MYSQL_USER" --mysql-password "$MYSQL_PASS" --mysql-db "$MYSQL_DB" \
  --minio-endpoint "$MINIO_EP" --minio-access-key "$MINIO_AK" --minio-secret-key "$MINIO_SK" --minio-bucket "$MINIO_BUCKET")

mkdir -p "$OUT_DIR"
: > "$RESULTS"
exec > >(tee -a "$LOG") 2>&1

echo "[wrapper] ZH-F02 G4 runId=$RUN_ID out=$OUT_DIR"
echo "[wrapper] base=$BASE_URL  N_MAIN=$N_MAIN N_DIAG=$N_DIAG N_RECOVERY=$N_RECOVERY"

# ---------- 0. seed ----------
if [ ! -f "$DATASET_DIR/dataset-manifest.json" ]; then
  echo "[wrapper] seeding dataset..."
  "$PY" "$SCRIPT_DIR/zhishu-upload-seed.py" --out-dir "$DATASET_DIR"
fi

# 文件 md5 (从 manifest 取, cleanup/harness 共用)
M_MD5=$("$PY" -c "import json;m=json.load(open(r'$DATASET_DIR/dataset-manifest.json',encoding='utf-8'));print(m['scales']['M']['md5'])")
S_MD5=$("$PY" -c "import json;m=json.load(open(r'$DATASET_DIR/dataset-manifest.json',encoding='utf-8'));print(m['scales']['S']['md5'])")
L_MD5=$("$PY" -c "import json;m=json.load(open(r'$DATASET_DIR/dataset-manifest.json',encoding='utf-8'));print(m['scales']['L']['md5'])")
M_FILE="$DATASET_DIR/$( "$PY" -c "import json;print(json.load(open(r'$DATASET_DIR/dataset-manifest.json',encoding='utf-8'))['scales']['M']['file'])" )"
S_FILE="$DATASET_DIR/$( "$PY" -c "import json;print(json.load(open(r'$DATASET_DIR/dataset-manifest.json',encoding='utf-8'))['scales']['S']['file'])" )"
L_FILE="$DATASET_DIR/$( "$PY" -c "import json;print(json.load(open(r'$DATASET_DIR/dataset-manifest.json',encoding='utf-8'))['scales']['L']['file'])" )"

cleanup() {
  local md5="$1"
  "$PY" "$SCRIPT_DIR/zhishu-upload-verify.py" --cleanup-md5 "$md5" \
    --redis-host "$REDIS_HOST" --redis-port "$REDIS_PORT" --redis-password "$REDIS_PASS" --redis-db "$REDIS_DB" \
    --mysql-host "$MYSQL_HOST" --mysql-port "$MYSQL_PORT" --mysql-user "$MYSQL_USER" --mysql-password "$MYSQL_PASS" --mysql-db "$MYSQL_DB" \
    --minio-endpoint "$MINIO_EP" --minio-access-key "$MINIO_AK" --minio-secret-key "$MINIO_SK" --minio-bucket "$MINIO_BUCKET" || true
}

# ---------- 1. M档主档 (交替 baseline/after, concurrency=4) ----------
echo "[wrapper] === M档主档 (n=$N_MAIN 每方, 交替) ==="
for i in $(seq 1 "$N_MAIN"); do
  cleanup "$M_MD5"
  "$PY" "$SCRIPT_DIR/zhishu-upload-harness.py" --variant baseline --file "$M_FILE" --scale M \
    --base-url "$BASE_URL" --username "$USER_A" --password "$PASS_A" \
    --concurrency 1 --network local --results-jsonl "$RESULTS" --run-id "M-B-$i"
  cleanup "$M_MD5"
  "$PY" "$SCRIPT_DIR/zhishu-upload-harness.py" --variant after --file "$M_FILE" --scale M \
    --base-url "$BASE_URL" --username "$USER_A" --password "$PASS_A" \
    --concurrency 4 --network local --results-jsonl "$RESULTS" --run-id "M-A-$i"
done

# ---------- 2. 诊断 S/L档 (baseline vs after concurrency=4) ----------
for SCALE in S L; do
  case "$SCALE" in
    S) F="$S_FILE"; MD5="$S_MD5";;
    L) F="$L_FILE"; MD5="$L_MD5";;
  esac
  echo "[wrapper] === ${SCALE}档诊断 (n=$N_DIAG 每方) ==="
  for i in $(seq 1 "$N_DIAG"); do
    cleanup "$MD5"
    "$PY" "$SCRIPT_DIR/zhishu-upload-harness.py" --variant baseline --file "$F" --scale "$SCALE" \
      --base-url "$BASE_URL" --username "$USER_A" --password "$PASS_A" \
      --concurrency 1 --network local --results-jsonl "$RESULTS" --run-id "${SCALE}-B-$i"
    cleanup "$MD5"
    "$PY" "$SCRIPT_DIR/zhishu-upload-harness.py" --variant after --file "$F" --scale "$SCALE" \
      --base-url "$BASE_URL" --username "$USER_A" --password "$PASS_A" \
      --concurrency 4 --network local --results-jsonl "$RESULTS" --run-id "${SCALE}-A-$i"
  done
done

# ---------- 3. R10 响应乱序确定性诊断 (after arm, 1 run) ----------
echo "[wrapper] === R10 诊断 (after concurrency=4, chunkIndex=0 响应延迟最后到达) ==="
cleanup "$M_MD5"
"$PY" "$SCRIPT_DIR/zhishu-upload-harness.py" --variant after --file "$M_FILE" --scale M \
  --base-url "$BASE_URL" --username "$USER_A" --password "$PASS_A" \
  --concurrency 4 --network local --results-jsonl "$RESULTS" --run-id "M-R10" --r10-diag

# ---------- 4. 恢复 RC-001 (partial @50% -> resume -> merge, n=$N_RECOVERY) ----------
echo "[wrapper] === 恢复 RC-001 (上传至50%中断 -> 恢复 -> merge, n=$N_RECOVERY) ==="
M_TOTAL=$("$PY" -c "import json;print(json.load(open(r'$DATASET_DIR/dataset-manifest.json',encoding='utf-8'))['scales']['M']['totalChunks'])")
HALF=$(( M_TOTAL / 2 ))
for i in $(seq 1 "$N_RECOVERY"); do
  cleanup "$M_MD5"
  # 上传至 ~50% 中断 (不 merge)
  "$PY" "$SCRIPT_DIR/zhishu-upload-harness.py" --variant baseline --file "$M_FILE" --scale M \
    --base-url "$BASE_URL" --username "$USER_A" --password "$PASS_A" \
    --concurrency 1 --network local --results-jsonl "$RESULTS" --run-id "M-RC-interrupt-$i" --stop-after-chunk "$HALF"
  # 恢复: GET /status 取已传 -> pool 上传缺失 -> merge
  "$PY" "$SCRIPT_DIR/zhishu-upload-harness.py" --variant after --file "$M_FILE" --scale M \
    --base-url "$BASE_URL" --username "$USER_A" --password "$PASS_A" \
    --concurrency 4 --network local --results-jsonl "$RESULTS" --run-id "M-RC-resume-$i" --resume
done

# ---------- 5. 隔离矩阵 (verify 模式, A/B 两用户) ----------
echo "[wrapper] === 隔离矩阵 (verify --base-url --token-a/-b) ==="
# 预留 case-3 (并发同 md5 merge): A/B 各上传同 md5 后, A 先 merge, B 后 merge -> 500
# case-3 md5 由 wrapper 临时用一个 S 文件准备 (A 上传后 merge, B 上传同 md5 不 merge)
cleanup "$S_MD5"
"$PY" "$SCRIPT_DIR/zhishu-upload-harness.py" --variant after --file "$S_FILE" --scale S \
  --base-url "$BASE_URL" --username "$USER_A" --password "$PASS_A" \
  --concurrency 4 --network local --results-jsonl "$RESULTS" --run-id "S-ISO3-A"
# B 上传同 md5 (全分片, 抑制 merge 留 ChunkInfo+bitmap) — 供 verify ISO-3 (B 后 merge -> 500)
S_TOTAL=$("$PY" -c "import json;print(json.load(open(r'$DATASET_DIR/dataset-manifest.json',encoding='utf-8'))['scales']['S']['totalChunks'])")
"$PY" "$SCRIPT_DIR/zhishu-upload-harness.py" --variant after --file "$S_FILE" --scale S \
  --base-url "$BASE_URL" --username "$USER_B" --password "$PASS_B" \
  --concurrency 4 --network local --results-jsonl "$RESULTS" --run-id "S-ISO3-B" --stop-after-chunk $(( S_TOTAL - 1 )) || true

# ---------- 6. stat + verify ----------
echo "[wrapper] === stat ==="
"$PY" "$SCRIPT_DIR/zhishu-upload-stat.py" --results-jsonl "$RESULTS" --out-dir "$OUT_DIR"

echo "[wrapper] === verify (护栏 04/05/06 + 反作弊 + MME 独立重算) ==="
TOKEN_A=$("$PY" -c "import requests,sys;r=requests.post('$BASE_URL/api/v1/users/login',json={'username':'$USER_A','password':'$PASS_A'},timeout=30);print(r.json()['data']['token'])")
TOKEN_B=$("$PY" -c "import requests,sys;r=requests.post('$BASE_URL/api/v1/users/login',json={'username':'$USER_B','password':'$PASS_B'},timeout=30);print(r.json()['data']['token'])")
"$PY" "$SCRIPT_DIR/zhishu-upload-verify.py" --results-jsonl "$RESULTS" --out-dir "$OUT_DIR" \
  --token-a "$TOKEN_A" --token-b "$TOKEN_B" \
  --iso-case3-md5 "$S_MD5" --iso-case4-file "$S_FILE" \
  --dataset-dir "$DATASET_DIR" \
  "${VERIFY_COMMON[@]}"

echo "[wrapper] DONE. out=$OUT_DIR"
echo "[wrapper] artifacts: raw-results.jsonl stat-summary.json stat-report.md verify-verdict.json verify-report.md wrapper.log"
