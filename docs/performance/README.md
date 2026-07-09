# 性能证据文档入口

本目录把智枢的性能优化结果拆成可阅读、可复现、可追溯的公开证据体系。根目录 `README.md` 只展示结论和入口；`PERFORMANCE.md` 汇总实验结果；本目录保存每个优化点的细节、证据索引、复现说明和公开路径规则。

## 快速入口

| 你想看什么 | 入口 |
|---|---|
| 三项优化的总览结论 | [PERFORMANCE.md](../../PERFORMANCE.md) |
| 从零复现实验 | [REPRODUCE.md](../../REPRODUCE.md) |
| claim 到证据的映射 | [evidence-index.md](evidence-index.md) |
| 公开结论边界 | [claims.md](claims.md) |
| 本地路径与公开路径规则 | [public-path-map.md](public-path-map.md) |
| 重复运行稳定性 | [rerun-stability.md](rerun-stability.md) |

## 单项优化

| 优化项 | 详细文档 | 核心结论 |
|---|---|---|
| ZH-F07 后台用户列表查询下推 | [zh-f07-admin-query.md](zh-f07-admin-query.md) | 100k 用户 P95 延迟降 53.59%，扫描行数降 99.96% |
| ZH-F05 Redis 流式会话写放大优化 | [zh-f05-redis-stream.md](zh-f05-redis-stream.md) | M 档 commands/answer 降 93.92%，bytes/answer 降 96.97% |
| ZH-F02 知识库分片上传有界并发 | [zh-f02-upload.md](zh-f02-upload.md) | M 档端到端 median 降 54.81%，吞吐升 121.08% |

## 证据链原则

每个公开数字都遵循同一条链路：

```text
源码改造 -> 实验脚本 -> 原始结果 -> 统计脚本 -> 独立校验 -> 文档 claim
```

公开文档只使用仓库相对路径和脱敏摘要，不暴露本机绝对路径、真实凭据、用户数据或无法公开的大型 raw artifact。
