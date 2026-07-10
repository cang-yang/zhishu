# 智枢性能实验复现指南

本文是智枢性能优化实验的统一复现入口。详细结果见 [PERFORMANCE.md](PERFORMANCE.md)，证据索引见 [docs/performance/evidence-index.md](docs/performance/evidence-index.md)。

## 1. 环境要求

| 依赖 | 建议版本 | 用途 |
|---|---|---|
| JDK | 17+ | 后端构建与实验运行 |
| Maven | 3.9+ | 后端构建与测试 |
| Python | 3.10+ | seed、统计、校验、图表脚本 |
| PowerShell | 7+ | ZH-F07 采集脚本 |
| MySQL | 8.0+ | 用户列表与上传实验数据库 |
| Redis | 7+ | 流式会话与上传 bitmap |
| MinIO | 当前项目兼容版本 | ZH-F02 分片对象存储 |
| Kafka | 当前项目兼容版本 | ZH-F02 merge 事务发送依赖 |

## 2. 快速选择

| 优化项 | 需要依赖 | 主入口 |
|---|---|---|
| ZH-F07 后台用户列表查询下推 | MySQL、Redis、PowerShell | `scripts/experiments/zhishu-admin-query.ps1` |
| ZH-F05 Redis 流式会话写放大优化 | Redis、Python、Bash | `scripts/experiments/zhishu-redis-stream.sh` |
| ZH-F02 知识库分片上传有界并发 | MySQL、Redis、MinIO、Kafka、Python、Bash | `scripts/experiments/zhishu-upload.sh` |

## 3. 先跑回归测试

如果只想确认当前 PR 的核心改动未破坏主链路，可以先运行目标测试：

```powershell
mvn "-Dtest=UserServiceTest,UserListEquivalenceTest,UserRepositoryListQueryTest,RedisChatSessionStoreTest,UploadControllerTest,UploadServicePerformanceTest" test
```

最近一次本地干净 worktree 验证结果：

```text
Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 4. ZH-F07 后台用户列表查询下推

### 依赖

```text
JDK 17+
Maven 3.9+
MySQL 8.0+
Redis
PowerShell 7+
```

### 运行

```powershell
mysql -u root -p zhishu_exp_f07 < scripts/experiments/zhishu-admin-seed-100k.sql
mvn spring-boot:run "-Dspring-boot.run.profiles=experiment"
pwsh scripts/experiments/zhishu-admin-query.ps1
pwsh scripts/experiments/zhishu-admin-stat.ps1
pwsh scripts/experiments/zhishu-admin-verify.ps1
```

### 预期输出

```text
100k 用户主档：
- P95 延迟 baseline 约 892ms，after 约 414ms
- SQL 扫描行数 baseline 约 98950，after 约 40
- 响应等价 100%，password 不泄漏
```

## 5. ZH-F05 Redis 流式会话写放大优化

### 依赖

```text
JDK 17+
Maven 3.9+
Redis 7+
Python 3.10+
Bash
```

### 运行

```bash
redis-cli -h <redis-host> -a <password> SELECT 14
python scripts/experiments/zhishu-redis-seed.py
bash scripts/experiments/zhishu-redis-stream.sh
python scripts/experiments/zhishu-redis-stat.py
python scripts/experiments/zhishu-redis-verify.py
```

### 预期输出

```text
M 档主结论：
- commands/answer 从 11070.08 降到 673.08，降 93.92%
- bytes/answer 从 4,756,894 降到 144,111，降 96.97%
- canonical diff 100%，不丢 chunk
```

## 6. ZH-F02 知识库分片上传有界并发

### 依赖

```text
JDK 17+
Maven 3.9+
MySQL 8.0+
Redis 7+
MinIO
Kafka
Python 3.10+
Bash
```

### 运行

```bash
cd .runtime
java -jar ../target/zhishu-0.0.1-SNAPSHOT.jar --spring.profiles.active=experiment-f02

cd ..
python scripts/experiments/zhishu-upload-seed.py
bash scripts/experiments/zhishu-upload.sh
python scripts/experiments/zhishu-upload-stat.py --results-jsonl <runRoot>/raw-results.jsonl
python scripts/experiments/zhishu-upload-verify.py --results-jsonl <runRoot>/raw-results.jsonl
```

### 预期输出

```text
M 档主结论：
- median 端到端从 1855.1ms 降到 838.3ms，降 54.81%
- median 吞吐从 34.54 MiB/s 升到 76.36 MiB/s，升 121.08%
- partial@50% -> resume 3/3 完整恢复
- 合并对象 SHA-256 == 源文件
```

## 7. 生成公开图表

图表脚本只依赖 Python 标准库：

```bash
python scripts/experiments/render_charts.py
```

输出：

```text
docs/performance/assets/overview.svg
docs/performance/assets/zh-f07-admin-query.svg
docs/performance/assets/zh-f05-redis-stream.svg
docs/performance/assets/zh-f02-upload.svg
```

## 8. 常见问题

| 问题 | 处理 |
|---|---|
| Maven 找不到 | 使用 Maven 3.9+，或把 `mvn.cmd` 加入 PATH |
| MySQL schema 不存在 | 先创建 `zhishu_exp_f07` 或 `zhishu_exp_f02` |
| Redis 数据污染 | 每个实验使用独立 DB，ZH-F05 使用 db=14，ZH-F02 使用 db=15 |
| ZH-F02 出现秒传污染 | 每个 run 前清理同 md5 的历史上传记录 |
| Kafka 未启动 | ZH-F02 merge 事务发送依赖 Kafka，需先启动 |
| 图表未刷新 | 重新运行 `python scripts/experiments/render_charts.py` |
