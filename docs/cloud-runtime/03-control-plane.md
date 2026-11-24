# 03 控制面进程模型

## 1. 总原则

> Agent 循环在控制面，容器只做执行环境。

控制面是用户服务器上的单进程服务，systemd 管理：

```text
minibot-control
```

不拆微服务。  
单进程、单数据库、单机，第一版最稳，也最容易排障。

## 2. 模块划分

```text
minibot-control
├─ API Server            HTTPS API、SSE、WebSocket
├─ Auth / Pairing        单设备鉴权、bootstrap token
├─ TurnScheduler         会话队列、并发控制
├─ TurnExecutor          Agent 循环
├─ ModelGateway          模型调用、凭据租约
├─ ToolExecutor          容器命令执行
├─ ContainerManager      Docker 启停、对账
├─ EventStore            事件落库、seq
├─ SSEHub                实时推送
├─ NotificationService   App 通知事件 + 邮件发件箱
├─ Reapers               审批超时、空闲回收、租约清理
└─ PostgreSQL 16
```

## 3. 一条消息的路径

```text
App POST /sessions/{id}/turns
    ↓
API 校验设备、会话、容器、模型绑定
    ↓
clientMessageId 幂等去重
    ↓
写 turns，status=queued
    ↓
返回 turnId + seq
    ↓
通知 TurnScheduler
    ↓
调度器发现同会话无执行中 Turn
    ↓
容器 stopped → 置 waiting_runtime → ContainerManager.ensureRunning
    ↓
容器就绪 → TurnExecutor 跑 Agent
    ↓
模型流 / 工具调用 / 审批 / 事件
    ↓
Turn 完成或失败
```

API 请求里不跑 Agent，不调模型，不启容器。  
请求应该很快返回。

## 4. 调度器

### 队列

以数据库为准，内存只是加速：

```text
turns.status=queued
按 queue_order 排序
```

控制面重启后，`queued` 还在。

### 并发

```text
同一会话：串行
不同会话：并行
不同容器：并行
```

资源保护：

```text
max_concurrent_turns       默认 4
max_turns_per_container    默认 4
```

这是保护服务器，不是业务锁。  
用户可在服务器设置里调整。

### 容器 busy

```text
有 running Turn
或 waiting_approval
或 waiting_runtime
或 终端进程
或 pinned
```

任一成立就不空闲回收。

## 5. TurnExecutor

职责：

```text
读会话历史 + 检查点
组装提示词
调模型
流式文本 → message.delta
模型返回工具调用
    ├─ 需要审批 → 写审批、存检查点、让出
    └─ 不需要审批 → ToolExecutor 执行
工具结果写回上下文
继续下一轮模型
直到最终回答
```

关键约束：

- 模型调用在控制面
- 模型 Key 只在控制面内存解密
- 容器默认不注入模型 Key
- 工具结果以事件形式落库
- 审批等待不占 worker

模型协议约束：

- 当前支持 OpenAI 兼容的 `chat/completions`。
- `baseUrl` 可以是域名根路径、`/v1`、`/v2` 或完整 `.../chat/completions`，控制面只补一次版本路径。
- 流式块里的 `choices`、`tool_calls`、`usage` 等字段允许为 `null`，解析时按可空处理。
- 讯飞星辰 MaaS 的 Anthropic 端点不能直接配置；使用同一服务的 OpenAI 兼容端点 `/v2`。

## 6. 审批

遇到需要审批的工具：

1. 写 `approval.requested`
2. Turn 置 `waiting_approval`
3. 保存检查点：消息历史、待执行工具、参数
4. 让出执行线程
5. 发 App 通知 + 邮件

用户批准后：

1. API 写审批结果
2. `TurnResumer` 认领 Turn
3. 读检查点
4. 追加审批结果
5. 继续执行

超时：

- `ApprovalReaper` 定时扫描
- 到期写 `approval.expired`
- 按拒绝处理
- 唤醒 Turn 继续

默认 10 分钟；纯邮件部署建议 30 分钟。  
邮件只通知，不能直接批准。

## 7. ContainerManager

职责：

- `ensureRunning(profileId)`：按 profile 加锁，幂等启动
- `stop`：只在没有活跃 Turn、审批、终端时停
- `reconcile`：启动时对比数据库和 `docker ps`
- 打标签：`minibot.profile=<id>`，方便对账
- 停容器不删 workspace

多会话同时需要同一个容器时，只启动一次，其他等就绪。

部署约束：宿主机运行控制面的 `minibot` 用户 UID/GID 必须与基础镜像内执行用户一致，当前为 `1000:1000`。否则 bind mount 的 `/workspace`、`/home/minibot/.minibot/logs` 会出现“容器能跑但控制面写不了工具日志”的权限错位。

私有镜像仓库：可选配置 `MINIBOT_REGISTRY_HOST`、`MINIBOT_REGISTRY_USERNAME`、`MINIBOT_REGISTRY_TOKEN`。`install.sh` 会在重装时保留已有配置，也可在命令行环境里传入这三项覆盖。控制面只对 `imageRef` 前缀精确匹配该 registry 的拉取请求附加认证，不会把凭据发给其他镜像源。Token 只写入权限为 `0600` 的服务器环境文件，不进入数据库、API 响应或日志。

## 8. 崩溃恢复

控制面重启后：

1. 打开数据库
2. 清理过期配对码
3. 所有 `running` 且租约过期的 Turn 标记 `interrupted`
4. 写 `turn.interrupted`
5. 保留 `queued`，调度器继续跑
6. 保留 `waiting_approval`，等决定或超时
7. 恢复邮件发件箱
8. 对账容器状态，缺的标 `stopped/error`
9. 不自动重放有副作用的工具

第一版不承诺“崩溃后自动从中断点继续执行工具”。  
工具可能已经改了文件、发了请求，自动重放不安全。

用户可以在 App 看到：

```text
任务已中断
[继续]
[重新发送]
```

`继续` 是从检查点恢复同一 Turn，不是假装什么都没发生。

### 租约

```text
turn_leases
├─ turn_id
├─ lease_owner
├─ expires_at
└─ updated_at
```

- 当前只建表，多 worker 协调未启用
- 后续心跳 10 秒、过期 60 秒、启动清理
- 为以后多 worker 留结构

## 9. 数据库

> 第一版 PostgreSQL 16，不用 SQLite。

原因：

- 多会话并发写事件
- JSONB + GIN
- `pg_trgm` 聊天检索
- `FOR UPDATE SKIP LOCKED`
- `LISTEN/NOTIFY`
- 备份、恢复、监控成熟

部署：

- 控制面自带 PostgreSQL 容器，或复用用户已有 PostgreSQL
- 只监听本机
- 独立持久卷
- 连接池复用

### 写入约束

- 事件批量提交
- 热表索引保持少
- 禁止长事务
- 邮件和检索不在 Turn 事务里
- 大字段不写事件 payload

## 10. 资源与限流

```text
max_concurrent_turns
max_turns_per_container
max_exec_per_container
max_terminals_per_container
max_upload_bytes
max_tool_output_bytes
```

超限策略：

- Turn：排队，不直接拒绝
- 工具：排队或失败并提示
- 终端：拒绝新建
- 上传：拒绝并提示配额

## 11. 优雅退出

收到 SIGTERM：

1. 停止接新 Turn
2. flush 所有事件缓冲
3. running Turn 标记 `interrupted`
4. 写 `turn.interrupted`
5. 关闭 SSE
6. 关闭数据库

容器不主动删除。  
控制面重启后对账恢复。
