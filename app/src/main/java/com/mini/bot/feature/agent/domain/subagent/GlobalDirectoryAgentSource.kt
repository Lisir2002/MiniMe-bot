package com.mini.bot.feature.agent.domain.subagent

import com.mini.bot.feature.workspace.domain.FileAccessProvider
import com.mini.bot.feature.workspace.domain.AgentConfigKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局子代理定义来源：`~/.minibot/agents`，跨项目共享。以容器路径经 [FileAccessProvider] 访问，
 * 跟随当前执行环境——本地模式落 App 私有 `filesDir/minibot/agents`（容器内 `/root/.minibot/agents`），
 * 远程模式经 SSH 落服务器用户 home 下的 `.minibot/agents`。远程是独立执行环境，读写均以远端为准。
 */
@Singleton
class GlobalDirectoryAgentSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentDefinitionSource {

    val agentsRoot: String = "~/.minibot/agents"

    override fun listDefinitions(): List<AgentDefinition> =
        AgentDefinitionDirectoryScanner.scan(fileAccess, agentsRoot)

    fun find(name: String): AgentDefinition? {
        val snapshot = fileAccess.cachedAgentConfigSnapshot(agentsRoot, AgentConfigKind.AGENTS)
        val definitions = snapshot?.let(AgentDefinitionDirectoryScanner::fromSnapshot) ?: listDefinitions()
        val definition = definitions.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        val path = definition.filePath ?: return definition
        return AgentDefinitionParser.parse(fileAccess, path)?.takeIf { it.name.equals(name, ignoreCase = true) }
    }
}
