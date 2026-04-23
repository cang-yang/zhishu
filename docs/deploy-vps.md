# zhishu VPS 部署说明

本文档对应当前仓库里的 [`docs/docker-compose.yaml`](docs/docker-compose.yaml) 与 [`deploy-vps.sh`](../deploy-vps.sh) 方案，适合首次上线、小流量、单机 VPS。

## 1. 部署目标

- 代码提交到 GitHub
- 在 VPS 上 `git pull`
- 使用 Docker Compose 一键编排
- 环境变量只保存在 VPS 的根目录 [`.env`](../.env.example)

这种做法是现实且推荐的。

## 2. 服务器前置要求

- Linux VPS
- 已安装 Docker 与 Docker Compose Plugin
- 已安装 Git
- 建议通过 1Panel 或 Nginx 做 80/443 反向代理

## 3. 首次部署

在服务器上执行：

```bash
git clone <你的仓库地址> zhishu
cd zhishu
cp .env.example .env
vim .env
chmod +x deploy-vps.sh
./deploy-vps.sh up
```

## 4. `.env` 放什么

所有敏感信息都放在 VPS 本地 [`.env`](../.env.example) 中，不提交 GitHub：

- MySQL 密码
- Redis 密码
- MinIO 密码
- Elasticsearch 密码
- JWT 密钥
- DeepSeek / Embedding API Key
- 微信支付参数
- 生产域名

## 5. 推荐反向代理方式

### 方案 A：1Panel 反向代理

- 域名绑定到 VPS
- 1Panel 网站反向代理到 `127.0.0.1:8080`
- 自动申请 HTTPS 证书

### 方案 B：宿主机 Nginx

- `80/443 -> 127.0.0.1:8080`
- 前端容器内部再转发：
  - `/api/` 到后端
  - `/proxy-ws/` 到后端 WebSocket

## 6. 常用命令

```bash
./deploy-vps.sh up
./deploy-vps.sh ps
./deploy-vps.sh logs backend
./deploy-vps.sh restart
./deploy-vps.sh down
```

## 7. 更新发布

```bash
git pull
./deploy-vps.sh up
```

或者直接：

```bash
./deploy-vps.sh pull
```

## 8. 上线后第一轮自检

- 页面能打开
- 登录成功
- 邀请码注册策略符合预期
- 文件上传成功
- 文档解析成功
- 检索问答成功
- WebSocket 正常
- 容器状态全部健康

## 9. 注意事项

- [`.env`](../.env.example) 不要提交到 GitHub
- 如果数据库里还没有管理员账号，系统会在启动时自动使用 [`.env`](../.env.example) 里的 [`ADMIN_BOOTSTRAP_USERNAME`](../.env.example:70) 与 [`ADMIN_BOOTSTRAP_PASSWORD`](../.env.example:71) 创建首个管理员；一旦已有管理员，后续启动会自动跳过
- 如果暂时不用微信支付，保持 `WX_PAY_ENABLE=false`
- 如果暂时不用启动知识库导入，保持 `KNOWLEDGE_BOOTSTRAP_ENABLED=false`
- 当前 Elasticsearch 使用 IK 分词插件，首次启动会自动安装，时间会稍长
- Docker 容器日志已限制轮转，相关变量见 [`DOCKER_LOG_MAX_SIZE`](../.env.example:16) 和 [`DOCKER_LOG_MAX_FILE`](../.env.example:17)
- Kafka 数据日志已限制保留时长与体积，相关变量见 [`KAFKA_LOG_RETENTION_HOURS`](../.env.example:23) 和 [`KAFKA_LOG_RETENTION_BYTES`](../.env.example:24)
- Java 文件日志已收缩为小体积轮转，配置位于 [`logback-spring.xml`](../src/main/resources/logback-spring.xml:1)
- 如果 VPS 磁盘紧张，要重点控制 MinIO 上传数据和 Elasticsearch 索引数据，这两部分不会自动按业务层帮你清理
