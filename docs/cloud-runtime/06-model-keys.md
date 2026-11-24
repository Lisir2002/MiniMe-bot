# 06 模型与密钥

## 1. 四层模型

```text
模型定义      provider、model、能力、上下文、价格
供应商凭据    某个 provider 的 Key，分本地/云端
模型绑定      模型 + 凭据绑到某个容器
模型调用      控制面运行时解密凭据，调 provider
```

不要把四层揉成一个“模型配置”。

## 2. 凭据

```text
provider_credentials
├─ id
├─ scope          LOCAL_APP / CLOUD_SERVER
├─ provider
├─ base_url
├─ encrypted_secret
├─ status         unassigned / active / invalid / revoked
├─ key_version
└─ created_at
```

规则：

- 本地凭据存在 App 私有存储
- 云端凭据存在服务器密钥库
- 同一个 provider 可以有两份凭据，本地一份、云端一份
- 云端绑定只能用云端凭据
- 本地绑定只能用本地凭据
- 不允许跨环境借用

## 3. 绑定

```text
model_bindings
├─ id
├─ profile_id
├─ model_definition_id
├─ credential_id
├─ status
└─ created_at
```

绑定决定“这个容器里这个模型能不能用”。

- 一个模型可以绑定多个容器
- 一条绑定对应一个容器
- 聊天模型选择器只显示当前容器中状态可用的绑定
- 不允许跨容器临时切模型

## 4. Key 存在哪

### 本地

- Android Keystore / 加密存储
- 只在本地容器运行时解密
- 云端任务不会读它

### 云端

- 安装时生成服务器主密钥：

```text
/var/lib/minibot/master.key
权限 0600
不进数据库
不进普通备份
```

- 凭据用 AES-256-GCM 加密后存 PostgreSQL
- 每条记录独立 nonce
- 主密钥丢失，云端凭据不可恢复

第一版不做 KMS，也不把主密钥放环境变量。

## 5. 解密时机

不要每次模型请求都解密。

一个 Turn 开始：

```text
取绑定
→ 解密凭据
→ 放进 SecretLease
→ 整个 Turn 复用
→ Turn 结束释放
```

内存只保留最小必要时间。  
不写日志，不进事件，不进容器。

## 6. 模型调用路径

```text
TurnExecutor
→ ModelBindingResolver
   ├─ 校验绑定属于当前容器
   ├─ 校验凭据 scope = CLOUD_SERVER
   └─ 校验状态 active
→ SecretVault.withSecret(credentialId)
→ ModelGateway.call(...)
   ├─ ProviderAdapter
   ├─ HTTP/2 + 连接池
   ├─ 流式解析
   ├─ 工具调用解析
   └─ usage 统计
→ EventStore 写 message.delta
→ SSE 推 App
```

App 不参与模型请求。  
App 关闭后这条链路照常跑。

## 7. 模型网关

`ModelGateway` 是控制面内部模块，不是对外服务，不开端口。

职责：

- 选择 provider adapter
- 拼请求
- 流式读取
- 解析文本增量
- 解析工具调用增量
- 统计 token 和耗时
- 映射错误
- 限流和重试

Provider 形态：

```text
OpenAI-compatible
Anthropic
Gemini
自定义 base_url
```

不要为每个 provider 写一套 Agent 逻辑，只写请求/响应适配。

## 8. 流式

- 模型 SSE 进来就解析
- 文本增量进 `EventBuffer`
- 50ms 或 200 字符批量落库
- 工具调用参数完整后再触发工具
- 不在模型流线程里跑工具

## 9. 超时与重试

分三段：

```text
连接超时        10 秒
首字节超时      30 秒
流空闲超时      60 秒
```

只要还在持续出 token，就不算超时。

重试：

- 还没收到任何 token：429、5xx、网络错误可以重试 2 次，指数退避
- 已经开始输出：不自动重试，标记 Turn 失败或中断
- 401/403：凭据无效，标记 `invalid`，不重试
- 上下文超长：明确报错，不偷偷截断到用户不知道
- 内容过滤：把 provider 拒绝原因转成可读错误

重试要写事件，App 能看到“正在重试”。

## 10. 限流

按凭据和 provider 做令牌桶：

```text
每分钟请求数
并发请求数
429 退避
```

一个会话被限流，不应卡死整个服务器所有会话。  
限流是 per credential，不是全局。

## 11. 上下文

- 历史消息按 token 预算裁剪
- 工具输出默认截断到 32KB
- 大输出给日志路径，让 Agent 分段读
- 技能提示词按需加载
- 图片、附件按模型能力处理

裁剪策略和本地 Agent 保持一致，不能云端一套、本地一套。

## 12. 错误映射

统一错误码：

```text
auth_failed
rate_limited
context_too_long
network_error
provider_error
content_filtered
model_not_supported
binding_unavailable
```

Provider 返回体可能带 Key 或内部信息，必须脱敏后再落库。

日志只记：

```text
request_id
provider
model
status
latency
token_usage
```

默认不记完整 prompt。

## 13. 凭据生命周期

### 添加

- 用户添加 provider 凭据，选择本地或云端
- 选云端时，App 通过 HTTPS 上传，服务器加密存储
- App 只保留“已上传”状态，不保留云端明文
- 未绑定前状态 `unassigned`，不能用于调用

### 绑定

- 把模型 + 凭据绑到容器
- 云端绑定时校验 scope
- 绑定成功后才在聊天模型选择器里出现

### 轮换

- 更新凭据生成新的 `key_version`
- 正在跑的 Turn 用旧版本直到结束
- 新 Turn 用新版本
- 旧版本在无活跃请求后清理

### 删除

- 删除绑定不立即删凭据
- 最后一条使用该凭据的绑定删除后，进入待回收
- 有活跃请求则等结束
- 云服务器离线时标记“待同步删除”，不假装成功
- 模型删除级联解绑，规则和之前一致

## 14. 安全红线

- 云端 Key 不回传 App
- API 不返回明文，只返回 `hasSecret`
- Key 不进容器环境变量
- Key 不进事件、日志、邮件、错误信息
- Key 不进 prompt
- 模型请求只从控制面发出
- 容器不能访问控制面数据库
- 控制面不暴露模型代理端口

## 15. 性能

- 模型流直接进事件缓冲，不做二次落盘
- 连接池复用，不每 Turn 新建 TLS
- 不在模型流线程里跑工具
- 不在模型流线程里发邮件
- token 统计用 provider usage，不自己全量重算
- 上下文裁剪在请求前做
- 搜索、历史查询不参与模型调用链路

## 16. 第一版取舍

做：

- 云端凭据加密存储
- 本地/云端凭据隔离
- 绑定校验
- 流式调用
- 首字节/空闲超时
- 429/5xx 有限重试
- 错误脱敏
- 凭据轮换和最后回收
- usage 记录

不做：

- KMS
- 多租户凭据共享
- 云端 Key 回传
- 自动跨 provider 降级
- 模型代理对外服务
- 提示词全文审计日志

## 17. Phase 4 当前实现边界

已落地：

- 云端凭据 AES-256-GCM 加密存 PostgreSQL，主密钥 0600
- 凭据创建后 `UNASSIGNED`，绑定成功后 `ACTIVE`
- 模型定义、容器级绑定、绑定归属校验
- OpenAI-compatible 流式调用与 `message.delta` 落库
- 首字节 30 秒超时、连接 10 秒超时
- 未收到 token 前的 429/5xx 指数退避重试，最多 2 次
- 401/403 标记凭据 `INVALID`
- usage 与耗时记录到 `model_calls`
- API 只返回 `hasSecret`，不返回明文

尚未实现：

- Anthropic、Gemini 原生适配器
- 凭据轮换、最后一条绑定删除后的密钥回收
- 流空闲 60 秒超时
- 工具调用增量解析与执行
- 多租户、KMS、跨 provider 自动降级
