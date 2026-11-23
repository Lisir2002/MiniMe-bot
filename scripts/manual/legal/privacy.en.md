# Mini-AIBOT Privacy Policy

**Last updated: 23 November 2026**

Mini-AIBOT is the Android client of the MiniMe-bot project, maintained by Lisir2002. This policy explains how the app handles your information. The app is open source under GPL-3.0 and is a downstream fork of jieapi/AiCode.

**This project does not operate its own service backend, does not centrally collect or store your chats, workspace files or credentials, and does not sell this data.** Configuration and conversations are mainly stored on the device. However, AI, web search, remote servers, remote backup and similar features send relevant content to third-party services you choose. The app also makes network requests for updates and public configuration.

## 1. Accounts and collection

- There is no Mini-AIBOT account system. GitHub and other integrations may require you to sign in or authorize the service, and authorization credentials may be stored on the device.
- The app bundles no analytics, tracking, advertising or crash-reporting SDK. It does not automatically upload activity, chat content or logs for usage analysis or advertising.
- The app records operational and crash diagnostics locally. Local records are not automatically uploaded.
- The maintainer does not remotely read your device data through the app. If you submit an issue, copy crash information or share logs, that content goes to the recipient you choose; remove sensitive information first.

## 2. Where data is stored and what logs contain

Data handled by the app includes:

- **Configuration and credentials**: model providers, API keys, endpoints, and server, Git or backup connections and authorization details you configure.
- **Chats and AI context**: conversation history, attachments, project rules, memory and tool results.
- **Workspace and environment files**: code, files, container images and local Linux environment data.
- **Preferences, diagnostic logs and backup files**.

Configuration and conversations are mainly kept in app-private storage; logs may be kept in an app-specific external storage directory. The default local workspace is in app-private storage; an **external workspace** you select stays in that device directory, and a **remote workspace** stays on the target server. Exports and backups go where you choose and may not be in app-private storage.

**Logs can contain sensitive content.** AI request and response logs may record request URLs, conversation context, code, tool results, model replies and error details. Some media may be redacted, but text, URLs and tool output are not guaranteed to have passwords, tokens or personal information removed. Crash diagnostics may include device model, OS version, memory state and stack traces; they are not uploaded automatically.

Runtime and AI logs have size limits and cleanup for old files. Initialization removes matching log files that have not been updated for more than 7 days. This is not a promise that all data is deleted after 7 days: active logs, conversations, exported copies and backups have their own lifetimes. Deleting a chat does not necessarily delete generated logs or backups.

## 3. Network access and third-party transfers

### Basic requests

- **Public configuration and catalogs**: depending on cache state, the app fetches model metadata, provider presets and container image catalogs at startup. Requests may pass through GitHub and CDN nodes such as jsDelivr. These requests download public data and do not include chats, workspace content or API keys.
- **Update checks and downloads**: automatic update checks are on by default and can be disabled. Checks call the GitHub Releases API; downloads may use a third-party mirror configured in Settings, or go directly to GitHub if the mirror is changed or left empty.
- **Environments and networking**: downloading container images, installing packages or using development tools contacts the relevant mirrors, package repositories or target services. Proxy connectivity checks may also contact external test endpoints.

Those services and network intermediaries may receive IP address, request time, headers and resource paths. Not uploading chats does not mean no network information is created.

### Data sent when you use features

- **AI providers**: conversation content and related context are sent to the model service you select. Besides code, files and images you send explicitly, context may include automatically loaded project rules, memory and skill lists, plus files, command output and other results the AI reads through tools. Auxiliary AI features such as title generation and context compaction may also submit related content. Image generation uses the service you configure for it.
- **MCP, search and browser**: MCP servers, search services and web targets you enable receive request content according to their configuration. Browser automation visits only pages you or the AI specify.
- **Git, SSH, sync and backup**: repository, file or backup data is sent only to hosts you configure and use. Credentials are provided by you and stored locally unless you enable the relevant sync or injection feature.
- **Plugins**: plugins can run in the container and make requests to services they declare. Check a plugin's source and permissions before installing it.

## 4. Device permissions

The app uses system capabilities only after you grant permission, for example storage access (workspaces, attachments and backups), installing unknown apps (APK installation), notifications (task completion), battery optimization exemption (keeping background tasks running), and camera or photo access (sending images). You can revoke permissions in system settings; related features may then stop working.

Shizuku, accessibility or device-management capabilities are used only when you authorize and enable them, and only to perform system actions you initiate.

## 5. Retention and deletion

Local data is under your control. You can delete conversations, workspaces, logs or backups in the app, or clear app data or uninstall the app in system settings. Clearing app data removes configuration, conversations, containers and workspaces in app-private storage. Files in external directories, remote servers or export locations are not deleted automatically and must be handled by you.

Data held by third-party services is handled under their privacy policies and your account settings; this project cannot delete it for you. To remove content you submitted in an issue, contact the maintainer or use the relevant platform.

## 6. Children and sensitive information

The app is intended for developers with the relevant technical judgment and is not directed at children. Do not submit unnecessary identity documents, medical or financial account data, keys or other highly sensitive information in chats, logs, issues or plugin configuration.

## 7. Third-party services and policy updates

Third-party AI, Git, storage, server and plugin services have their own privacy policies and data rules. Read and confirm them before selecting or enabling such a service. This project does not control third-party data practices.

This policy may be updated for feature or legal changes; the new version and date will be shown in the app. Material changes will be noted in the project repository. Continued use means you accept the updated policy.

## 8. Contact

Questions about this policy can be raised in the [project issue tracker](https://github.com/Lisir2002/MiniMe-bot/issues). Do not post keys, passwords, chat content or unredacted logs in public issues.
