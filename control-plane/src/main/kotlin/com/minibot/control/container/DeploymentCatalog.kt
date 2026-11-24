package com.minibot.control.container

import com.minibot.control.api.DeploymentComponentOption
import com.minibot.control.api.DeploymentImageOption
import com.minibot.control.api.DeploymentOptionsResponse
import com.minibot.control.api.ResourcePreset

class DeploymentCatalog(
    private val imageRegistry: String = "ghcr.io/lisir2002/minibot-base",
) {
    fun options(): DeploymentOptionsResponse {
        val distros = listOf(
            "debian" to "Debian stable",
            "ubuntu" to "Ubuntu LTS",
            "alpine" to "Alpine",
        )
        val architectures = listOf("amd64", "arm64")
        val images = distros.flatMap { (distro, display) ->
            architectures.map { architecture ->
                DeploymentImageOption(
                    id = "$distro-$architecture",
                    displayName = "$display / $architecture",
                    imageRef = "$imageRegistry:$distro-$architecture",
                    architecture = architecture,
                    distro = distro,
                )
            }
        }

        return DeploymentOptionsResponse(
            images = images,
            components = components(),
            packageManagers = listOf(
                "apt",
                "apk",
                "npm",
                "pnpm",
                "yarn",
                "pip",
                "uv",
                "cargo",
                "gem",
                "composer",
                "go",
            ),
            presets = listOf(
                ResourcePreset(
                    id = "small",
                    displayName = "小 / 1C 1G",
                    resourcePolicy = ResourcePolicy(cpuCores = 1.0, memoryMb = 1024, diskMb = 10240),
                ),
                ResourcePreset(
                    id = "medium",
                    displayName = "中 / 2C 2G",
                    resourcePolicy = ResourcePolicy(cpuCores = 2.0, memoryMb = 2048, diskMb = 20480),
                ),
                ResourcePreset(
                    id = "large",
                    displayName = "大 / 4C 4G",
                    resourcePolicy = ResourcePolicy(cpuCores = 4.0, memoryMb = 4096, diskMb = 40960),
                ),
            ),
        )
    }

    private fun components(): List<DeploymentComponentOption> = listOf(
        DeploymentComponentOption("node", "Node.js", "runtime", "Node.js 运行时与 npm"),
        DeploymentComponentOption("python", "Python", "runtime", "Python 运行时与 pip"),
        DeploymentComponentOption("jdk", "JDK", "runtime", "OpenJDK 运行时"),
        DeploymentComponentOption("android-sdk", "Android SDK", "mobile", "Android 构建工具与平台"),
        DeploymentComponentOption("flutter", "Flutter", "mobile", "Flutter SDK"),
        DeploymentComponentOption("go", "Go", "runtime", "Go 工具链"),
        DeploymentComponentOption("rust", "Rust", "runtime", "Rust 工具链"),
        DeploymentComponentOption("php", "PHP", "runtime", "PHP 运行时与 Composer"),
    )
}
