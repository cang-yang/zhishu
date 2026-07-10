# 智枢

智枢是一个面向企业和个人知识管理场景的知识库与 AI 问答系统。项目围绕“资料上传、知识沉淀、智能检索、AI 会话、用户与组织管理”构建完整业务链路，支持用户将文档资料沉淀为可检索、可追问、可管理的知识资产。

项目采用 Spring Boot 3.4 + Vue 3 实现，后端负责知识库、会话、用户、组织、上传和后台管理等核心能力，前端提供知识库操作、AI 对话和管理界面。Redis、MySQL、MinIO、Kafka、Elasticsearch 等组件用于支撑会话状态、结构化数据、对象存储、异步任务和检索能力。

## 核心能力

| 模块 | 说明 |
|---|---|
| 知识库管理 | 支持知识库创建、资料上传、文档分片和后续检索问答链路 |
| AI 会话 | 支持围绕知识内容进行多轮对话，并维护用户会话状态 |
| 文件上传 | 支持大文件分片上传、断点恢复、合并校验和对象存储落盘 |
| 用户与组织 | 提供用户、组织、权限和后台管理相关能力 |
| 后台管理 | 支持管理员侧用户列表、查询筛选、分页和状态管理 |
| 工程证据 | 为关键性能优化保留可复现脚本、统计结果、校验记录和公开结论边界 |

## 技术栈

| 层级 | 技术 |
|---|---|
| 后端 | Spring Boot 3.4, Spring Data JPA, Spring Security, Redis, MySQL 8.0, Kafka, Elasticsearch |
| 前端 | Vue 3, TypeScript, Vite |
| 存储与中间件 | MySQL, Redis, MinIO, Kafka, Elasticsearch |
| 构建与验证 | Maven, pnpm, Python, PowerShell, Bash |

## 工程亮点

本项目在核心业务链路之外，补充了三项可复现的工程优化证据。这些内容不是项目的全部，而是用于说明项目在性能、稳定性和可验证性上的进一步打磨。

![智枢性能优化总览](docs/performance/assets/overview.png)

| 优化项 | 核心结果 | 详细说明 | 复现入口 |
|---|---|---|---|
| 后台用户列表查询下推 | 100k 用户 P95 延迟降 53.59%，扫描行数降 99.96% | [ZH-F07 证据页](docs/performance/zh-f07-admin-query.md) | [复现](REPRODUCE.md#4-zh-f07-后台用户列表查询下推) |
| Redis 流式写放大优化 | M 档 commands/answer 降 93.92%，bytes/answer 降 96.97% | [ZH-F05 证据页](docs/performance/zh-f05-redis-stream.md) | [复现](REPRODUCE.md#5-zh-f05-redis-流式会话写放大优化) |
| 分片上传有界并发 | M 档端到端 median 降 54.81%，吞吐升 121.08% | [ZH-F02 证据页](docs/performance/zh-f02-upload.md) | [复现](REPRODUCE.md#6-zh-f02-知识库分片上传有界并发) |

## 文档入口

| 文档 | 说明 |
|---|---|
| [PERFORMANCE.md](PERFORMANCE.md) | 性能实验总报告，汇总三项优化的实验设计、指标、MME 与复审结论 |
| [REPRODUCE.md](REPRODUCE.md) | 统一复现入口，包含依赖、命令、数据集、预期输出和常见问题 |
| [docs/performance/README.md](docs/performance/README.md) | 性能证据文档索引 |
| [docs/performance/evidence-index.md](docs/performance/evidence-index.md) | claim 到脚本、统计结果、校验结果和 sha256 的映射 |
| [docs/performance/claims.md](docs/performance/claims.md) | 公开结论边界，说明哪些可以写进 README/简历，哪些不能越界 |
| [docs/performance/public-path-map.md](docs/performance/public-path-map.md) | 公开路径规则，避免暴露本机绝对路径 |

## 验证方式

运行核心回归测试：

```powershell
mvn "-Dtest=UserServiceTest,UserListEquivalenceTest,UserRepositoryListQueryTest,RedisChatSessionStoreTest,UploadControllerTest,UploadServicePerformanceTest" test
```

重新生成公开图表：

```bash
python scripts/experiments/render_charts.py
```

完整性能实验复现请从 [REPRODUCE.md](REPRODUCE.md) 开始。

## 公开结论边界

README 只展示项目核心能力和关键工程亮点。所有性能数字均绑定固定数据集、运行环境、脚本版本和独立校验记录；完整实验链路、证据 hash 和公开边界见 [PERFORMANCE.md](PERFORMANCE.md)、[docs/performance/evidence-index.md](docs/performance/evidence-index.md) 与 [docs/performance/claims.md](docs/performance/claims.md)。
