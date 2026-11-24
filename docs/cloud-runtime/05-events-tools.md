# 05 事件流与工具执行

## 1. 两类数据

```text
messages   最终消息，聊天历史事实源
events     流式增量、工具输出、状态变化、审计
turns      执行状态、队列、审批关联
```

`events` 是过程，`messages` 是结果。

- 历史列表、搜索读 `messages`
- 实时 UI 读 `events`
- 重连先快照再补事件

## 2. 事件类型

```text
turn.created
turn.started
message.delta
message.completed
tool.started
tool.output
tool.completed
tool.cancelled
tool.failed
approval.requested
turn.waiting_approval
approval.resolved
turn.completed
turn.failed
turn.interrupted
container.state
```

## 3. 事件表与 seq

```text
events
├─ id
├─ session_id
├─ seq
├─ turn_id
├─ message_id
├─ type
├─ payload_json
└─ created_at

unique(session_id, seq)
```

`seq` 按会话递增，不全局递增。

分配：

```sql
UPDATE sessions
SET last_seq = last_seq + 1
WHERE id = ?
RETURNING last_seq;
```

一次 flush 可写多条事件，在同一事务里连续分配 seq。

## 4. 流式写入

每个 Turn 一个独立写缓冲：

```text
模型 token 流
→ 追加到内存缓冲
→ 满足任一条件就 flush
   ├─ 50ms 到了
   ├─ 累积 200 字符
   ├─ 工具调用开始
   ├─ 审批请求
   ├─ Turn 结束
   └─ SIGTERM
```

flush：

1. 开短事务
2. 分配 seq
3. 写 `message.delta`
4. 提交
5. 推 SSE

顺序不能反：先写库，再推流。

### 关键事件先 flush

工具开始、审批请求、Turn 结束前，先 flush 文本缓冲，保证 seq 顺序就是 UI 顺序。

### 为什么不是每个 token 写一次

- 每秒几十个 token 会把数据库打成高频小 INSERT
- SSE 也会变成高频小包
- 50ms 一批，App 仍然连续输出，数据库压力降一个量级

## 5. 断线

App 断线只影响 SSE，不影响 Turn。

服务端：

- 事件照常写库
- Turn 照常执行
- 容器照常跑
- 邮件照常发

重连：

```text
带 lastSeq
→ GET /events?afterSeq=lastSeq
→ 补漏掉的事件
→ 再开 SSE
```

如果 `lastSeq` 太旧：

```text
返回 snapshotRequired
App 拉快照
再继续流
```

第一版事件不删，先不搞归档。

## 6. 崩溃丢多少

### 优雅退出

SIGTERM：

- 停止接新 Turn
- flush 所有缓冲
- running Turn 标记 `interrupted`
- 写 `turn.interrupted`

基本不丢已生成内容。

### 进程被 kill

已提交事件不丢。  
最多丢一个 flush 窗口的未提交增量：50ms 或 200 字符以内。

因为 SSE 是提交之后才推，App 不会看到“服务端没落库但客户端已显示”的内容。

### 工具执行中崩溃

```text
tool.started 先落库
→ 执行工具
→ tool.completed 落库
```

崩溃后看到 `tool.started` 没有 `tool.completed`：

- 标记“工具可能已执行”
- Turn `interrupted`
- 不自动重放
- 用户选择继续或重发

## 7. 工具执行协议

### ToolRequest

```text
callId
turnId
sessionId
tool
args
cwd
timeoutMs
stdin?
```

### ToolResult

```text
callId
exitCode
signal
durationMs
stdoutPreview
stderrPreview
truncated
outputRef
```

### 事件

```text
tool.started
tool.output
tool.completed
tool.cancelled
tool.failed
```

## 8. 执行流程

```text
Agent 决定调用工具
→ ToolExecutor 校验工具、参数、路径
→ 判断是否需要审批
   ├─ 需要 → 写 approval.requested → 写 turn_checkpoints → 让出 worker
   └─ 不需要 → 继续
→ ContainerManager.ensureRunning
→ ExecDriver 执行
→ 边读 stdout/stderr，边写 tool.output
→ 写 tool.completed
→ 结果返回 Agent
```

## 9. 执行方式

第一版用 Docker Engine API：

```text
POST /containers/{id}/exec
POST /exec/{id}/start
→ hijacked stream
```

不用宿主 shell 拼 `docker exec`。

抽象：

```text
ExecDriver
├─ DockerApiExecDriver
└─ 以后可替换为 ContainerExecAgent
```

## 10. 命令模型

第一版不做持久 shell。

每次工具调用：

```text
docker exec <container> sh -lc "<command>"
```

- 环境变量、当前目录不跨命令保留
- 需要目录就传 `cwd`
- 需要环境变量就显式传
- 文件、安装的东西留在卷里

## 11. 超时与取消

工具声明超时：

```text
shell.exec          默认 120 秒
包安装              默认 600 秒
普通文件操作        默认 30 秒
```

容器级再设上限。

超时不能只关连接，要杀进程：

- Docker exec 的 shell 本身作为进程组组长，PID 写容器内临时文件
- 超时按进程组 TERM，再 KILL
- 防止子进程残留
- 不使用 `setsid` 包装：部分镜像的 `setsid` 会 fork 后立即退出，导致 exec 流提前结束、命令继续在后台运行
- 超时结果固定 `exitCode=124`、`errorCode=tool_timeout`

用户取消 Turn：

- 停止模型流
- 杀当前命令进程组
- 写 `tool.cancelled`
- Turn `cancelled`
- 容器不删

## 12. 并发

```text
同一会话：工具串行
不同会话：可以并行
同一容器：允许多个命令同时跑
max_exec_per_container 默认 4
```

超过排队，不直接拒绝。

不额外加文件锁。  
两个会话同时改同一个文件的风险，和本地一样。

## 13. 输出

stdout / stderr 分开：

```text
tool.output
├─ callId
├─ stream     stdout / stderr
├─ chunk
├─ offset
└─ truncated
```

写入策略：

- 50ms 或 4KB 一批
- 先落库再推 SSE
- 按 `seq` 保证顺序

## 14. 大输出

```text
单条事件 chunk    最大 4KB
App 预览总量     最大 64KB
Agent 结果        最大 32KB
完整输出          落文件
```

路径：

```text
/var/lib/minibot/tool-logs/<turnId>/<callId>.stdout.log
/var/lib/minibot/tool-logs/<turnId>/<callId>.stderr.log
```

`tool.completed` 带 `outputRef`。

二进制输出不走 SSE，只落文件，返回元信息。

## 15. 审批

工具声明风险：

```text
safe            直接执行
confirm         需要审批
danger          默认拒绝，除非容器策略放行
```

容器策略三档：

```text
全部审批
仅危险命令审批
不审批
```

默认“仅危险命令审批”。  
危险判断是启发式，不承诺绝对安全。

审批拒绝：

- 工具不执行
- 返回明确拒绝结果
- Agent 可以换方案或结束 Turn

审批超时：

- 默认 10 分钟
- 写 `approval.expired`
- 按拒绝处理
- 唤醒 Turn 继续

## 16. 安全边界

容器不放：

- 模型 Key
- App 设备令牌
- 控制面数据库密码
- Docker socket
- 宿主根目录

挂载：

```text
workspace          读写
skills             读写或受控
plugins            受控
tool-logs          只读
```

控制面永远不在宿主执行工具命令，只通过 Docker API 进容器。  
容器不暴露入站端口，App 不直连容器。
