# 公开结论边界

本页记录智枢性能优化可公开表达的结论，以及不能越界的说法。

## 可公开结论

| claimId | 可公开表达 | 证据入口 |
|---|---|---|
| ZH-F07-C1 | 后台用户列表查询通过数据库过滤、排序、分页和投影下推，在 100k 用户合成数据集上 P95 延迟下降 53.59% | [ZH-F07](zh-f07-admin-query.md) |
| ZH-F07-C2 | SQL 扫描行数从约 98950 降到 40，峰值堆从 1.36GB 降到 150MB | [ZH-F07](zh-f07-admin-query.md) |
| ZH-F05-C1 | Redis 流式会话写入由 O(N) session 重写改为 O(1) APPEND buffer，M 档 commands/answer 下降 93.92% | [ZH-F05](zh-f05-redis-stream.md) |
| ZH-F05-C2 | M 档 bytes/answer 下降 96.97%，最终消息 canonical diff 100% | [ZH-F05](zh-f05-redis-stream.md) |
| ZH-F02-C1 | 分片上传由串行改为有界并发 worker pool，M 档 median 端到端耗时下降 54.81% | [ZH-F02](zh-f02-upload.md) |
| ZH-F02-C2 | M 档 median 吞吐提升 121.08%，恢复、合并 SHA-256、用户隔离护栏通过 | [ZH-F02](zh-f02-upload.md) |

## 禁止越界结论

| 禁止说法 | 原因 |
|---|---|
| “所有场景都提升 50% 以上” | 当前结论绑定固定数据集、依赖和实验 profile，不代表所有生产环境 |
| “Redis 性能整体提升 93.92%” | 93.92% 指 ZH-F05 M 档 commands/answer，不是 Redis 全局性能 |
| “上传速度永久提升 121.08%” | 121.08% 指 M 档固定环境 median 吞吐，不代表所有网络和文件类型 |
| “查询优化无需索引/无需数据库调优” | ZH-F07 依赖数据库下推、排序和索引设计 |
| “截图即可证明实验结果” | 公开 claim 必须能追溯到脚本、raw/stat/verify 和 sha256 |

## 简历推荐写法

```text
围绕知识服务后端完成 3 项可量化性能优化：用户列表查询下推使 100k 数据集 P95 降 53.59%，Redis 流式写入改为 APPEND buffer 使 commands/answer 降 93.92%，分片上传引入有界并发使 M 档端到端 median 降 54.81%；配套实验脚本、统计校验、证据索引与复现文档。
```
