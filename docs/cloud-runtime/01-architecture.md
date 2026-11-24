# 01 总体架构

## 1. 目标

在 Mini-AIBOT 现有本地容器能力上，增加“用户自建纯云端”模式：

- 云端会话、命令执行、结果输出都在用户自己的云服务器上
- App 清后台后云端任务继续跑
- 重新打开 App 同步云端任务进度
- 用户在 App 配置的模型接口区分本地/云端，按容器性质读取不同数据
- App 发消息通过 HTTPS 传到云端
- 云容器统一纳入“容器与镜像”管理
- 新增独立“部署云端容器”流程，不改现有 SSH 流程

## 2. 非目标

第一版不做：

- 我们分发云服务器
- 多租户共享服务器
- 多设备同步
- 原生推送
- 云端与本地共享密钥
- 云端与本地共享同一个卷
- 跨容器复制环境

## 3. 核心对象

```text
ContainerProfile
├─ id
├─ kind            LOCAL / CLOUD
├─ name
├─ image
├─ resourcePolicy
├─ persistentVolumes
└─ createdAt

ContainerInstance
├─ id
├─ profileId
├─ containerId
├─ phase           provisioning/running/stopping/stopped/archived/error/deleting
├─ activity        activeTurns/activeTerminals/pendingApprovals/pinned/lastActivityAt
└─ lastError

Workspace
├─ id
├─ profileId
├─ name
└─ rootPath

Session
├─ id
├─ profileId
├─ workspaceId
├─ title
├─ modelBindingId
└─ lastSeq

ModelBinding
├─ id
├─ profileId
├─ modelDefinitionId
├─ credentialId
└─ status
```

容器是持久身份，实例可停可重建。

## 4. 本地与云端的关系

```text
本地容器：
  Agent 循环在 App
  容器在手机
  聊天数据库在 App Room
  模型凭据在 App 私有存储

云端容器：
  Agent 循环在控制面
  容器在用户服务器
  聊天数据在 PostgreSQL
  模型凭据在服务器密钥库
```

两边共享：

- `ContainerProfile` 抽象
- 容器内目录语义
- 会话/ Turn / 审批概念
- 技能与插件目录结构
- 文件与终端操作语义

两边不共享：

- 数据库
- 密钥
- 卷
- 事件流
- 队列

## 5. 云端服务器

用户自备服务器，产品只提供：

- 一条安装命令
- 控制面运行时
- App 连接协议
- 官方镜像库

控制面部署在用户服务器，App 永远不使用 SSH。

服务器接入流程见 [02-deployment.md](02-deployment.md)。

## 6. 生命周期

### phase

```text
provisioning   创建中
running        运行中
stopping       停止中
stopped        已停止
archived       已归档
error          异常
deleting       删除中
```

### activity

```text
activeTurns        执行中/排队中的 Turn
activeTerminals    打开的终端
pendingApprovals   待审批
pinned             用户要求常驻
lastActivityAt     最后活动时间
```

### 空闲策略

```text
10 分钟内        保温
10–30 分钟       停止
超过 7 天        归档
```

不停止条件：

- 有活跃 Turn
- 有待审批
- 有终端进程
- `pinned`

`pinned` 不限制数量，资源由用户服务器兜底。

## 7. 删除语义

区分“删实例”和“删档案”：

### 删实例

- 只 `stop`
- 不 `rm`
- rootfs 可写层保留
- workspace / skills / plugins 卷保留
- 重新恢复同一 profile 后，模型绑定自动重新应用

### 删档案

- 删除容器
- 删除卷
- 删除绑定
- 删除服务器侧密钥
- 不可恢复

UI 必须明确区分，不能一个“删除”按钮全干。

## 8. 部署形态

```text
每个 ContainerProfile 一个 Docker 容器
控制面管理 Docker Engine
容器不暴露入站端口
App 不直连容器
```

为什么不用 microVM：

- 热容器响应接近
- 成本最低
- 单机 8C/8G 可承载若干轻量容器
- microVM 隔离强，但运维和成本不划算

## 9. 与现有 SSH 的关系

现有远程 SSH 流程保持原样：

- 连接、凭据、创建、编辑、删除逻辑不动
- 不共用连接表
- 不共用主机指纹
- 不共用 SFTP 通道
- 不共用密钥

列表层可以做统一展示，通过适配器区分：

```text
LocalContainerAdapter
CloudContainerAdapter
SshContainerAdapter
```

## 10. 与现有插件三轴架构的关系

云端模式对应“远端已有完整运行体”：

```text
执行后端：CloudExecutionBackend
Agent 工作流：CloudWorkflowClient
会话源：CloudSessionSource
```

关键约束：

- 云端会话的队列和持久化在控制面
- App 不为云端会话维护第二份消息副本
- App 只保留展示缓存和 `lastSeq`
- 不把云端塞进本地 `AgentRunManager` 的 `sessionJobs` 队列
- 云端审批、事件、通知都以控制面为准

## 11. 成功标准

第一版完成时，用户应该能：

1. 在 App 里部署一台自己的云服务器控制面
2. 在“容器与镜像”里看到云容器
3. 创建云容器并选择镜像/组件
4. 在云容器里创建会话
5. 发一条消息，App 关闭后任务继续
6. 重新打开 App 看到完整进度
7. 模型 Key 只存在云端，不出现在容器和 App
8. 危险工具触发审批，邮件能通知到
9. 能在云端工作区浏览文件、开终端
10. 服务器重启后，已排队的 Turn 继续，执行中的 Turn 明确标记中断
