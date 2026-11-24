package com.mini.bot.feature.settings.domain.model

/**
 * 首次安装控制面的单行命令。
 *
 * 安装器和分发包都放在同一个 control-plane Release；App 只负责拼参数，不伪造不存在的下载地址。
 * 域名地址走 --domain，纯 IP 走 --access ip；没有域名时仍然可以完整安装。
 */
object CloudInstallCommand {
    const val RELEASE_TAG = "control-plane-v0.1.1"
    const val RELEASE_BASE = "https://github.com/Lisir2002/MiniMe-bot/releases/download/$RELEASE_TAG"
    const val INSTALLER_URL = "$RELEASE_BASE/install.sh"
    const val DISTRIBUTION_URL = "$RELEASE_BASE/minibot-control-dist.tar.gz"

    fun forInput(raw: String): String {
        val endpoint = (CloudControlPlaneEndpoint.normalize(raw) as? CloudEndpointParseResult.Success)?.endpoint
        return build(domain = endpoint?.takeIf { it.secure }?.host)
    }

    fun build(domain: String?): String {
        val accessArg = if (domain.isNullOrBlank()) {
            "--access ip"
        } else {
            "--domain ${shellQuote(domain)}"
        }
        return "curl -fsSL $INSTALLER_URL | sudo bash -s -- \\\n" +
            "  --dist-url $DISTRIBUTION_URL \\\n" +
            "  $accessArg"
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
