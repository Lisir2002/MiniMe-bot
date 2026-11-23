# Mini-AIBOT

Mini-AIBOT is an AI coding assistant for Android. It combines chat, file editing, a terminal and a Linux development environment in one app. It is not a cloud IDE and does not bundle models: after configuring your own model provider, you can read and edit code, run commands and build projects on the device or on a remote server.

> Mini-AIBOT is a downstream fork of [jieapi/AiCode](https://github.com/jieapi/AiCode), not an official upstream release. The app name, package name, release pipeline and built-in documentation have been adapted for this project. The upstream GPL-3.0 license and copyright notices are retained.

## What it is for

- Make small code changes, debug, use Git and build projects on a phone.
- Run shells, package managers and development tools in a bundled Alpine Linux container.
- Connect to a remote SSH server and use the phone as a mobile terminal.
- Use a configurable AI provider for chat, code generation, file operations and tool calls.

## Features

- AI chat with streaming output, Markdown rendering and long-context compaction.
- File read/write, code editing, shell execution, background terminals, web search and image input.
- A built-in browser for web pages and local files.
- Local container and remote SSH execution backends.
- Git repository viewing, commits, branches and remotes.
- MCP, skills, subagents, checkpoints and undo, tool authorization.
- A fully offline built-in manual, including terms of use and privacy policy.
- OpenAI, Anthropic and Gemini compatible APIs, plus custom providers.

## Relationship to upstream

This project is based on AiCode. Main downstream changes:

- Renamed the app to Mini-AIBOT and the package to `com.mini.bot`, with matching container directory and CLI names.
- Switched to this repository's GitHub Actions release pipeline, building arm64 packages with signature, package name and size checks.
- Made the built-in manual fully offline and added downstream terms of use and privacy policy.
- Pointed the About page and project links to this repository; the upstream acknowledgement card is kept.
- Added a plugin compatibility layer and localization changes on top of upstream code.

Upstream documentation and some code semantics remain. Thanks to the upstream author and contributors. Upstream project: <https://github.com/jieapi/AiCode>.

## Install

- Android 8.0 or later, arm64 devices.
- Download the APK from [Releases](https://github.com/Lisir2002/MiniMe-bot/releases).
- On first launch, configure the model, API key and endpoint in Settings. No model or free quota is bundled.

## Build

JDK 17 and the Android SDK are required. Debug builds can be produced locally:

```bash
./gradlew :app:assembleArmsoloDebug
```

Release builds run in GitHub Actions with signing material injected from repository secrets. Official packages are not built locally. The workflow is `.github/workflows/android-release.yml`.

## License and acknowledgements

Released under [GPL-3.0](LICENSE). You may run, study, modify and redistribute the code under the terms of that license.

Thanks to the author and contributors of [jieapi/AiCode](https://github.com/jieapi/AiCode) for the upstream implementation and documentation, and to everyone who contributes code, reports issues or improves the docs for this project.

## Links

- Repository: <https://github.com/Lisir2002/MiniMe-bot>
- Issues: <https://github.com/Lisir2002/MiniMe-bot/issues>
- Upstream: <https://github.com/jieapi/AiCode>
