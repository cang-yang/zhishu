# Zhishu (知数)

基于 Spring Boot 3.4.2 (Java 17) + Spring Data JPA + Redis + MySQL 8.0 的知识服务后端，前端 Vue 3 + TypeScript。

## 技术栈

- 后端: Spring Boot 3.4.2, Spring Data JPA, Spring Security, Redis, MySQL 8.0, Kafka, Elasticsearch
- 前端: Vue 3 + TypeScript + Vite
- 构建: Maven (后端) / pnpm (前端)

## 性能优化

### 后台用户列表查询 (ADMIN-USERS-LIST)

将 `findAll` 内存过滤/排序/分页/组织标签 N+1 查询下推到 MySQL:

- 过滤: `JpaSpecificationExecutor` (username LIKE + `FIND_IN_SET` + role)
- 排序: `createdAt DESC, id ASC` 下推 (索引 `idx_users_created_at_id`)
- 分页: `Pageable` 下推 (`LIMIT/OFFSET`)
- 投影: 构造表达式 DTO (6 字段, 不含 password)
- 组织标签: `findByTagIdIn` 批量查 (消除 N+1)
- count: 独立 count query 下推

#### 基准测试 (100k 用户合成数据集, REAL MySQL 8.0, n=200/arm, 同 JVM 交替)

| 指标 | baseline (findAll) | after (下推) | 降幅 |
|---|---|---|---|
| P95 延迟 | 892ms | 414ms | 53.59% |
| SQL 扫描行数 | 98950 | 40 | 99.96% |
| 峰值堆 | 1.36GB | 150MB | 88.96% |
| 响应等价 | — | — | 100% (canonical diff, password 不泄漏) |

三档验证 (100k/10k/1k): 规模越大下推收益越大 (100k P95 降 53.59%, 10k 降 9.71%, 1k 降 5.49%)。

独立复审: 代码审查 G3 R4 96 PASS, 结果审查 G5 R1 97 PASS, 0 阻塞。证据可单文件追溯 (`claim-manifest.json`, 4 claims → 证据路径 + sha256)。

#### 复现

```bash
# 1. 准备数据集 (zhishu_exp_f07 schema)
mysql -u root -p zhishu_exp_f07 < scripts/experiments/zhishu-admin-seed-100k.sql

# 2. 启动后端 (experiment profile, 端口 18081)
mvn -o spring-boot:run -Dspring-boot.run.profiles=experiment

# 3. 采集 (n=200/arm, 三档 100k/10k/1k)
pwsh scripts/experiments/zhishu-admin-query.ps1
pwsh scripts/experiments/zhishu-admin-stat.ps1
pwsh scripts/experiments/zhishu-admin-verify.ps1
```

前置依赖: JDK 17 / Maven 3.9 / MySQL 8.0 / Redis / PowerShell 7。

### Redis 流式会话写放大优化 (ZH-F05-REDIS-STREAM-BUFFER)

将 AI 流式回复每 chunk 的 `saveMessage + refreshSessionAfterMessageMutation`（O(N) 重写 session 全部消息）改为 `APPEND` buffer 追加（O(1)），R4 故障自动降级回 baseline 路径：

- baseline 每 chunk: `saveMessage`(1 SET) + `refreshSession`(for-loop 重写 N 消息) = O(N) cmds/chunk
- after 每 chunk: `requireOwnedMessage`(2 GET) + `APPEND` + `EXPIRE` = O(1) cmds/chunk
- buffer key: `chat:session:{userId}:msg:{messageId}:buffer`, complete 时合并到 message 并 `DEL`
- 故障恢复: APPEND 连续失败 3 次（100ms 退避）降级 `appendBaselinePath`（读 buffer + 合并 chunk + saveMessage），不丢 chunk
- 配置门控: `isBaselineArm()` 双条件（`experimentFeature=ZH-F05` 且 `experimentArm=baseline`），生产默认 after

#### 基准测试 (REAL Redis, commandstats 精确计数, n=25/arm, 同 JVM 交替)

| 指标 | baseline (O(N) 重写) | after (APPEND buffer) | 降幅 |
|---|---|---|---|
| M 档 commands/answer | 11,070 | 673 | 93.92% |
| M 档 bytes/answer | 4,756,894 | 144,111 | 96.97% |
| M 档 P95 延迟 | 139.6ms | 5.8ms | 24x (ratio 0.0413) |
| L 档 P95 斜率 (ns/N) | 2,178,621 | 3,449 | 99.84% (O(N)→O(1)) |
| 最终消息等价 | — | — | 100% (canonical diff, 不丢 chunk) |

三档验证 (S/M/L, history=5/50/200, chunk=20/100/300): 规模越大写放大消除收益越大 (S 降 68.38%, M 降 93.92%, L 降 98.20%)——符合 O(N)→O(1) 特征。

独立复审: 代码审查 G3 R1 96 PASS, 结果审查 G5 R1 97 PASS, 0 阻塞。证据可单文件追溯 (`claim-manifest.json`, 4 claims → 证据路径 + sha256)。完整实验链路见 [PERFORMANCE.md](PERFORMANCE.md)。

#### 复现

```bash
# 1. 启动 Redis (db=14 隔离)
redis-cli -h <redis-host> -a <password> SELECT 14

# 2. seed dataset (deterministic, seed=42, synthetic 公开无敏感)
python scripts/experiments/zhishu-redis-seed.py

# 3. 采集 (三档 S/M/L, baseline + after 同 JVM 交替)
bash scripts/experiments/zhishu-redis-stream.sh

# 4. 统计 + 独立校验
python scripts/experiments/zhishu-redis-stat.py
python scripts/experiments/zhishu-redis-verify.py
```

前置依赖: JDK 17+ / Maven 3.9 / Redis 7。被测代码为真实 `RedisChatSessionStore` (生产 class, 非 mock), plain JUnit + Lettuce 直连隔离 ES/Kafka/MinIO/MySQL。

### 知识库分片上传有界并发 (ZH-F02-CONCURRENT-UPLOAD)

将知识库文件分片上传的前端串行 `for + await uploadChunk` 改为有界并发 worker pool（concurrency=4，cursor 派发 + fail-fast + 分片并集 merge）。后端零改（已幂等 Redis bitmap SETBIT / MinIO 覆盖 / DB upsert，已隔离 bitmap userId-scoped + merge 所有权闸）：

- baseline: `startUpload` 内 `for (i) await uploadChunk(i)` 串行，N 分片 N 个 RTT 串行累加
- after: `runUploadPool(indices, 4, worker)` cursor-based 派发，最多 4 分片在途，首错 fail-fast 停止派发；`uploadedChunks = Array.from(new Set([...prev, ...data.uploaded]))` 并集单调（响应乱序到达不丢片）
- 恢复友好: fail-fast 后已上传分片记入 Redis bitmap，resume 从 `/status` 续传未完成分片

#### 基准测试 (REAL 后端 HTTP, MinIO/Redis/MySQL/Kafka, n=20/arm 主档 M, 同环境交替)

| 指标 | baseline (串行 c=1) | after (并发 c=4) | 变化 |
|---|---|---|---|
| M 档 (64MiB/13chunks) median 端到端 | 1855.1ms | 838.3ms | 降 54.81% |
| M 档 median 吞吐 | 34.54 MiB/s | 76.36 MiB/s | 升 121.08% |
| M 档 P90 比 (after/baseline) | — | 0.5276 | ≤1.10 (不恶化) |
| L 档 (256MiB/52chunks) median | 7078.3ms | 2802.3ms | 降 60.4% |
| 恢复 (partial@50%→resume) | — | 3/3 完整恢复 13/13 | PASS |
| 合并对象 SHA-256 | — | == 源文件 | 100% (不丢片) |
| 多用户同 MD5 隔离矩阵 | — | ISO-1/2/4 阻断 | PASS |

三档验证 (S 16MiB/M 64MiB/L 256MiB): 分片越多并发收益越大 (M 降 54.81%, L 降 60.4%)。反作弊: baseline 严格串行 c=1 (23/23)、after 并发真生效 (chunk P95 > P50×1.05)、fileMd5==manifest 交叉核对、吞吐内部一致。后端零改 → 隔离属性不可能回归。

独立复审: 代码审查 G3 96 PASS, 结果审查 G5 95 PASS, 0 阻塞。证据可单文件追溯 (`claim-manifest.json`, 4 claims → 证据路径 + sha256)。完整实验链路见 [PERFORMANCE.md](PERFORMANCE.md)。

#### 复现

```bash
# 1. 启动依赖 (MySQL zhishu_exp_f02 / Redis db=15 / MinIO bucket zhishu-exp / Kafka)
# 2. 启动后端 (experiment-f02 profile, 端口 18083)
cd .runtime && java -jar ../target/zhishu-0.0.1-SNAPSHOT.jar --spring.profiles.active=experiment-f02

# 3. seed dataset (deterministic, seed=42, synthetic .txt 公开无敏感)
python scripts/experiments/zhishu-upload-seed.py

# 4. 采集 (三档 S/M/L, baseline c=1 + after c=4 交替 + 恢复 + 隔离矩阵)
bash scripts/experiments/zhishu-upload.sh

# 5. 统计 + 独立校验
python scripts/experiments/zhishu-upload-stat.py --results-jsonl <runRoot>/raw-results.jsonl
python scripts/experiments/zhishu-upload-verify.py --results-jsonl <runRoot>/raw-results.jsonl
```

前置依赖: JDK 17+ / Maven 3.9 / MySQL 8.0 / Redis 7 / MinIO / Kafka。被测为真实前端并发池 + 真实后端 4 端点（/chunk /status /merge /login），非 mock。
