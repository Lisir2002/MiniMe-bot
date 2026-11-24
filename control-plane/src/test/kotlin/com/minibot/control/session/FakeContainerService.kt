package com.minibot.control.session

import com.minibot.control.container.ContainerActivity
import com.minibot.control.container.ContainerInstance
import com.minibot.control.container.ContainerKind
import com.minibot.control.container.ContainerPhase
import com.minibot.control.container.ContainerProfile
import com.minibot.control.container.ContainerService
import com.minibot.control.container.ContainerSummary
import com.minibot.control.container.DeployContainerRequest
import com.minibot.control.container.ResourcePolicy
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class FakeContainerService : ContainerService {
    val ensureCalls = AtomicInteger(0)

    override fun deploy(request: DeployContainerRequest): ContainerSummary = summary()

    override fun list(): List<ContainerSummary> = listOf(summary())

    override fun get(profileId: String): ContainerSummary = summary(profileId)

    override fun ensureRunning(profileId: String): ContainerSummary {
        ensureCalls.incrementAndGet()
        return summary(profileId)
    }

    override fun stop(profileId: String): ContainerSummary = summary(profileId)

    override fun deleteInstance(profileId: String): ContainerSummary = summary(profileId)

    override fun archive(profileId: String, confirm: Boolean): ContainerSummary = summary(profileId)

    override fun setPinned(profileId: String, pinned: Boolean): ContainerSummary = summary(profileId)

    override fun reconcile() = Unit

    private fun summary(profileId: String = "profile-1"): ContainerSummary {
        val id = "container-1"
        return ContainerSummary(
            profile = ContainerProfile(
                id = profileId,
                kind = ContainerKind.CLOUD,
                name = "test",
                imageRef = "test:image",
                imageDigest = null,
                resourcePolicy = ResourcePolicy(),
                components = emptyList(),
                packages = emptyList(),
                createdAt = Instant.now().toString(),
            ),
            instance = ContainerInstance(
                id = "instance-1",
                profileId = profileId,
                containerId = id,
                phase = ContainerPhase.RUNNING,
                pinned = false,
                lastActivityAt = Instant.now().toString(),
                lastError = null,
            ),
            activity = ContainerActivity(),
        )
    }
}
