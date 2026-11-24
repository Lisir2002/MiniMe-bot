# 09 第一批实现顺序

## 0. 当前状态

```text
Phase 0  设计文档、OpenAPI 骨架、V1 migration 已落地
Phase 1  控制面骨架、配对、/health、/server/info 已落地
Phase 2  容器档案/实例、Docker Engine 封装、卷管理、部署目录与容器 API 已落地
         组件实际安装任务、空闲休眠/归档后台任务尚未实现
Phase 3  会话/Turn/消息/事件表、会话内串行调度、快照、事件补发与 SSE 已落地
Phase 4  云端模型凭据、模型定义/绑定、AES-256-GCM 密钥库、OpenAI 兼容流式网关、
         真实 Turn 执行器、usage 记录与 429/5xx 重试已落地
         工具调用、审批、通知、文件、终端属于后续阶段
Phase 9  云端部署入口、IP/域名可选访问方式、设备配对和基础容器创建代码已落地；安装命令生成与 Release 发布链路代码已落地，云会话/SSE、文件与终端 UI 尚未接入，真实域名证书未验证
```

实现代码：

```text
control-plane/
```

## 1. 第一批目标

先跑通一条最小但完整的云端链路：

```text
部署云服务器
→ 创建云容器
→ 创建会话
→ 发一条消息
→ 控制面跑 Agent
→ 调模型
→ 执行一个 shell 工具
→ 事件流回 App
→ App 关闭后任务继续
→ 重开 App 恢复进度
→ 危险工具触发审批
→ 邮件通知
```

第一批不追求功能多，追求链路闭合、可恢复、可排障。

## 2. 阶段划分

### Phase 0：契约冻结

产出：

- 本目录设计文档
- OpenAPI 骨架
- PostgreSQL migration 初始版本
- 关键错误码表
- 事件类型表

验收：

- API 路径、字段、状态机不再反复改
- 数据表能支持消息、事件、审批、通知
- `ContainerProfile kind=LOCAL/CLOUD` 字段确定

### Phase 1：控制面骨架

范围：

- `minibot-control` 进程
- HTTPS API
- PostgreSQL 连接与 migration
- 单设备配对
- `/server/info`
- 健康检查
- systemd 安装脚本

验收：

- 服务器安装后能启动
- App 能自动配对
- 重复 claim 返回 409
- 重启后设备令牌仍有效
- 日志不含密钥

### Phase 2：容器与部署向导后端

范围：

- `ContainerProfile` / `ContainerInstance`
- Docker Engine API 封装
- `ensureRunning` / `stop` / `reconcile`
- 部署向导七步的后端接口
- 官方镜像拉取
- 卷创建
- 组件安装任务

验收：

- 能创建一个云容器（代码已实现，需在真实 Docker 主机验证）
- 能启动/停止
- 停止不删卷
- 重启控制面能对账
- 重复部署不创建第二个容器
- 容器标签正确
- 组件目录与包规格已进入部署请求；实际安装与锁定清单属于后续增量

### Phase 3：会话、Turn、事件、SSE

范围：

- `sessions` / `turns` / `events`
- `clientMessageId` 幂等
- 会话内串行、会话间并行
- `snapshot`
- SSE `afterSeq` 补事件
- 事件批量 append；模型流按 200 字符或 50ms 窗口落库
- 消息最终落 `messages`

当前实现说明：Phase 3 的占位执行器已被 Phase 4 的真实执行器替换；没有模型绑定、凭据无效或 provider 不支持时会明确失败，不会静默回退。审批列表在快照里仍为空。

验收：

- 发消息立即返回
- 同一会话两条消息按顺序执行
- 不同会话可并行
- App 断线重连不丢已提交事件
- App 关闭后 Turn 继续（真实执行器复用同一队列和事件持久化）
- 快照能恢复当前 Turn/队列/消息

### Phase 4：Agent 运行时与模型

范围：

- `TurnExecutor`
- `ModelGateway`
- `SecretVault`
- `provider_credentials` / `model_bindings`
- 本地/云端凭据隔离
- 流式文本
- 工具调用解析
- usage 记录
- 错误映射

验收：

- 云端 Key 不出现在容器、事件、日志、App
- 模型流式输出到 App
- 429/5xx 有限重试
- 凭据无效明确报错
- Turn 结束后释放密钥租约

### Phase 5：工具执行与审批

范围：

- Docker Engine API `exec`
- `ToolExecutor`
- stdout/stderr 流式
- 超时杀进程组
- 大输出落文件
- `tool.started` / `tool.completed`
- 审批请求、决定、超时
- 检查点与 `TurnResumer`

验收：

- 能执行 shell 命令
- 命令输出流式显示
- 超时能杀掉进程组
- 审批等待不占 worker
- 审批超时自动拒绝
- 工具执行中崩溃标记“可能已执行”
- 重启后 `waiting_approval` 保留

当前实现边界：

- 已完成：Docker exec、`shell.exec` 校验/风险分级、审批 API、工具日志、工具事件、模型 tool_calls 循环
- 已完成：`turn_checkpoints` 与 `WAITING_APPROVAL` 状态，审批等待时调度 worker 释放，决定后重新入队并从检查点继续
- 已完成：待审批记录持久化，控制面重启后审批与检查点仍在；过期扫描每 15 秒执行
- 命令在容器内用 `sh -lc` 执行，`cwd` 限定在 `/workspace`
- 默认只对 `DANGER` 走审批，审批默认 10 分钟，超时按 `EXPIRED` 处理
- 未完成：`turn_leases` 仅建表未使用，多控制面实例租约协调未实现
- 未完成：真实 Docker 环境验证、stderr 独立 `outputRef`、审批 UI

### Phase 6：通知

范围：

- `notification_events`
- `notification_deliveries`
- App 通知中心
- SMTP 配置
- 测试邮件
- 审批/失败/容器异常邮件
- 重试与失败记录

验收：

- 审批请求能发邮件
- 测试邮件可发送
- 邮件失败重试
- 邮件不含敏感正文
- 单设备已读游标正常

### Phase 7：工作区、技能、插件

范围：

- 宿主卷布局
- workspace / skills / plugins / tool-logs 挂载
- 默认技能初始化
- 技能索引缓存
- 脚本型插件安装与注册
- 插件按容器隔离

验收：

- 本地/云端路径语义一致
- 容器重建后卷还在
- 技能能被 Agent 读取
- 脚本插件能执行
- Android 原生插件标记不支持云端

### Phase 8：文件与终端

范围：

- 文件 API
- 流式上传下载
- Range 下载
- 路径校验
- 文件搜索
- 终端 PTY
- WebSocket attach / resize
- 终端 open 算 busy

验收：

- App 能浏览/编辑工作区文件
- 大文件不整块进内存
- 路径逃逸被拒绝
- 终端能开、能连、能关
- App 关闭终端继续
- 控制面重启终端结束

### Phase 9：App 接入

范围：

- 云端容器列表分组
- “部署云端容器”入口
- 七步部署向导，新增“访问方式”步骤（IP 测试 / 域名 + HTTPS）
- 服务器访问方式后端：`install.sh --domain`、`--access ip`、Caddy 反代、`/server/info` 访问字段
- 云会话 UI
- SSE 客户端
- 快照恢复
- 审批 UI
- 通知中心
- 文件/终端页面适配

验收：

- 本地/云端容器同页展示
- 云端容器状态准确
- 不填域名可完整走完部署，流程与旧版一致
- 合法域名且 DNS 正确时，部署完成后 App 自动使用 `https://<域名>`
- 域名格式非法不能进入下一步；DNS 未生效能看到原因，不无限转圈
- 证书失败可一键回退 IP 测试，数据、卷和容器不丢
- 域名成功后控制面不再对公网暴露 8787
- App 不保存 DNS 服务商 Token
- 云会话可发消息、看进度
- App 杀掉重开能恢复
- 审批可批准/拒绝
- 不破坏现有 SSH 流程

## 3. 第一批 MVP 边界

### 做

- 单设备
- 单云服务器
- 单默认工作区
- 一个容器一个 Docker 实例
- 一个模型绑定
- 一个 shell 工具
- 审批 + 邮件
- 事件 + SSE
- 文件 + 终端基础能力

### 不做

- 多设备
- 原生推送
- 多租户
- 跨容器卷共享
- 原生 Android 插件云端运行
- 常驻插件进程
- 镜像升级自动迁移
- 多工作区 UI
- 断点续传
- 文件版本历史
- tmux 持久终端
- 自动重放有副作用工具

## 4. 与现有代码的接入顺序

不要先改一堆现有页面。

建议：

1. 先独立实现 `minibot-control`，不改 App
2. 用 curl / 测试脚本跑通 API
3. 再接 App 的 `CloudSessionSource`
4. 最后把云容器接入“容器与镜像”列表

App 侧接入点：

```text
ExecutionBackendRegistry
└─ 增加 CloudExecutionBackend

SessionSource
└─ 增加 CloudSessionSource

Workflow
└─ CloudWorkflowClient，只做远端事件桥
```

约束：

- 云端不进入本地 `AgentRunManager` 的 `sessionJobs`
- 云端不维护第二份 Room 消息副本
- 云端队列和持久化在控制面
- 现有 SSH 流程不改

## 5. 测试策略

遵循现有偏好：

- 本地只跑单测，不做全量编译
- 全量编译和打包只在 GitHub Actions
- 只交付 arm64 真机包

测试分层：

### 单元测试

- seq 分配
- 幂等
- 状态机
- 路径校验
- 错误映射
- 事件批量 flush

### 集成测试

在 GitHub Actions 跑：

- PostgreSQL migration
- Docker 容器启动/停止
- Turn 执行
- SSE 断线补发
- 审批超时
- 邮件 outbox
- 文件 API 路径逃逸

### 端到端

- 一台真实服务器
- 一个云容器
- 一条消息
- 一个工具
- 一次审批
- 一次 App 重开恢复

## 6. 验收清单

- [ ] 云服务器一条命令安装
- [ ] App 自动配对
- [ ] 云容器创建/启动/停止
- [ ] 停止不丢卷
- [ ] 创建会话
- [ ] 发消息立即返回
- [ ] App 关闭后任务继续
- [ ] 重开 App 恢复进度
- [ ] 事件断线补发
- [ ] 模型 Key 不进容器
- [ ] shell 工具执行
- [ ] 审批邮件
- [ ] 审批超时拒绝
- [ ] 控制面崩溃恢复
- [ ] 文件浏览
- [ ] 终端打开/关闭
- [ ] 现有 SSH 流程无回归

## 7. 主要风险

| 风险 | 处理 |
| --- | --- |
| 云端 Agent 行为和本地不一致 | 复用现有 Agent 领域逻辑，抽取差异接口 |
| rootfs 持久化与镜像升级冲突 | 第一版不做自动迁移，明确“重建丢系统包” |
| 邮件到达慢导致审批超时 | 默认 10 分钟，纯邮件建议 30 分钟 |
| 事件写放大 | 50ms/200 字符批量，payload 限长 |
| 工具崩溃后副作用不确定 | `tool.started` 先落库，不自动重放 |
| 终端忘记关闭导致容器不休眠 | UI 明确提示，终端 open 算 busy |
| App 架构被云端拖乱 | 云端走 CloudSessionSource，不塞进本地 AgentRunManager |
| 数据库性能 | PostgreSQL + 少索引 + 检索独立表 |
| 用户服务器环境差异 | Ubuntu/Debian 正式验证，其余适配 |

## 8. 待确认项

这些不阻塞 Phase 0，但要在对应阶段前定：

- 官方组件具体版本（JDK 17/21、Android SDK、Flutter/Node/Python/Go/Rust）
- 主机系统正式验证矩阵
- 模型凭据添加时是否允许“待分配 Key”暂存
- 云容器列表分组的具体 UI
- 邮件默认收件人数量
- `pinned` 在 UI 上的表达
- 服务器资源预设的小/中/大具体数值
- 终端最大滚动缓冲和超时策略

## 9. 建议提交顺序

```text
docs: 云端运行时设计文档
feat(control): 控制面骨架与配对
feat(control): PostgreSQL migration 与容器管理
feat(control): 会话/Turn/事件/SSE
feat(control): Agent 运行时与模型网关
feat(control): 工具执行与审批
feat(control): 通知与邮件
feat(control): 工作区/技能/插件
feat(control): 文件与终端
feat(app): 云容器列表与部署入口
feat(app): 云会话与通知中心
test: 云端链路集成测试
```

不要把这些揉成一个巨型提交。
