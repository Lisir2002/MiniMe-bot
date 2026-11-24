# 04 第一批 API 与数据表

## 1. API 原则

- 正式环境 App 只通过 HTTPS 访问控制面；测试阶段可显式切到 IP+HTTP，接口和界面都标注风险
- App 不使用 SSH
- 第一版单设备
- 所有写接口幂等
- 事件只追加
- API 请求不执行长任务

统一前缀：

```text
/api/v1
```

## 2. 鉴权

### 配对

```text
POST /api/v1/pair/claim
```

请求：

```json
{
  "bootstrapToken": "...",
  "devicePublicKey": "...",
  "deviceName": "Android"
}
```

返回：

```json
{
  "deviceId": "...",
  "deviceToken": "...",
  "serverId": "...",
  "serverName": "Mini-AIBOT Cloud"
}
```

- 第一版只允许一个设备
- 已绑定后再次 claim 返回 409
- 换设备需服务器本地 CLI 重置

### 请求头

```text
Authorization: Bearer <deviceToken>
Idempotency-Key: <uuid>     写接口可选
```

## 3. 服务器与容器

```text
GET  /api/v1/server/info
GET  /api/v1/deploy/options
GET  /api/v1/containers
POST /api/v1/containers
GET  /api/v1/containers/{id}
PATCH /api/v1/containers/{id}
POST /api/v1/containers/{id}/start
POST /api/v1/containers/{id}/stop
DELETE /api/v1/containers/{id}/instance
DELETE /api/v1/containers/{id}/archive?confirm=true
```

`GET /api/v1/server/info` 返回（节选）：

```json
{
  "serverId": "...",
  "serverName": "Mini-AIBOT Cloud",
  "paired": true,
  "accessMode": "ip",
  "publicUrl": null,
  "domain": null,
  "architecture": "amd64"
}
```

`accessMode` 为 `ip` 或 `domain`；`architecture` 为 `amd64` 或 `arm64`，App 用它筛选匹配的官方镜像。

`GET /containers` 返回：

```json
[
  {
    "profile": {
      "id": "...",
      "kind": "CLOUD",
      "name": "...",
      "imageRef": "ghcr.io/lisir2002/minibot-base:debian-amd64",
      "imageDigest": null,
      "resourcePolicy": {"cpuCores": 1.0, "memoryMb": 1024, "diskMb": 10240},
      "components": ["node"],
      "packages": [],
      "createdAt": "2026-11-24T00:00:00Z"
    },
    "instance": {
      "id": "...",
      "profileId": "...",
      "containerId": "...",
      "phase": "RUNNING",
      "pinned": false,
      "lastActivityAt": "2026-11-24T00:00:00Z",
      "lastError": null
    },
    "activity": {
      "activeTurns": 1,
      "activeTerminals": 0,
      "pendingApprovals": 0,
      "pinned": false
    }
  }
]
```

Phase 2 的 `deploy/options` 返回官方镜像、组件、包管理器和资源预设；`POST /containers` 只接受目录中的官方镜像与组件，容器创建后组件安装任务尚未接入。

`DELETE /instance`：

- 只 stop，不 rm
- 保留卷和 rootfs
- 绑定保留

`DELETE /archive`：

- 删除容器和卷
- 删除绑定和密钥
- 不可恢复

## 3.1 模型凭据与绑定

云端凭据只存加密后的密文，API 不返回明文，也不返回密钥版本以外的密钥信息。

```text
GET  /api/v1/model-credentials
POST /api/v1/model-credentials
GET  /api/v1/model-definitions
POST /api/v1/model-definitions
GET  /api/v1/model-bindings?profileId=
POST /api/v1/model-bindings
DELETE /api/v1/model-bindings/{id}
```

规则：

- 凭据创建后为 `UNASSIGNED`，绑定成功后才变为 `ACTIVE`
- 凭据和模型定义的 provider 必须一致
- 绑定必须属于当前 `ContainerProfile`，不允许跨容器使用
- 会话创建时可带 `modelBindingId`；执行 Turn 时再次校验绑定属于该容器
- 云端只接受 `CLOUD_SERVER` 凭据；本地凭据不进入云端运行时
- 模型调用失败只记录错误码、耗时和 token 用量，不写完整 prompt 或密钥
- 目前只支持 OpenAI-compatible 流式接口；Anthropic/Gemini 适配器后续接入

## 4. 会话与 Turn

```text
GET  /api/v1/sessions
POST /api/v1/sessions
GET  /api/v1/sessions/{id}/snapshot
POST /api/v1/sessions/{id}/turns
POST /api/v1/sessions/{id}/turns/{turnId}/cancel
GET  /api/v1/sessions/{id}/events?afterSeq=123
GET  /api/v1/sessions/{id}/stream?afterSeq=123
```

### 发消息

请求：

```json
{
  "clientMessageId": "uuid",
  "content": "...",
  "attachments": []
}
```

返回：

```json
{
  "turnId": "uuid",
  "seq": 42,
  "status": "queued"
}
```

规则：

- `clientMessageId` 幂等
- 先落库再返回
- 同会话已有执行中 Turn，新 Turn 排队
- 容器 stopped 时返回 `waiting_runtime`
- Phase 4 起真实 Turn 执行器调用云端模型绑定；没有绑定的会话会明确返回 `binding_unavailable`

### 快照

`GET /sessions/{id}/snapshot` 返回：

```text
当前 Turn
队列
待审批
最后 seq
模型绑定状态
容器状态
当前流式输出尾部
```

App 打开会话先拉快照，再补事件。

### 事件流

第一版用 SSE：

```text
GET /sessions/{id}/stream?afterSeq=123
```

- 服务端到 App 单向
- 发消息、审批走普通 HTTP
- 断线重连带 `afterSeq`
- 每 15 秒心跳
- App 按 `seq` 去重

## 5. 审批

```text
GET  /api/v1/approvals?status=pending
POST /api/v1/turns/{turnId}/approvals/{approvalId}
```

请求：

```json
{
  "decision": "approve",
  "reason": null
}
```

- 只允许 `approve` / `deny`
- 同一审批重复提交幂等
- 已过期返回 409
- 决定后写 `approval.resolved`

## 6. 通知

```text
GET  /api/v1/notifications?afterId=...
POST /api/v1/notifications/{id}/read
GET  /api/v1/settings/notifications
PUT  /api/v1/settings/notifications
POST /api/v1/settings/test-email
```

第一版：

- App 内通知中心
- 邮件通知
- 单设备已读游标

## 7. 文件与终端

```text
GET  /api/v1/workspaces/{id}/files?path=/
GET  /api/v1/workspaces/{id}/files/stat?path=...
GET  /api/v1/workspaces/{id}/files/content?path=...&offset=&limit=
PUT  /api/v1/workspaces/{id}/files/content?path=...
POST /api/v1/workspaces/{id}/files/mkdir
POST /api/v1/workspaces/{id}/files/move
DELETE /api/v1/workspaces/{id}/files?path=...
POST /api/v1/workspaces/{id}/files/upload
GET  /api/v1/workspaces/{id}/files/download
GET  /api/v1/workspaces/{id}/files/search?q=...

POST   /api/v1/containers/{id}/terminals
GET    /api/v1/containers/{id}/terminals
WS     /api/v1/terminals/{terminalId}/attach
POST   /api/v1/terminals/{terminalId}/resize
DELETE /api/v1/terminals/{terminalId}
```

详细行为见 [07-workspace-files-notifications.md](07-workspace-files-notifications.md)。

## 8. 错误格式

```json
{
  "code": "container_start_failed",
  "message": "容器启动失败",
  "retryable": true,
  "details": {}
}
```

常用错误码：

```text
unauthorized
device_already_paired
container_not_found
container_start_failed
binding_unavailable
credential_invalid
turn_not_found
approval_expired
approval_already_decided
path_outside_workspace
quota_exceeded
smtp_send_failed
```

Provider 错误必须脱敏后再落库。

## 9. PostgreSQL 数据表

### server_settings

```text
id
server_name
timezone
approval_timeout_seconds
notification_settings_json
created_at
updated_at
```

### server_device

第一版只一行。

```text
id
public_key
token_hash
device_name
created_at
revoked_at
```

### pairing_tokens

```text
id
token_hash
expires_at
used_at
created_at
```

### container_profiles

```text
id
kind              LOCAL / CLOUD
name
image_ref
image_digest
resource_policy_json
created_at
updated_at
```

### container_instances

```text
id
profile_id
container_id
phase
last_activity_at
pinned
last_error
created_at
updated_at
```

### workspaces

```text
id
profile_id
name
root_path
created_at
```

### sessions

```text
id
profile_id
workspace_id
title
model_binding_id
last_seq
created_at
updated_at
```

### turns

```text
id
session_id
client_message_id
status
queue_order
created_at
started_at
finished_at
error_code
error_message
```

状态：

```text
queued
waiting_runtime
running
waiting_approval
completed
failed
interrupted
cancelled
```

### messages

最终消息，聊天历史事实源。

```text
id
session_id
turn_id
role
content
content_json
created_at
```

### message_search

聊天检索独立表。

```text
message_id
session_id
content
tsv
updated_at
```

索引：

- `GIN(tsv)`
- `GIN(content gin_trgm_ops)`

### events

```text
id
session_id
seq
turn_id
message_id
type
payload_json
created_at
```

约束：

```text
unique(session_id, seq)
```

### approvals

```text
id
turn_id
session_id
status
request_json
decision
reason
expires_at
decided_at
created_at
```

状态：

```text
pending
approved
denied
expired
cancelled
```

### turn_checkpoints

```text
turn_id
session_id
payload_json   # approvalId、step、待执行 tool_call
created_at
updated_at
```

### turn_leases

```text
turn_id
lease_owner
expires_at
updated_at
```

### provider_credentials

```text
id
scope             LOCAL_APP / CLOUD_SERVER
provider
base_url
encrypted_secret
secret_nonce
key_version
status
created_at
revoked_at
```

### model_definitions

```text
id
provider
model
display_name
capabilities_json
context_window
input_price
output_price
created_at
```

### model_bindings

```text
id
profile_id
model_definition_id
credential_id
status
created_at
updated_at
deleted_at
```

### model_calls

```text
id
session_id
turn_id
binding_id
provider
model
input_tokens
output_tokens
latency_ms
status
error_code
created_at
```

只记录调用元数据，不记录 prompt、响应正文或密钥。

### notification_events

```text
id
type
session_id
turn_id
payload_json
created_at
```

### notification_deliveries

```text
id
notification_event_id
channel           app / email
status            pending / sent / failed / skipped
attempts
last_error
next_retry_at
sent_at
```

### smtp_configs

```text
id
host
port
security
username
encrypted_password
from_address
to_addresses
enabled
created_at
updated_at
```

### plugin_installations

```text
id
profile_id
plugin_id
version
status
installed_at
```

### terminal_sessions

```text
id
profile_id
container_id
status
cols
rows
created_at
last_activity_at
```

## 10. 索引

热路径：

```text
events(session_id, seq) unique
turns(session_id, status, queue_order)
approvals(status, expires_at)
notification_deliveries(status, next_retry_at)
container_instances(profile_id, phase)
```

不要给 `events.payload_json` 建 GIN 索引，避免写放大。  
检索走 `message_search`。

## 11. 幂等

- `clientMessageId` 在 session 内唯一
- 审批重复提交返回同一结果
- 事件 `unique(session_id, seq)`
- 通知投递按 `notification_event_id + channel` 唯一
- 容器启动按 profile 加锁，重复调用不创建第二个容器
