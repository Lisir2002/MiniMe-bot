# 07 工作区、文件、终端与通知

## 1. 统一容器内路径

本地和云端都固定成：

```text
/workspace                         当前工作区
/home/minibot/.minibot/skills      全局技能
/home/minibot/.minibot/plugins     全局插件
/home/minibot/.minibot/logs        工具日志
```

项目级技能：

```text
/workspace/.minibot/skills
```

容器内统一非 root 用户，比如 `minibot`。

## 2. 云端宿主目录

```text
/var/lib/minibot/volumes/<profileId>/
├─ workspace/
│  └─ .minibot/skills/
├─ home/
│  └─ .minibot/
│     ├─ skills/
│     └─ plugins/
└─ tool-logs/
```

挂载：

```text
宿主 workspace        → /workspace                读写
宿主 home/.minibot    → /home/minibot/.minibot   读写
宿主 tool-logs        → /home/minibot/.minibot/logs  只读
```

不挂：

- 宿主根目录
- Docker socket
- 控制面数据库目录
- 模型 Key 文件
- 其他容器的卷

## 3. 工作区

第一版：

```text
一个容器一个默认工作区
多个会话共享这个工作区
```

数据模型保留多工作区字段，UI 先单工作区。

工作区是持久卷：

- 容器 stop 不丢
- 删实例不删卷
- 归档/彻底删除才处理数据

会话只记录自己属于哪个工作区，不复制文件。

## 4. 技能

分两层：

```text
全局技能    /home/minibot/.minibot/skills
项目技能    /workspace/.minibot/skills
```

来源：

- 官方内置技能，安装时初始化到全局卷
- 用户从 App 安装的技能，写入全局卷
- 项目自带技能，放工作区 `.minibot/skills`

控制面读取技能文件，拼进提示词；技能里的脚本通过 ToolExecutor 在容器里执行。

缓存：

- 按文件 hash 缓存技能索引
- 目录 mtime 变化再重扫
- 提示词按需加载，不全部注入

初始化：

```text
镜像内 /opt/minibot/defaults/skills
→ 首次创建时，如果宿主 skills 卷为空，复制默认技能
→ 已有内容不覆盖
→ 记录默认包版本
```

## 5. 插件

路径：

```text
/home/minibot/.minibot/plugins
```

插件类型：

- 脚本型 / CLI 型：可以云端跑，通过 ToolExecutor 执行
- 依赖 Android API 的插件：纯云端不可用，UI 标记“不支持云端”
- 常驻进程型插件：第一版不做

第一版插件形态：

```text
plugin/
├─ manifest.json
├─ scripts/
└─ data/
```

安装：

1. App 上传插件包
2. 控制面校验 manifest
3. 解压到当前容器 plugins 卷
4. 记录安装信息到服务器数据库
5. 插件工具注册到 ToolExecutor

插件按容器隔离，不跨容器共享。

## 6. 文件 API

原则：

> App 只操作工作区，不操作宿主文件系统。

所有路径都是工作区相对路径：

```text
/workspace/...
```

不允许：

- 绝对路径
- `..`
- 跳出 workspace 的符号链接
- 访问 `/var/lib/minibot` 其他目录
- 访问 Docker socket、数据库、Key

接口：

```text
GET    /api/v1/workspaces/{id}/files?path=/
GET    /api/v1/workspaces/{id}/files/stat?path=...
GET    /api/v1/workspaces/{id}/files/content?path=...&offset=&limit=
PUT    /api/v1/workspaces/{id}/files/content?path=...
POST   /api/v1/workspaces/{id}/files/mkdir
POST   /api/v1/workspaces/{id}/files/move
DELETE /api/v1/workspaces/{id}/files?path=...
POST   /api/v1/workspaces/{id}/files/upload
GET    /api/v1/workspaces/{id}/files/download
GET    /api/v1/workspaces/{id}/files/search?q=...
```

行为：

- 列表分页，返回名称、类型、大小、mtime
- 文本读取支持 offset/limit，不整文件进内存
- 下载支持 Range
- 上传流式写临时文件，校验后 rename
- 写入用临时文件 + fsync + rename
- 返回 ETag，写入带 `If-Match` 做冲突检测
- 大文件不 base64，直接流式

安全：

- 路径规范化后必须仍在 workspace 内
- 写入用 `O_NOFOLLOW`，防止符号链接逃逸
- 软链接目标越界直接拒绝
- 单文件大小、总配额、并发上传数限制
- 文件 API 不能执行命令

## 7. 文件搜索

第一版用 `ripgrep`：

```text
rg --json --hidden --glob '!.git' ...
```

- 在容器里执行，或宿主直接对 workspace 卷执行
- 限制返回条数、单条长度、总耗时
- 支持文件名搜索和内容搜索
- 二进制文件跳过
- 结果给路径、行号、片段

聊天记录检索走 PostgreSQL `message_search`，和文件搜索分开。

## 8. 终端

终端和 Agent 工具调用不是一回事。

- Agent 工具自动执行、可审批
- 终端用户手动输入，用户自己负责
- 终端不写会话事件表
- 终端不进聊天记录
- 终端不触发 Agent

### 模型

```text
TerminalSession
├─ id
├─ profileId
├─ containerId
├─ status        open / attached / closed
├─ cols / rows
├─ createdAt
├─ lastActivityAt
└─ scrollback
```

每个终端是一个服务端 PTY：

```text
docker exec --tty --interactive <container> /bin/bash
```

控制面持有连接，App 通过 WebSocket 连上来。

### 接口

```text
POST   /api/v1/containers/{id}/terminals
GET    /api/v1/containers/{id}/terminals
WS     /api/v1/terminals/{terminalId}/attach
POST   /api/v1/terminals/{terminalId}/resize
DELETE /api/v1/terminals/{terminalId}
```

消息：

```text
client → input  { data }
client → resize { cols, rows }
server → output { data }
server → exit   { code }
```

### App 关闭

- App 关闭：终端进程继续在服务器跑，重新打开可以 attach
- 控制面重启：第一版终端结束，不恢复
- 第一版不引入 tmux

### 回收

终端 open 就算容器 busy，不自动停容器。  
用户必须显式关闭终端，或者关闭容器。

提示：

> 开着终端，容器不会休眠。

### 并发

```text
每个容器最多 2 个终端
终端和 Agent 工具可以同时跑
不额外加文件锁
```

### 安全

- 只在容器内跑，不碰宿主
- 不注入模型 Key
- 不做命令审批，终端就是用户自己操作
- WebSocket 必须带设备令牌
- 同一终端同时只允许一个 App 连接，新连接踢掉旧连接
- 终端输出不进邮件、不进推送、不进会话事件
- 滚动缓冲上限 256KB
- 不做终端录制

## 9. 通知

第一版只做：

```text
App 内通知
邮件通知
```

原生 FCM/APNs 后续可选。

### App 通知的边界

- App 在前台：实时事件、应用内提示
- App 在后台但进程活着：Android 可发本地通知；iOS 不保证
- App 被系统杀掉：收不到 App 通知，只能靠邮件
- 重新打开 App：通知中心补全

明确：

> App 通知不是可靠推送，只是 App 活着时的提示。  
> App 关闭后能到达的，只有邮件。

### 邮件

SMTP 配置：

```text
host
port
security SSL/STARTTLS
username
encrypted_password
from_address
to_addresses
```

规则：

- 用 465/587 提交端口，不依赖 25
- 支持常见邮箱授权码
- 配置后“发送测试邮件”
- SMTP 密码加密存储，不进日志
- 发送失败重试，不静默丢失

邮件事件默认：

```text
审批请求     发
Turn 失败    发
容器异常     发
Turn 完成    默认不发
普通消息     不发
```

邮件内容只放：

```text
服务器名
会话标题
事件类型
操作摘要
时间
打开 App 的深链
```

不放：

- 聊天正文
- 完整命令
- 模型 Key
- 文件内容
- 审批令牌

审批不能在邮件里直接批准。  
邮件只负责人回来，批准/拒绝必须在 App 完成。

### 超时

- 默认 10 分钟
- 纯邮件部署建议 30 分钟
- 部署向导里如果只启用邮件且超时小于 15 分钟，给提示
- 超时由服务端 reaper 处理

### 单设备

第一版不做多设备：

- 不建设备列表、邀请、撤销
- 不做每设备已读游标
- 不做多设备审批竞争
- 只保留一个设备凭据、一个已读游标

审批：

- 一个审批一个决定
- 重复提交幂等
- 超时自动拒绝
