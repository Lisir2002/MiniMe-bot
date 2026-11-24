# minibot-control

Mini-AIBOT 用户自建云服务器控制面。Phase 1/2/3/4/5 骨架。

## 当前能力

- PostgreSQL 16 连接与 Flyway migration
- 单设备配对
- `/health`
- `/api/v1/server/info`
- `/api/v1/pair/claim`
- 主密钥文件生成
- 云容器档案/实例表与 Docker Engine API 封装
- 官方镜像目录与部署选项
- 卷目录创建、启动/停止/删除实例/归档
- 控制面重启后的容器对账
- 会话 / Turn / 消息 / 事件持久化与快照
- 会话内 Turn 串行、不同会话并行
- `clientMessageId` 幂等
- 事件按会话 seq 递增、SSE 补发与 15 秒心跳
- 云端模型凭据 AES-256-GCM 加密存储
- 模型定义与容器级模型绑定
- OpenAI-compatible 流式模型网关
- 真实 Turn 执行器、文本增量、usage 记录与 429/5xx 重试
- 模型 tool_calls 解析与多轮模型/工具循环
- Docker Engine API `shell.exec`、stdout/stderr 事件与大输出日志
- 待审批列表、审批决定与超时拒绝
- 审批检查点、`WAITING_APPROVAL` 状态与决定后恢复
- systemd / 安装脚本骨架

尚未实现：

- 部署向导的组件实际安装任务（目录已返回，安装执行器未接）
- `turn_leases` 多实例租约、控制面重启后已决定审批的补偿恢复
- 通知 / 文件 / 终端
- Anthropic、Gemini 等非 OpenAI-compatible 适配器
- 空闲休眠与归档后台任务
- 真实 Docker/PostgreSQL 环境验证
- App 接入

完整设计见：

```text
../docs/cloud-runtime/
```

## 本地运行

需要 JDK 11+ 和 PostgreSQL 16。

```bash
cd control-plane
docker compose -f docker-compose.dev.yml up -d
```

设置开发环境变量：

```bash
export MINIBOT_DATA_DIR=/tmp/minibot-dev
export MINIBOT_DB_URL=jdbc:postgresql://127.0.0.1:5432/minibot
export MINIBOT_DB_USER=minibot
export MINIBOT_DB_PASSWORD=minibot-dev
export MINIBOT_BOOTSTRAP_TOKEN=dev-bootstrap-token
export MINIBOT_DOCKER_HOST=unix:///var/run/docker.sock
```

控制面需要能访问 Docker socket；本地开发可直接使用宿主 Docker。

启动：

```bash
../gradlew -p control-plane run
```

健康检查：

```bash
curl -s http://127.0.0.1:8787/health
```

配对：

```bash
curl -s -X POST http://127.0.0.1:8787/api/v1/pair/claim \
  -H 'Content-Type: application/json' \
  -d '{
    "bootstrapToken": "dev-bootstrap-token",
    "devicePublicKey": "dev-public-key",
    "deviceName": "Dev"
  }'
```

服务器信息：

```bash
curl -s http://127.0.0.1:8787/api/v1/server/info \
  -H 'Authorization: Bearer <deviceToken>'
```

部署目录：

```bash
curl -s http://127.0.0.1:8787/api/v1/deploy/options \
  -H 'Authorization: Bearer <deviceToken>'
```

添加云端模型凭据（密钥不会回传）：

```bash
curl -s -X POST http://127.0.0.1:8787/api/v1/model-credentials \
  -H 'Authorization: Bearer <deviceToken>' \
  -H 'Content-Type: application/json' \
  -d '{
    "provider": "openai",
    "baseUrl": "https://api.openai.com/v1",
    "secret": "sk-..."
  }'
```

`baseUrl` 支持根路径、`/v1`、`/v2` 或完整 `.../chat/completions`。例如讯飞星辰 MaaS 使用：

```json
{"provider":"openai_compatible","baseUrl":"https://maas-api.cn-huabei-1.xf-yun.com/v2","secret":"ak-..."}
```

该服务的 `/anthropic/v1` 是 Anthropic 消息格式，当前控制面不支持，配置时要用 OpenAI 兼容的 `/v2`。

创建模型定义并绑定到容器：

```bash
curl -s -X POST http://127.0.0.1:8787/api/v1/model-definitions \
  -H 'Authorization: Bearer <deviceToken>' \
  -H 'Content-Type: application/json' \
  -d '{"provider":"openai","model":"gpt-4o-mini","displayName":"GPT-4o mini"}'

curl -s -X POST http://127.0.0.1:8787/api/v1/model-bindings \
  -H 'Authorization: Bearer <deviceToken>' \
  -H 'Content-Type: application/json' \
  -d '{"profileId":"<profileId>","modelDefinitionId":"<definitionId>","credentialId":"<credentialId>"}'
```

创建云容器：

```bash
curl -s -X POST http://127.0.0.1:8787/api/v1/containers \
  -H 'Authorization: Bearer <deviceToken>' \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "dev-cloud",
    "imageRef": "ghcr.io/lisir2002/minibot-base:debian-amd64",
    "resourcePolicy": {"cpuCores": 1.0, "memoryMb": 1024, "diskMb": 10240},
    "components": ["node"],
    "packages": [],
    "pinned": false
  }'
```

## 测试

只跑单元测试，不跑全量 Android 构建：

```bash
../gradlew -p control-plane test
```

## 安装到服务器

构建可执行发行包：

```bash
../gradlew -p control-plane installDist
```

安装：

```bash
sudo ./deploy/install.sh \
  --binary build/install/minibot-control/bin/minibot-control \
  --bootstrap "$(openssl rand -hex 24)"
```

安装器会：

- 检查 root / systemd / Docker
- 创建 `minibot` 用户
- 启动 bundled PostgreSQL 容器
- 写入 `/etc/minibot/control.env`
- 安装 systemd 服务
- 输出一次性配对 token

## 配置

见 `deploy/control.env.example`。

关键配置：

| 变量 | 说明 |
| --- | --- |
| `MINIBOT_HOST` | 监听地址，默认 `127.0.0.1`，由反向代理终止 TLS |
| `MINIBOT_PORT` | 默认 `8787` |
| `MINIBOT_DB_URL` | PostgreSQL JDBC URL |
| `MINIBOT_DATA_DIR` | 数据目录，默认 `/var/lib/minibot` |
| `MINIBOT_MASTER_KEY_PATH` | 主密钥路径 |
| `MINIBOT_BOOTSTRAP_TOKEN` | 一次性配对 token，生产由安装器写入文件 |
| `MINIBOT_PAIRING_TTL_SECONDS` | 配对 token 有效期 |
| `MINIBOT_DOCKER_HOST` | Docker Engine 地址，默认 `unix:///var/run/docker.sock` |

## 接口契约

`openapi/openapi.yaml`

## 安全

- 设备令牌只返回一次，数据库存 SHA-256 hash
- 配对 token 一次性、短有效期
- 配对 token 到期后不会因重启自动续期；重新运行安装器生成新 token
- 主密钥 0600，不进数据库
- 云端模型密钥 AES-256-GCM 加密后存 PostgreSQL，API 只返回 `hasSecret`
- 系统提示、事件、日志、容器环境变量都不包含云端模型密钥
- 默认只监听 `127.0.0.1`
- 生产必须由 Caddy/Nginx 提供 HTTPS
