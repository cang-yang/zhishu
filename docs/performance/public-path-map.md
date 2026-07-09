# 公开路径与本地证据映射

公开仓库文档必须使用相对路径，不直接暴露开发机绝对路径。内部原始证据可以保留在本机或 `.local` 目录，但 README、PERFORMANCE 和 docs/performance 中只引用公开入口或脱敏摘要。

## 路径规则

| 类型 | 允许公开 | 不允许公开 |
|---|---|---|
| 文档链接 | `docs/performance/zh-f02-upload.md` | `<local-project-root>/...` |
| 图表链接 | `docs/performance/assets/overview.svg` | `<local-user-home>/.../overview.png` |
| 脚本链接 | `scripts/experiments/zhishu-upload-stat.py` | 本机临时脚本绝对路径 |
| 原始运行目录 | 仓库相对脱敏摘要或 `raw archived locally` | `<local-run-dir>/zh-f02-runs/...` |

## 当前公开映射

| 原始或内部位置 | 公开入口 | 说明 |
|---|---|---|
| 阶段三本地审查记录 `phase3/zhishu/ZH-F07/*` | [zh-f07-admin-query.md](zh-f07-admin-query.md) | 公开文档保留关键指标、sha256 和复现命令 |
| 阶段三本地审查记录 `phase3/zhishu/ZH-F05/*` | [zh-f05-redis-stream.md](zh-f05-redis-stream.md) | 公开文档保留 Redis commandstats、MME 和反作弊摘要 |
| `<local-run-dir>/zh-f02-runs/...` | [zh-f02-upload.md](zh-f02-upload.md) | 公开文档不暴露个人路径，只保留 raw-results/stat/verify 的 sha256 与复现入口 |
| 自动生成图表 | [assets](assets/) | 由 `scripts/experiments/render_charts.py` 生成 |

## 后续如果公开 raw evidence

如果要把 raw evidence 也提交到仓库，建议使用下面结构：

```text
docs/evidence/zhishu/
  ZH-F07/
    stat-summary.sanitized.json
    verify-report.sanitized.md
  ZH-F05/
    stat-summary.sanitized.json
    verify-verdict.sanitized.json
  ZH-F02/
    stat-summary.sanitized.json
    verify-verdict.sanitized.json
```

公开版本只保留聚合指标、sha256、运行参数和脱敏日志，不提交真实密钥、用户数据、本机路径或大体积临时对象。
