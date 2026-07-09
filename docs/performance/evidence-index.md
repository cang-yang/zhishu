# 性能证据索引

本页把公开 claim 映射到脚本、统计结果、校验结果和 sha256。更详细的实验叙述见 [PERFORMANCE.md](../../PERFORMANCE.md)。

## ZH-F07 后台用户列表查询下推

| 证据 | 路径或说明 |
|---|---|
| 详细文档 | [zh-f07-admin-query.md](zh-f07-admin-query.md) |
| 复现入口 | [REPRODUCE.md#4-zh-f07-后台用户列表查询下推](../../REPRODUCE.md#4-zh-f07-后台用户列表查询下推) |
| seed | `scripts/experiments/zhishu-admin-seed-100k.sql` |
| 采集脚本 | `scripts/experiments/zhishu-admin-query.ps1` |
| 统计脚本 | `scripts/experiments/zhishu-admin-stat.ps1` |
| 校验脚本 | `scripts/experiments/zhishu-admin-verify.ps1` |
| stat-summary sha256 | `1141D4DBD6AB355A48B87FD52E1CA80D35C4324A94D08A75953C0A377EB2847F` |
| verification-report sha256 | `16B4DEE1C2B1B3424C872A7BAE667C03BAE4CB2BD4DEB018D53D707FA72E9D67` |

## ZH-F05 Redis 流式会话写放大优化

| 证据 | 路径或说明 |
|---|---|
| 详细文档 | [zh-f05-redis-stream.md](zh-f05-redis-stream.md) |
| 复现入口 | [REPRODUCE.md#5-zh-f05-redis-流式会话写放大优化](../../REPRODUCE.md#5-zh-f05-redis-流式会话写放大优化) |
| seed | `scripts/experiments/zhishu-redis-seed.py` |
| 采集脚本 | `scripts/experiments/zhishu-redis-stream.sh` |
| 统计脚本 | `scripts/experiments/zhishu-redis-stat.py` |
| 校验脚本 | `scripts/experiments/zhishu-redis-verify.py` |
| result-M sha256 | `12692790EEA2C85621F2254F90C254C577F57008EA4726F3CA9C77010468EA33` |
| stat-summary sha256 | `A6C8DC351D02CE180D5D61AFB3CBAF0B9E5C1777DBB09E749758205E949013D4` |
| verify-verdict sha256 | `67E3E01FF73113FF6762D9BD47D11AAB0BFBE13A61B76D33BAAE11E2E8F86243` |

## ZH-F02 知识库分片上传有界并发

| 证据 | 路径或说明 |
|---|---|
| 详细文档 | [zh-f02-upload.md](zh-f02-upload.md) |
| 复现入口 | [REPRODUCE.md#6-zh-f02-知识库分片上传有界并发](../../REPRODUCE.md#6-zh-f02-知识库分片上传有界并发) |
| seed | `scripts/experiments/zhishu-upload-seed.py` |
| 采集脚本 | `scripts/experiments/zhishu-upload.sh` |
| 统计脚本 | `scripts/experiments/zhishu-upload-stat.py` |
| 校验脚本 | `scripts/experiments/zhishu-upload-verify.py` |
| raw-results sha256 | `287B65FC2741F85619E1DB27A2F165BC143F8118F077CC56340A880B24069F03` |
| stat-summary sha256 | `7D9D72B639C0FE71CCC46C80B13F2D9CD8D00D6FB89DFA5765F655B176BCC2EF` |
| verify-verdict sha256 | `84B620B59C85E2CC0F90B450F05427A3B1209A8B96E8F6300DA75B07C070809F` |

## 图表证据

| 图表 | 生成方式 |
|---|---|
| [overview.svg](assets/overview.svg) | `python scripts/experiments/render_charts.py` |
| [zh-f07-admin-query.svg](assets/zh-f07-admin-query.svg) | `python scripts/experiments/render_charts.py` |
| [zh-f05-redis-stream.svg](assets/zh-f05-redis-stream.svg) | `python scripts/experiments/render_charts.py` |
| [zh-f02-upload.svg](assets/zh-f02-upload.svg) | `python scripts/experiments/render_charts.py` |
