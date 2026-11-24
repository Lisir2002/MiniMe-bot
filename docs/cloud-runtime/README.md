# Mini-AIBOT 云端运行时设计

> 状态：Phase 1/2/3/4 已落地；Phase 5 工具执行、审批与检查点恢复已落地，通知、文件、终端待接入  
> Phase 9 已开始：云端部署入口、IP/域名可选访问方式、设备配对和基础容器创建代码已落地；安装命令生成与 Release 发布链路代码已落地，云会话/SSE、文件与终端 UI 尚未接入，真实域名证书未验证  
> 实现起点：`control-plane/`  
> 范围：纯本地 / 用户自建纯云端双模式中的云端部分  
> 目标：聊天记录、命令执行、结果输出都在用户自己的云服务器上运行；App 只做控制、展示和通知入口。

---

## 1. 一句话结论

```text
App 是客户端
控制面在用户云服务器
Agent 循环在控制面
容器只做执行环境
数据、密钥、事件、队列都在云端
```

云端不是“把本地容器搬到服务器”，而是：

- 本地容器：Agent 在 App，容器在手机
- 云端容器：Agent 在服务器控制面，容器也在服务器
- App 关闭不影响云端任务
- 正式环境 App 只通过 HTTPS / SSE 读状态、发指令、做审批；测试阶段可显式使用 IP+HTTP，并标注风险

---

## 2. 文档目录

| 文档 | 内容 |
| --- | --- |
| [01-architecture.md](01-architecture.md) | 总体架构、端云职责、ContainerProfile、生命周期、与现有架构的关系 |
| [02-deployment.md](02-deployment.md) | 服务器接入、部署向导、官方镜像、组件与包管理 |
| [03-control-plane.md](03-control-plane.md) | 控制面进程模型、调度、并发、容器管理、崩溃恢复 |
| [04-api-data.md](04-api-data.md) | 第一批 API、PostgreSQL 数据表、事件序号、幂等 |
| [05-events-tools.md](05-events-tools.md) | 消息链路、事件批量写入、SSE、断线、工具执行协议、审批 |
| [06-model-keys.md](06-model-keys.md) | 模型目录、凭据、绑定、Key 加密、模型网关、重试 |
| [07-workspace-files-notifications.md](07-workspace-files-notifications.md) | 工作区、技能、插件、文件 API、终端、App/邮件通知 |
| [08-security.md](08-security.md) | 安全边界、路径校验、密钥保护、终端与文件风险 |
| [09-implementation-plan.md](09-implementation-plan.md) | 第一批实现顺序、验收标准、测试策略、明确不做 |
| [10-domain-binding.md](10-domain-binding.md) | 部署向导“访问方式”步骤、域名校验、Caddy/HTTPS 安装契约、失败降级 |

---

## 3. 关键决策

| 编号 | 决策 | 备注 |
| --- | --- | --- |
| D-01 | 云端容器统一纳入 `ContainerProfile`，`kind=LOCAL/CLOUD` | 列表统一管理，后端适配器分离 |
| D-02 | 云端服务器由用户自备，产品不分发服务器 | 控制面部署在用户服务器 |
| D-03 | App 不使用 SSH 连接云服务器 | 正式环境只走 HTTPS；测试阶段可显式切到 IP+HTTP，并在界面持续标注风险 |
| D-04 | Agent 循环在控制面，容器只做执行环境 | 避免提示词/技能/工具分裂 |
| D-05 | 控制面单进程 + PostgreSQL 16 | 单机部署，性能与检索优先 |
| D-06 | 每个 ContainerProfile 一个 Docker 容器 | 用户服务器上运行 |
| D-07 | 会话内串行，会话间并行 | 对齐本地容器语义 |
| D-08 | 事件按会话 `seq` 递增，先落库再推 SSE | 断线可补，崩溃不丢已提交事件 |
| D-09 | 模型凭据按环境隔离，云端 Key 只存服务器 | App 永不持有云端明文 |
| D-10 | 第一版通知只做 App 内通知 + 邮件 | 原生 FCM/APNs 后续可选 |
| D-11 | 第一版只做单设备 | 不做多设备同步、设备列表、跨设备已读 |
| D-12 | 工具执行第一版走 Docker Engine API `exec` | 不拼宿主 shell |
| D-13 | 文件 API 只操作工作区，终端是服务端 PTY | 与本地路径语义一致 |
| D-14 | 删除实例只 stop 不 rm，删除档案才销毁 | rootfs 可写层保留 |
| D-15 | 空闲 10 分钟保温、30 分钟停止、7 天归档 | `pinned`、活跃 Turn、审批、终端不回收 |
| D-16 | 域名绑定是可选访问方式 | 服务器安装器 + Caddy 自动 HTTPS；App 不收集 DNS 服务商 Token |

---

## 4. 架构总览

```text
Android App
  │ HTTPS / SSE
  ▼
┌─────────────────────────────────────────────┐
│ minibot-control（用户云服务器）             │
│                                             │
│  API / 配对 / 单设备鉴权                    │
│  TurnScheduler    会话队列、并发控制        │
│  TurnExecutor     Agent 循环                │
│  ModelGateway     模型调用、密钥租约        │
│  ToolExecutor     容器命令执行              │
│  ContainerManager Docker 启停、对账         │
│  EventStore + SSE 事件落库、断线补发        │
│  Notification     App 通知 + 邮件发件箱     │
│  Reapers          审批超时、空闲回收        │
│                                             │
│  PostgreSQL 16                              │
└─────────────────────────────────────────────┘
  │ Docker Engine API
  ▼
┌─────────────────────────────────────────────┐
│ 云端容器（每个 ContainerProfile 一个）      │
│  /workspace                                 │
│  /home/minibot/.minibot/skills              │
│  /home/minibot/.minibot/plugins             │
│  /home/minibot/.minibot/logs                │
└─────────────────────────────────────────────┘
```

---

## 5. 端云职责边界

| 能力 | App | 控制面 | 容器 |
| --- | --- | --- | --- |
| 聊天 UI | 负责 | 提供 API | 不参与 |
| 会话队列 | 不负责 | 负责 | 不参与 |
| Agent 循环 | 不负责 | 负责 | 不参与 |
| 模型调用 | 不负责 | 负责 | 不参与 |
| 模型 Key | 不持有云端明文 | 加密存储/解密 | 不注入 |
| 命令执行 | 不负责 | 调度 | 执行 |
| 技能文件 | 展示/上传 | 读取/拼提示词 | 挂载 |
| 插件 | 安装入口 | 安装/注册 | 脚本执行 |
| 事件流 | 展示 | 落库/推送 | 不参与 |
| 文件 | 浏览/编辑 | 校验/读写 | 挂载 |
| 终端 | 输入/展示 | 持有 PTY | 执行 |
| 通知 | App 内提示 | 事件 + 邮件 | 不参与 |

明确写死：

```text
App 关闭 ≠ 停止任务
App 退出 ≠ 停止容器
App 断网 ≠ 中断 Turn
```

只有这些会停任务：

- 用户主动取消 Turn
- 服务器关机/断网
- 控制面崩溃
- 容器异常退出
- 审批超时且无法继续

---

## 6. 与现有架构的关系

现有仓库已有“插件三轴”架构文档：

- [plugin-three-axis-architecture.md](../architecture/plugin-three-axis-architecture.md)

云端模式复用其中的“外部完整运行体”组合：

```text
执行后端：CloudExecutionBackend
Agent 工作流：CloudWorkflowClient（不在 App 跑 Agent 循环）
会话源：CloudSessionSource
```

与本地模式的区别：

- 本地：`AgentRunManager` 在 App 内调度、持久化、跑 Agent
- 云端：控制面负责队列、持久化、Agent 循环；App 只做客户端
- App 不再为云端会话维护第二份 Room 消息副本
- 云端会话的 `sessionId`、`turnId`、`lastSeq` 以控制面为准

实现时不要把云端硬塞进本地 `AgentRunManager` 的会话队列。  
云端需要的是“远端运行体客户端”，不是“本地工作流适配器”。

---

## 7. 第一版明确不做

- 多设备同步、设备列表、跨设备已读
- 原生 FCM/APNs
- 邮件内直接审批
- 多租户、共享服务器
- 云端 Key 回传 App
- 跨容器共享卷
- 原生 Android 插件在云端运行
- 常驻插件进程
- 镜像升级自动迁移 rootfs 环境
- 持久 PTY / tmux 跨控制面重启
- 文件版本历史、断点续传、协同编辑
- 全量 apt 预装；改为部署时按需选择组件

---

## 8. 术语

| 术语 | 含义 |
| --- | --- |
| ContainerProfile | 容器持久身份，`kind=LOCAL/CLOUD` |
| ContainerInstance | 某次运行的容器实例，可停可重建 |
| Workspace | 容器内工作区，第一版一个容器一个默认工作区 |
| Session | 会话，归属某个容器 |
| Turn | 一条用户消息触发的一轮执行 |
| Event | 会话内追加事件，按 `seq` 排序 |
| Approval | 工具执行前的审批请求 |
| Credential | 某 provider 的 Key，按本地/云端隔离 |
| Binding | 模型 + 凭据绑定到容器 |
| Credential Lease | 一次 Turn 内复用的解密密钥租约 |

---

## 9. 文档维护规则

- 所有实现以本目录文档为准；与旧文档冲突时，先更新本目录并注明
- 接口、数据表变更必须同步 `04-api-data.md`
- 任何涉及密钥、文件路径、终端的安全变更必须同步 `08-security.md`
- 第一批范围变更必须同步 `09-implementation-plan.md`
