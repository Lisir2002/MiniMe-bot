# Mini-AIBOT

Mini-AIBOT 是运行在 Android 上的 AI 编程助手，把对话、文件编辑、终端和 Linux 开发环境放在同一台手机里。它不是云端 IDE，也不内置模型：配置自己的模型服务后，就可以在设备本地或远程服务器上读写代码、执行命令和完成构建。

> Mini-AIBOT 是 [jieapi/AiCode](https://github.com/jieapi/AiCode) 的下游分支，不是上游官方版本。应用名、包名、发布流程和内置文档已按本项目调整，上游的 GPL-3.0 许可证与版权声明完整保留。

## 用途

- 在手机上完成轻量代码修改、调试、Git 操作和项目构建。
- 通过内置 Alpine Linux 容器运行 Shell、包管理器与开发工具。
- 连接远程 SSH 服务器，把手机作为远程项目的移动终端。
- 使用可配置的 AI 供应商完成对话、代码生成、文件操作和工具调用。

## 主要能力

- AI 对话、流式输出、Markdown 渲染与长上下文压缩。
- 文件读写、代码编辑、Shell 执行、后台终端、网页搜索与图片识别。
- 内置浏览器，可查看网页和本地文件。
- 本地容器与远程 SSH 两种执行环境。
- Git 仓库查看、提交、分支与远程操作。
- MCP、技能、子代理、检查点与撤销、工具授权。
- 完全离线的内置使用手册，含用户协议与隐私政策。
- 支持 OpenAI、Anthropic、Gemini 兼容协议及自定义供应商。

## 与上游的关系

本项目基于 AiCode 二次开发，主要下游改动包括：

- 应用名改为 Mini-AIBOT，包名改为 `com.mini.bot`，容器目录与 CLI 名称同步调整。
- 改用本仓库的 GitHub Actions 发布流程，构建 arm64 真机包并进行签名、包名和体积校验。
- 内置手册改为完全离线，并增加下游版用户协议与隐私政策。
- 关于页和项目入口指向本仓库；上游致谢卡片保留。
- 在上游代码之上维护插件兼容层和本地化调整。

上游文档和部分代码语义仍保留，感谢上游作者与贡献者。上游项目地址：<https://github.com/jieapi/AiCode>。

## 安装

- 系统要求：Android 8.0 及以上，arm64 设备。
- 从 [Releases](https://github.com/Lisir2002/MiniMe-bot/releases) 下载 APK 安装。
- 首次启动后，在设置中配置 AI 供应商的模型、API Key 和端点。应用不提供内置模型或免费额度。

## 构建

需要 JDK 17 与 Android SDK。调试构建可在本地执行：

```bash
./gradlew :app:assembleArmsoloDebug
```

Release 构建由 GitHub Actions 完成，使用仓库 Secrets 注入签名信息，不在本地生成正式包。工作流位于 `.github/workflows/android-release.yml`。

## 许可证与致谢

本项目依据 [GPL-3.0](LICENSE) 发布。你可以按许可证运行、研究、修改和再分发代码。

感谢 [jieapi/AiCode](https://github.com/jieapi/AiCode) 的作者与所有贡献者提供上游实现和文档；感谢所有为本项目提交代码、报告问题和改进文档的人。

## 链接

- 仓库：<https://github.com/Lisir2002/MiniMe-bot>
- 问题反馈：<https://github.com/Lisir2002/MiniMe-bot/issues>
- 上游项目：<https://github.com/jieapi/AiCode>
