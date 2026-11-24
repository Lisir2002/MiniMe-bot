package com.minibot.control.api

import com.minibot.control.config.AppConfig
import com.minibot.control.container.ContainerException
import com.minibot.control.container.ContainerService
import com.minibot.control.container.DeployContainerRequest
import com.minibot.control.container.DeploymentCatalog
import com.minibot.control.pairing.BootstrapToken
import com.minibot.control.pairing.ClaimResult
import com.minibot.control.pairing.DeviceRecord
import com.minibot.control.pairing.PairingService
import com.minibot.control.pairing.PairingStore
import com.minibot.control.model.ModelService
import com.minibot.control.session.EventHub
import com.minibot.control.tool.ApprovalService
import com.minibot.control.session.SessionOperations
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.time.Instant

fun Application.apiModule(
    config: AppConfig,
    store: PairingStore,
    pairingService: PairingService,
    containerManager: ContainerService,
    deploymentCatalog: DeploymentCatalog,
    sessionService: SessionOperations,
    models: ModelService? = null,
    approvals: ApprovalService? = null,
    eventHub: EventHub,
    serverId: String,
    serverName: String,
    version: String,
) {
    val serverArchitecture = when (System.getProperty("os.arch")?.lowercase()) {
        "aarch64", "arm64" -> "arm64"
        else -> "amd64"
    }
    install(CallLogging) {
        level = Level.INFO
    }
    install(DefaultHeaders)
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            },
        )
    }
    install(StatusPages) {
        exception<ContentTransformationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid_request", "Malformed JSON request body"),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled request failure", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("internal_error", "Internal server error", retryable = true),
            )
        }
    }

    routing {
        get("/health") {
            call.respond(
                HealthResponse(
                    status = "ok",
                    time = Instant.now().toString(),
                ),
            )
        }

        route("/api/v1") {
            models?.let {
                modelRoutes(
                    pairingService = pairingService,
                    models = it,
                )
            }
            approvals?.let {
                approvalRoutes(
                    pairingService = pairingService,
                    approvals = it,
                )
            }
            sessionRoutes(
                pairingService = pairingService,
                sessions = sessionService,
                eventHub = eventHub,
                json = eventJson,
            )

            post("/pair/claim") {
                val request = call.receive<PairClaimRequest>()
                when (
                    val result = pairingService.claim(
                        bootstrapToken = request.bootstrapToken,
                        devicePublicKey = request.devicePublicKey,
                        deviceName = request.deviceName,
                    )
                ) {
                    is ClaimResult.Success -> {
                        BootstrapToken.clear(config.bootstrapTokenFile)
                        call.respond(
                            HttpStatusCode.OK,
                            PairClaimResponse(
                                deviceId = result.deviceId,
                                deviceToken = result.deviceToken,
                                serverId = result.serverId,
                                serverName = result.serverName,
                            ),
                        )
                    }

                    is ClaimResult.Failure -> call.respond(
                        HttpStatusCode.fromValue(result.status),
                        ErrorResponse(result.code, result.message),
                    )
                }
            }

            get("/server/info") {
                val device = call.authenticate(pairingService)
                if (device == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse("unauthorized", "A valid device token is required"),
                    )
                    return@get
                }
                call.respond(
                    ServerInfoResponse(
                        serverId = serverId,
                        serverName = serverName,
                        timezone = config.timezone,
                        paired = store.activeDevice() != null,
                        version = version,
                        time = Instant.now().toString(),
                        accessMode = config.accessMode,
                        publicUrl = config.publicUrl,
                        domain = config.domain,
                        architecture = serverArchitecture,
                    ),
                )
            }

            get("/deploy/options") {
                if (!call.requireDevice(pairingService)) return@get
                call.respond(deploymentCatalog.options())
            }

            get("/containers") {
                if (!call.requireDevice(pairingService)) return@get
                call.respond(containerManager.list())
            }

            post("/containers") {
                if (!call.requireDevice(pairingService)) return@post
                val request = call.receive<DeployContainerRequest>()
                val options = deploymentCatalog.options()
                if (options.images.none { it.imageRef == request.imageRef.trim() }) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("unsupported_image", "Select an official image from the deployment catalog"),
                    )
                    return@post
                }
                val allowedComponents = options.components.map { it.id }.toSet()
                if (request.components.any { it !in allowedComponents }) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("unsupported_component", "Unknown component id"),
                    )
                    return@post
                }
                try {
                    call.respond(HttpStatusCode.Created, containerManager.deploy(request))
                } catch (error: ContainerException) {
                    call.respond(
                        HttpStatusCode.fromValue(error.error.status),
                        ErrorResponse(error.error.code, error.error.message),
                    )
                }
            }

            get("/containers/{id}") {
                if (!call.requireDevice(pairingService)) return@get
                val profileId = call.profileId() ?: return@get
                try {
                    call.respond(containerManager.get(profileId))
                } catch (error: ContainerException) {
                    call.respondContainerError(error)
                }
            }

            post("/containers/{id}/start") {
                if (!call.requireDevice(pairingService)) return@post
                val profileId = call.profileId() ?: return@post
                try {
                    call.respond(containerManager.ensureRunning(profileId))
                } catch (error: ContainerException) {
                    call.respondContainerError(error)
                }
            }

            post("/containers/{id}/stop") {
                if (!call.requireDevice(pairingService)) return@post
                val profileId = call.profileId() ?: return@post
                try {
                    call.respond(containerManager.stop(profileId))
                } catch (error: ContainerException) {
                    call.respondContainerError(error)
                }
            }

            delete("/containers/{id}/instance") {
                if (!call.requireDevice(pairingService)) return@delete
                val profileId = call.profileId() ?: return@delete
                try {
                    call.respond(containerManager.deleteInstance(profileId))
                } catch (error: ContainerException) {
                    call.respondContainerError(error)
                }
            }

            delete("/containers/{id}/archive") {
                if (!call.requireDevice(pairingService)) return@delete
                val profileId = call.profileId() ?: return@delete
                val confirmed = call.request.queryParameters["confirm"]?.toBooleanStrictOrNull() == true
                try {
                    call.respond(containerManager.archive(profileId, confirmed))
                } catch (error: ContainerException) {
                    call.respondContainerError(error)
                }
            }

            patch("/containers/{id}") {
                if (!call.requireDevice(pairingService)) return@patch
                val profileId = call.profileId() ?: return@patch
                val request = call.receive<UpdateContainerRequest>()
                try {
                    call.respond(containerManager.setPinned(profileId, request.pinned))
                } catch (error: ContainerException) {
                    call.respondContainerError(error)
                }
            }
        }
    }
}

private suspend fun ApplicationCall.profileId(): String? {
    val id = parameters["id"]
    if (id.isNullOrBlank()) {
        respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("invalid_request", "Container id is required"),
        )
        return null
    }
    return id
}

private suspend fun ApplicationCall.requireDevice(pairingService: PairingService): Boolean {
    if (authenticate(pairingService) == null) {
        respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse("unauthorized", "A valid device token is required"),
        )
        return false
    }
    return true
}

private suspend fun ApplicationCall.respondContainerError(error: ContainerException) {
    respond(
        HttpStatusCode.fromValue(error.error.status),
        ErrorResponse(error.error.code, error.error.message),
    )
}

private val eventJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

private fun ApplicationCall.authenticate(pairingService: PairingService): DeviceRecord? =
    request.headers[HttpHeaders.Authorization]
        ?.let { header ->
            header.removePrefix("Bearer ").trim().takeIf { it.isNotBlank() }
        }
        ?.let(pairingService::authenticate)
