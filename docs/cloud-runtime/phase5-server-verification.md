# Phase 5 真实服务器验证报告

验证日期：2026-11-24（UTC+8）  
服务器：`156.225.16.61`，CentOS Stream 9，4 vCPU / 约 3.6GB 内存  
控制面版本：`0.5.0`  
服务：`minibot-control`，监听 `0.0.0.0:8787`，测试期用 `http://156.225.16.61:8787` 直连

## 1. 结论

Phase 5 的核心运行链路已在真实服务器上跑通：

- PostgreSQL 16 正常，Flyway V1–V6 全部应用。
- 设备配对正常，控制面重启后仍保持已配对状态。
- Docker 容器内 `shell.exec` 正常，stdout 能落事件和日志文件。
- `DANGER` 命令会挂起为 `WAITING_APPROVAL`，审批通过后恢复执行；审批拒绝时不会执行。
- 工具超时会杀掉容器内进程组，返回 `exitCode=124`、`tool_timeout`，没有残留进程。
- 控制面重启后 `WAITING_APPROVAL`、待审批记录和检查点都保留，重启后仍可审批并恢复。
- Turn 完成后检查点被删除，事件序列完整，恢复路径没有重复 `tool.started`。
- 官方 `POST /api/v1/containers` 返回 `201`，使用 `ghcr.io/lisir2002/minibot-base:alpine-amd64` 创建并启动真实容器。
- 在官方 API 创建的容器里再次跑通安全命令、危险命令审批和恢复。
- 接入真实模型 `spark-x2.6`，在官方容器上跑通安全命令、危险审批、超时、重启恢复四条链路。
- 控制面已改为 `0.0.0.0:8787`，公网 HTTP 直连 `/health` 和带 Token 的 `/api/v1/server/info` 均返回 `200`。

补充说明：`minibot-base` 已推送到 GHCR，但 GitHub 用户自有容器包没有修改可见性的公开 API，当前仍是私有包。服务器控制面增加了 GHCR 认证配置，因此本次官方部署 API 能拉取成功。匿名用户和其他未配置凭据的服务器仍无法拉取。

## 2. 验证环境

| 项目 | 结果 |
|---|---|
| Docker | `29.10.0`，Compose `v5.8.0` |
| PostgreSQL | `postgres:16`，容器 `minibot-postgres-dev`，healthy |
| JDK | OpenJDK `11.0.20.1` |
| Flyway | V1 init、V2 containers、V3 sessions/events、V4 models、V5 tools/approvals、V6 turn checkpoints |
| 配对 | `serverId=a03dd561-9999-433f-aa32-de4647a5bafd`，`paired=true` |
| 控制面数据目录 | `/var/lib/minibot` |
| 手工验证容器 | `verify-minibot`，profile `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa` |
| 官方 API 容器 | `verify-official`，profile `7a823cc5-006c-46aa-8715-af194c1787a9`；另建 `verify-official-2` 复测 |
| 镜像 | `ghcr.io/lisir2002/minibot-base:alpine-amd64`，基于 `alpine:3.20`，容器用户 `1000:1000` |
| 私有 Registry | `MINIBOT_REGISTRY_HOST=ghcr.io`，控制面按镜像前缀精确匹配后附带认证 |
| 真实模型 | 讯飞星辰 `spark-x2.6`，OpenAI 兼容端点 `https://maas-api.cn-huabei-1.xf-yun.com/v2` |
| 公网访问 | `http://156.225.16.61:8787`，明文 HTTP，测试期直连 |
| 最终构建 | 分发包 SHA-256 `7f2ab50b…`；部署 jar SHA-256 `10ca876c…` |

模型侧先用本机 HTTPS mock 验证控制面循环，随后改用讯飞星辰 MaaS 的真实模型 `spark-x2.6`（OpenAI 兼容端点 `/v2`）跑完整链路。mock 服务与自签名证书已清理。

## 3. 验证结果

### 3.1 安全命令执行

Turn `32016fc0-6978-4ed3-b270-68010eca0059`：

- 模型返回 `shell.exec`，命令 `printf 'hello from container\n'`。
- `tool.started` 的 `risk=SAFE`。
- `tool.completed`：`exitCode=0`，`durationMs=206`。
- stdout 文件存在：`/var/lib/minibot/volumes/<profile>/tool-logs/<turn>/call_1.stdout.log`，内容为 `hello from container`。
- `tool` 消息落库，随后模型返回最终答复。

### 3.2 危险命令审批通过

Turn `b1cb0a01-9055-4c3b-8c87-28c2e46f5da1`：

- 命令 `rm -rf /tmp/minibot-danger-check`，`risk=DANGER`。
- Turn 进入 `WAITING_APPROVAL`，创建审批 `fe614844-5656-4dc9-9964-bf66171672f6`。
- 事件顺序：`tool.started` → `approval.requested` → `turn.waiting_approval`。
- 审批通过后：`approval.resolved` → `turn.started` → `tool.completed`；恢复路径没有重复 `tool.started`。
- 工具 `exitCode=0`，Turn 完成。

### 3.3 危险命令审批拒绝

Turn `2a1bf95d-dc19-4a4c-bd29-d01e27230282`：

- 审批 `421fcff5-48b4-40a7-81f5-4c5e3500fc2d` 被拒绝。
- 工具结果 `denied=true`、`errorCode=approval_denied`、`exitCode=-1`。
- 没有真正执行 `rm -rf`，Turn 正常结束。

### 3.4 工具超时与进程清理

Turn `2deba962-7820-4878-a5d9-9f8cf02e724f`：

- 命令 `sleep 30`，`timeoutMs=2000`。
- 工具约 4.1 秒后返回：`exitCode=124`、`timedOut=true`、`errorCode=tool_timeout`。
- 容器内 `sleep 30` 已被清理，`ps` 检查无残留。

### 3.5 重启后审批持久化

Turn `e5a49ddb-084e-434d-b840-8642e1c2a44a`：

重启前数据库：

```text
status=WAITING_APPROVAL
turn_checkpoints=1
pending_approvals=1
```

重启 `minibot-control` 后：

```text
status=WAITING_APPROVAL
turn_checkpoints=1
pending_approvals=1
```

重启后快照仍返回 `WAITING_APPROVAL` 和同一 approvalId。再次审批后 Turn 恢复、工具执行、模型继续，最终 `COMPLETED`；检查点数量变为 0。

### 3.6 官方云容器创建 API

首次请求 `POST /api/v1/containers`：

```json
{
  "name": "verify-official",
  "imageRef": "ghcr.io/lisir2002/minibot-base:alpine-amd64",
  "resourcePolicy": {"cpuCores": 1.0, "memoryMb": 1024, "diskMb": 10240},
  "components": [],
  "packages": [],
  "pinned": true
}
```

返回 `HTTP 201`：

- profile：`7a823cc5-006c-46aa-8715-af194c1787a9`
- containerId：`36908e2121fefbb49f81c44f42c239159e5dd56a7be049764571eeeef1d25790`
- phase：`RUNNING`

容器内检查：

```text
uid=1000(minibot) gid=1000(minibot)
/workspace 可写
```

控制面带上私仓认证、改用最终构建后，再次创建 `verify-official-2`，同样返回 `HTTP 201`，profile `4fd1df9e-1e8a-4f7e-8c39-0077c78974df`。

### 3.7 官方容器内的工具与审批

Session `d2a9a70e-a265-4629-9f21-5aa697c12a6d`：

- 安全命令 Turn `28cd5c80-c37a-4a93-8802-c5523436872a`：`tool.completed`，`exitCode=0`，`stdoutPreview=hello from official container`。
- 危险命令 Turn `2e94a383-7ee1-41fa-94ff-6fd214274b17`：进入 `WAITING_APPROVAL`，审批 `eef71b12-400f-45d2-9123-b7b472c776a3`。
- 审批通过后 Turn 恢复，工具 `exitCode=0`，最终完成。

验证后已停止手工容器和 `verify-official-2` 复测容器，保留 `verify-official` 运行，避免三个 1GB 容器同时占用服务器内存。

### 3.8 真实模型闭环

接入讯飞星辰 MaaS 的 `spark-x2.6`。用户提供的 `/anthropic/v1/messages` 端点直接调用可通，但控制面当前未实现 Anthropic 消息格式；因此使用同一服务的 OpenAI 兼容端点 `https://maas-api.cn-huabei-1.xf-yun.com/v2`。控制面新建凭据 `22c296f2-1a8f-4ece-bbdb-4553398dfb8c`、模型定义 `faaa6a20-82a1-4c29-9e7a-ce429d9aa988`、容器绑定 `a2d98545-e06a-42b5-88dc-ed0b8adca4cf`。

模型返回真实流式文本、真实工具调用，以下四条均在官方容器上通过：

| 场景 | Session / Turn | 结果 |
|---|---|---|
| 安全命令 | `df5ad124-62e3-4479-bf1c-36b1bec24e3d` / `71177704-3332-4f75-ac00-3ef81b01f366` | 工具输出 `hello from real spark 3`，`exitCode=0`，模型总结后 `turn.completed` |
| 危险命令审批 | `6a449cca-35bc-4158-9e27-924a3b29b2bb` / `f9b9245e-4cb7-4d4b-bbb2-ecf26fbed46a` | 进入 `WAITING_APPROVAL`，审批 `fb9fcbc8-c788-46cf-842e-c56718247327` 通过后恢复并完成 |
| 工具超时 | `5906de7a-19d2-4946-9272-1f712c9a13f8` / `fd0d960f-7f99-46c1-aa23-5476bd643e0a` | `sleep 30` + `timeoutMs=2000`，返回 `exitCode=124`、`timedOut=true`、`tool_timeout` |
| 重启后审批 | `7ece8836-677a-4e19-8579-8a1aa2967ca5` / `4fa23b05-a8cb-4637-99bc-81713d521c75` | 重启前 `WAITING_APPROVAL`；重启后待审批仍为 1，审批 `6deafaa8-8a8f-44f9-94c0-968e793fd9c3` 后恢复并完成 |

### 3.9 公网 HTTP 直连

按测试要求不使用 HTTPS，控制面从 `127.0.0.1:8787` 改为 `0.0.0.0:8787`，服务器没有 firewalld，INPUT 链默认放行。

验证：

- 服务器内 `ss` 显示 `*:8787` 监听。
- 从外部网络绕过本机代理访问 `http://156.225.16.61:8787/health`，返回 `200` 和 `{"status":"ok"}`。
- 带设备 Token 访问 `http://156.225.16.61:8787/api/v1/server/info`，返回 `200`，`paired=true`，版本 `0.5.0`。

安全边界：这是明文 HTTP，设备 Token、会话内容和工具输出都可能被链路窃听或篡改，只适合当前测试阶段；正式使用前应加 HTTPS 反向代理或至少限制来源 IP。Android 的 `network_security_config.xml` 已允许 cleartext，因此 HTTP 地址可直接使用。

## 4. 发现并修复的问题

### 4.1 宿主机与容器 UID 不一致

首次工具调用返回 `tool_execution_failed`。根因：

- 服务器安装脚本用 `useradd --system` 创建 `minibot`，实际 UID 为 `984`。
- 基础镜像内执行用户是 UID/GID `1000`。
- `/workspace` 和 `tool-logs` 的 bind mount 权限错位，控制面无法创建工具日志目录。

修复：

- 服务器上将 `minibot` 调整为 `1000:1000`，并递归修正 `/var/lib/minibot` 所有权。
- `control-plane/deploy/install.sh` 改为固定创建 `1000:1000`；如果 UID/GID 已被占用则安装失败并提示。
- `docs/cloud-runtime/02-deployment.md`、`03-control-plane.md` 增加部署约束。

### 4.2 `setsid` 导致超时误判

首次超时测试中，工具 2 秒后返回 `exitCode=0`，但 `sleep 30` 仍在容器中运行。根因：

- Docker exec 包装脚本使用 `exec setsid ...`。
- Alpine/BusyBox 的 `setsid` 在调用进程是进程组长时会 fork 后立即退出。
- exec 输出流提前关闭，`ExecStartResultCallback` 误以为命令结束，超时分支没有触发。

修复：

- `DockerExec.kt` 不再使用 `setsid`；让 Docker exec 的 shell 自己作为进程组组长。
- 超时仍按 PID 文件杀进程组，返回 `124`。
- `docs/cloud-runtime/05-events-tools.md` 同步更新实现说明。
- 修复后重新构建、部署、跑超时测试，进程已正确清理。
- 该修复阶段的本地单测：56 个，55 通过、1 跳过、0 失败；跳过项仍是本机无 Docker/PostgreSQL 的 `JdbcPairingStoreIntegrationTest`。

### 4.3 GHCR 镜像发布与私有仓库认证

最初 `ghcr.io/lisir2002/minibot-base` 不存在，`POST /containers` 直接被部署目录校验拒绝；本地 registry 镜像也会返回 `unsupported_image`。

处理：

- 将 Alpine 基础镜像推送到 `ghcr.io/lisir2002/minibot-base:alpine-amd64`。
- GitHub 用户自有容器包没有公开的可见性修改 API，包当前仍是 private；匿名拉取仍被拒绝。
- 控制面新增 `MINIBOT_REGISTRY_HOST`、`MINIBOT_REGISTRY_USERNAME`、`MINIBOT_REGISTRY_TOKEN`。
- `DockerJavaEngine.pullImage` 只在镜像前缀精确匹配配置的 registry 时附加认证，避免把凭据发给其他镜像源。
- 增加 `DockerRegistryAuthTest`，覆盖正常匹配、相似域名和字段缺失。

安全注意：当前配置的是权限较大的个人 Token。验证结束后应换成只带 `read:packages` 的专用 Token，或把镜像改为公开包。

### 4.4 真实模型兼容性修复

首次真实调用暴露两个问题：

1. `baseUrl` 为 `/v2` 时，端点拼接逻辑又补了一层 `/v1`，实际请求变成 `/v2/v1/chat/completions`，返回 404。已改为识别任意 `/vN` 版本前缀，并补端点单测。
2. 模型第二轮流式响应中 `tool_calls` 为 `null`。原代码直接按数组解析，抛出 `JsonNull is not a JsonArray`，Turn 失败。已把所有流式块的可空字段改为安全类型转换。

修复后重新构建、部署，真实模型四条链路全部通过。本地单测 58 个，57 通过、1 跳过、0 失败。

## 5. 仍未完成

1. **镜像可见性**：`minibot-base` 当前是私有包；其他服务器必须配置 GHCR 凭据才能部署。若要匿名拉取，需要在 GitHub 包设置里手动改为 Public。
2. **镜像覆盖不完整**：部署目录声明了 `debian/ubuntu/alpine × amd64/arm64` 六个镜像，本次只发布了 `alpine-amd64`；其余五个仍未发布，选择它们会部署失败。
3. **HTTPS 未配置**：当前按测试要求使用明文 HTTP 直连；正式环境仍应加 HTTPS 反向代理或来源 IP 限制。
4. **多 worker 租约未启用**：`turn_leases` 只有表，未做多 worker 协调和启动补偿扫描。
5. **Registry Token 待收紧**：当前服务器环境文件里是权限较大的个人 Token，应换成只读 `read:packages` Token 或改用公开镜像。
6. **模型 Key 待轮换**：本次测试 Key 已写入控制面加密凭据；正式使用前建议换独立限额 Key。

## 6. 复现入口

- 控制面：`systemctl status minibot-control`
- 健康检查：`curl http://127.0.0.1:8787/health`
- 数据库：`docker exec -it minibot-postgres-dev psql -U minibot -d minibot`
- 事件：`select seq,type,payload_json from events where session_id=... order by seq`
- 检查点：`select * from turn_checkpoints where turn_id=...`
- 工具日志：`/var/lib/minibot/volumes/<profile>/tool-logs/<turn>/`
