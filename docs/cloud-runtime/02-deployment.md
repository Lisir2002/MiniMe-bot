# 02 部署与服务器接入

## 1. 部署入口

现有“容器与镜像”页顶部增加独立入口：

```text
[ 部署云端容器 ]
```

进入全屏向导。

现有 SSH 的“添加远程连接”流程完全不动。  
云端部署是一套独立流程，不共用现有 SSH 表单。

## 2. 服务器接入

### 2.1 安装命令

控制面发布资产由 `.github/workflows/control-plane-release.yml` 在 `control-plane-v*` 标签上生成。发布后的推荐安装方式是：

```bash
curl -fsSL https://github.com/Lisir2002/MiniMe-bot/releases/download/control-plane-v0.1.0/install.sh | \
  sudo bash -s -- \
  --dist-url https://github.com/Lisir2002/MiniMe-bot/releases/download/control-plane-v0.1.0/minibot-control-dist.tar.gz \
  --access ip
```

需要 HTTPS 时把 `--access ip` 换成：

```bash
--domain example.com
```

安装器会下载分发包，校验同目录的 `.sha256`，解压后安装；不需要提前上传二进制或服务单元文件。App 的“连接控制面”页会根据当前地址生成并复制这条命令：IP 地址用 `--access ip`，`https://域名` 用 `--domain 域名`。

在还没有 Release 的本地开发环境，仍可直接使用仓库里的安装器：

```bash
sudo ./deploy/install.sh \
  --binary /opt/minibot-dist/minibot-control/bin/minibot-control \
  --bootstrap "$(openssl rand -hex 24)" --access ip
```

安装器会打印这一次性 bootstrap token，并写入 `/var/lib/minibot/bootstrap.token`；App 配对页输入该令牌完成配对。域名参数和访问方式说明见 [10-domain-binding.md](10-domain-binding.md)。

安装器做：

- 检查 Docker / 系统版本 / 端口 / 磁盘 / TLS
- 生成服务器 ID
- 初始化 PostgreSQL
- 安装 `minibot-control` systemd 服务
- 创建 `minibot` 服务用户时固定 UID/GID `1000:1000`，与基础镜像执行用户对齐；端口/UID 冲突时安装器直接报错
- 输出服务器地址、证书指纹
- 进入“等待认领”

安装幂等，重复执行为升级。

### 2.1.1 直连 HTTP 测试模式

控制面默认只监听 `127.0.0.1:8787`。测试阶段如果不接 HTTPS，可让控制面监听所有网卡：

```bash
sudo MINIBOT_HOST=0.0.0.0 MINIBOT_PORT=8787 ./deploy/install.sh --binary ...
```

或直接修改 `/etc/minibot/control.env`：

```text
MINIBOT_HOST=0.0.0.0
MINIBOT_PORT=8787
```

然后放行服务器防火墙和云厂商安全组的 `8787/tcp`，App 使用 `http://<服务器公网IP>:8787`。

注意：明文 HTTP 会暴露设备 Token、会话内容和工具输出，只适合测试；正式环境必须加 HTTPS 反向代理，或用安全组限制来源 IP。

### 2.2 配对

当前实现走“安装器输出一次性令牌，App 输入令牌完成配对”：

```text
服务器安装/重装
→ 安装器打印 bootstrap token，并写入 /var/lib/minibot/bootstrap.token
→ App 配对页输入令牌
→ POST /api/v1/pair/claim，提交设备 EC P-256 公钥
→ 服务器绑定设备，返回 device token
→ App 加密保存 device token，后续请求带 Bearer
```

- 第一台设备即管理员，第一版只支持单设备。
- 令牌使用一次后失效；服务器已有配对设备时返回 `device_already_paired`，需先在服务器重置配对。
- 换设备/重装通过服务器本地 CLI 重置配对，或一次性转移码。
- “App 生成令牌 + 安装命令内嵌 + 轮询自动配对”尚未实现，是后续安装命令生成的一部分。

### 2.3 域名与证书

域名是部署向导中的可选“访问方式”，不是必经步骤。完整交互、校验规则和失败降级见 [10-domain-binding.md](10-domain-binding.md)。

- 没有域名：使用 `http://<服务器IP>:8787` 测试，安装器和 App 都持续标记明文风险。
- 有域名：安装器接收 `--domain`，将控制面收回 `127.0.0.1:8787`，由 Caddy 监听 80/443、自动申请和续期证书。
- 证书申请在服务器完成，App 不收集 DNS 服务商 API Token。
- 第一版只支持手动添加 A/AAAA 记录；DNS 未生效时可以继续安装，安装后再检测 HTTPS。
- 域名失败不自动降级；用户点击“改用 IP 测试”后，安装器以 `--access ip` 显式回退，数据和容器保留。
- 域名成功后公网不再暴露 8787，App 使用 `https://<域名>` 访问。

## 3. 部署向导七步

```text
1. 选择服务器
2. 访问方式
3. 基础镜像
4. 初始化环境
5. 资源与策略
6. 确认
7. 部署进度
```

### 第一步：选择服务器

- 已接入服务器列表
- “首次接入服务器”分支
- 显示服务器状态、架构、磁盘、Docker 版本

### 第二步：访问方式

可选步骤，不阻塞没有域名的用户。

```text
先用 IP 测试（默认）
绑定域名并启用 HTTPS
```

- IP 测试：使用 `http://<服务器IP>:8787`，显示明文风险。
- 域名：输入 FQDN，展示 `A <域名> → <服务器IP>`，支持检测 DNS 和安装后检测 HTTPS。
- 域名格式非法时不能进入下一步；DNS 未生效允许继续，但标黄。
- 选择结果决定安装命令带 `--domain` 还是 `--access ip`。
- 交互细节见 [10-domain-binding.md](10-domain-binding.md)。

### 第三步：基础镜像

官方镜像：

```text
Alpine / Ubuntu / Debian
× amd64 / arm64
```

用户不真正构建镜像，只选择官方镜像。

默认推荐：

```text
Debian stable + amd64/arm64 对应架构
```

### 第四步：初始化环境

分“官方组件”和“自定义包”两个区。

官方组件：

- Node
- Python
- JDK
- Android SDK
- Flutter
- Go
- Rust
- PHP

默认预设：精简。

用户可自由选择组件，也允许自定义包。

### 第五步：资源与策略

- 小 / 中 / 大预设
- 自由填写 CPU / 内存 / 磁盘
- 空闲策略
- `pinned`
- 审批超时
- 网络策略
- 通知配置（见第 6 节）

### 第六步：确认

- 镜像 digest
- 组件清单
- 资源配额
- 卷布局
- 通知配置
- 预估磁盘占用

### 第七步：部署进度

- 拉取镜像
- 创建卷
- 创建容器
- 安装组件
- 启动控制面连接
- 健康检查

失败可重试，不重复创建。

## 4. 官方镜像库

### 4.1 原则

普通用户不构建镜像，只选择官方镜像和组件后一键部署。  
镜像由官方 CI 集中构建，用户服务器只拉取。

### 4.2 构建链路

```text
GitHub 源码
→ GitHub Actions
→ GHCR 多架构镜像
→ rootfs tar（供本地容器）
```

- 按 digest 拉取
- 签名校验
- 生成 SBOM
- 漏洞扫描
- 多架构 manifest

### 4.3 基础镜像矩阵

```text
Alpine × amd64/arm64
Ubuntu × amd64/arm64
Debian × amd64/arm64
```

不默认全量 apt。  
组件在部署时按需安装。

### 4.4 当前发布状态

截至 2026-11-24，GHCR 只发布了 `ghcr.io/lisir2002/minibot-base:alpine-amd64`，其余五个矩阵镜像尚未发布。该包当前是私有包，部署服务器必须配置 `MINIBOT_REGISTRY_*` 凭据；匿名拉取会失败。等 CI 补齐六个镜像并改为公开包后，普通用户才能直接选择任意镜像部署。

## 5. 包管理

支持多生态、公开仓库、全局安装：

```text
apt / apk
npm / pnpm / yarn
pip / uv
cargo
gem
composer
go
```

规则：

- 只允许公开源
- 不支持私有仓库和 Token
- 部署页只做全局安装
- 项目依赖留在工作区
- 包安全由用户自担
- 平台只接受结构化包规格，不拼接 shell
- 安装限制在容器内

安装顺序：

```text
系统包 → 语言运行时 → 语言包
```

安装后生成锁定清单：

- 支持部分失败
- 支持重试
- 支持追加安装
- 不承诺可靠卸载单包
- 需要干净移除时重置环境

公共源可用镜像加速；依赖私有源的包会明确失败。

## 6. 通知配置

放在第五步“资源与策略”：

```text
App 内通知       默认开启
邮件通知         默认关闭
SMTP 主机/端口/加密/账号/授权码/发件人/收件人
邮件事件：审批、失败、容器异常、Turn 完成
审批超时         默认 10 分钟
```

- App 内通知旁注明：仅 App 运行时可见
- 只启用邮件时，审批超时小于 15 分钟给提示，建议 30 分钟
- SMTP 密码只提交、不回显
- 部署完成后提供“发送测试邮件”
- 测试失败不影响部署成功

## 7. 主机系统支持

正式验证：

```text
Ubuntu / Debian
```

适配：

```text
Rocky / Alma / Fedora
```

尽力兼容：

```text
openSUSE / Arch
```

Alpine 宿主只提供 Docker Compose 手动模式。

## 8. 部署产物

服务器上：

```text
/etc/minibot/config.yaml
/var/lib/minibot/
├─ master.key
├─ postgres/
├─ volumes/<profileId>/
└─ logs/
```

- `master.key` 权限 0600
- 数据库只监听本机
- 控制面 systemd 管理
- TLS 由 Caddy 终止，控制面监听 localhost

## 9. 升级与回滚

- 控制面升级不重建容器
- 数据库迁移先备份
- 镜像升级默认不自动重建容器
- 需要重建时保留 workspace / skills / plugins 卷
- rootfs 里用户安装的系统包在重建时丢失，必须提示
