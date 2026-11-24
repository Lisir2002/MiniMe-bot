package com.minibot.control

import com.minibot.control.api.apiModule
import com.minibot.control.config.AppConfig
import com.minibot.control.container.ContainerManager
import com.minibot.control.container.DeploymentCatalog
import com.minibot.control.container.DockerJavaEngine
import com.minibot.control.container.JdbcContainerStore
import com.minibot.control.container.VolumeManager
import com.minibot.control.db.Database
import com.minibot.control.model.JdbcModelStore
import com.minibot.control.model.ModelGateway
import com.minibot.control.model.ModelService
import com.minibot.control.tool.ApprovalService
import com.minibot.control.tool.ApprovalStatus
import com.minibot.control.tool.DockerToolExecutor
import com.minibot.control.tool.JdbcApprovalStore
import com.minibot.control.pairing.BootstrapToken
import com.minibot.control.pairing.JdbcPairingStore
import com.minibot.control.pairing.PairingService
import com.minibot.control.security.MasterKey
import com.minibot.control.security.SecretVault
import com.minibot.control.session.EventHub
import com.minibot.control.session.EventStore
import com.minibot.control.session.JdbcSessionStore
import com.minibot.control.session.RealTurnExecutor
import com.minibot.control.session.SessionService
import com.minibot.control.session.TurnScheduler
import com.minibot.control.session.TurnStatus
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

object BuildInfo {
    const val VERSION = "0.5.0"
}

private val logger = LoggerFactory.getLogger("com.minibot.control.Main")

fun main() {
    val config = AppConfig.fromEnv()
    val masterKey = MasterKey.loadOrCreate(config.masterKeyPath)
    logger.info(
        "Mini-AIBOT control starting: host={} port={} dataDir={} masterKeyBytes={}",
        config.host,
        config.port,
        config.dataDir,
        masterKey.size,
    )

    val database = Database(config)
    database.migrate()
    val store = JdbcPairingStore(database.dataSource)
    val serverId = store.ensureServer(config.serverName, config.timezone)
    val bootstrapToken = BootstrapToken.prepare(config, store)
    if (bootstrapToken != null) {
        logger.info(
            "Server {} is waiting for first device. Bootstrap token is available at {}",
            serverId,
            config.bootstrapTokenFile,
        )
    } else {
        logger.info("Server {} already has a paired device", serverId)
    }

    val pairingService = PairingService(store, serverId, config.serverName)
    val dockerEngine = DockerJavaEngine(
        dockerHost = config.dockerHost,
        registryHost = config.registryHost,
        registryUsername = config.registryUsername,
        registryToken = config.registryToken,
    )
    val containerStore = JdbcContainerStore(database.dataSource)
    val containerManager = ContainerManager(
        engine = dockerEngine,
        store = containerStore,
        volumes = VolumeManager(config.dataDir),
        serverId = serverId,
    )
    val deploymentCatalog = DeploymentCatalog()
    val modelStore = JdbcModelStore(database.dataSource)
    val modelService = ModelService(
        store = modelStore,
        vault = SecretVault(masterKey),
        containers = containerManager,
    )
    val modelGateway = ModelGateway(modelService)
    val sessionStore = JdbcSessionStore(database.dataSource)
    val eventHub = EventHub()
    val eventStore = EventStore(sessionStore, eventHub)
    val approvalService = ApprovalService(
        store = JdbcApprovalStore(database.dataSource),
        events = eventStore,
    )
    val toolExecutor = DockerToolExecutor(
        engine = dockerEngine,
        containers = containerManager,
        events = eventStore,
        approvals = approvalService,
        dataDir = config.dataDir,
    )
    val turnScheduler = TurnScheduler(
        store = sessionStore,
        events = eventStore,
        containers = containerManager,
        executor = RealTurnExecutor(
            store = sessionStore,
            events = eventStore,
            models = modelService,
            gateway = modelGateway,
            tools = toolExecutor,
        ),
    )
    val sessionService = SessionService(
        store = sessionStore,
        containers = containerManager,
        scheduler = turnScheduler,
        events = eventStore,
        models = modelService,
        approvals = approvalService,
    )
    turnScheduler.approvalStatus = { approvalId -> approvalService.find(approvalId)?.status }
    approvalService.onResolved = { approval ->
        if (approval.status in setOf(
                ApprovalStatus.APPROVED,
                ApprovalStatus.DENIED,
                ApprovalStatus.EXPIRED,
            )
        ) {
            val turn = sessionStore.findTurn(approval.turnId)
            if (turn != null && turn.status == TurnStatus.WAITING_APPROVAL) {
                val queued = sessionStore.updateTurnStatus(turn.id, TurnStatus.QUEUED)
                if (queued != null) turnScheduler.enqueue(queued)
            }
        }
    }
    turnScheduler.start()
    approvalService.start()
    runCatching { containerManager.reconcile() }
        .onFailure { logger.warn("Container reconciliation failed; Docker may be unavailable", it) }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info("Mini-AIBOT control shutting down")
            runCatching { approvalService.close() }
            runCatching { turnScheduler.close() }
            runCatching { dockerEngine.close() }
            database.close()
        },
    )

    embeddedServer(
        Netty,
        port = config.port,
        host = config.host,
    ) {
        apiModule(
            config = config,
            store = store,
            pairingService = pairingService,
            containerManager = containerManager,
            deploymentCatalog = deploymentCatalog,
            models = modelService,
            approvals = approvalService,
            sessionService = sessionService,
            eventHub = eventHub,
            serverId = serverId,
            serverName = config.serverName,
            version = BuildInfo.VERSION,
        )
    }.start(wait = true)
}
