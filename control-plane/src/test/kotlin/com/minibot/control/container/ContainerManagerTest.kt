package com.minibot.control.container

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ContainerManagerTest {
    private lateinit var tempDir: Path
    private lateinit var engine: FakeDockerEngine
    private lateinit var store: InMemoryContainerStore
    private lateinit var manager: ContainerManager

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("minibot-container-test")
        engine = FakeDockerEngine()
        store = InMemoryContainerStore()
        manager = ContainerManager(
            engine = engine,
            store = store,
            volumes = VolumeManager(tempDir),
            serverId = "server-test",
        )
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `deploy creates profile instance and starts container`() {
        val summary = manager.deploy(
            DeployContainerRequest(
                name = "My Cloud",
                imageRef = "ghcr.io/example/minibot:test",
                resourcePolicy = ResourcePolicy(cpuCores = 2.0, memoryMb = 2048, diskMb = 10240),
                components = listOf("node"),
            ),
        )

        assertEquals(ContainerKind.CLOUD, summary.profile.kind)
        assertEquals(ContainerPhase.RUNNING, summary.instance.phase)
        assertEquals("cnt-1", summary.instance.containerId)
        assertEquals(listOf("ghcr.io/example/minibot:test"), engine.pulledImages)
        assertEquals(listOf("cnt-1"), engine.startedContainers)
        assertNotNull(store.findProfile(summary.profile.id))
        assertTrue(Files.exists(tempDir.resolve("volumes/${summary.profile.id}/workspace")))
    }

    @Test
    fun `ensureRunning starts an existing stopped container`() {
        val deployed = manager.deploy(DeployContainerRequest("Cloud", "example/image:1"))
        manager.stop(deployed.profile.id)

        val state = engine.setState(deployed.instance.containerId!!, running = false)
        val resumed = manager.ensureRunning(deployed.profile.id)

        assertEquals(ContainerPhase.RUNNING, resumed.instance.phase)
        assertEquals(2, engine.startedContainers.size)
        assertEquals(false, state.running)
    }

    @Test
    fun `delete instance keeps container and profile`() {
        val deployed = manager.deploy(DeployContainerRequest("Cloud", "example/image:1"))
        val deleted = manager.deleteInstance(deployed.profile.id)

        assertEquals(ContainerPhase.STOPPED, deleted.instance.phase)
        assertNotNull(deleted.instance.containerId)
        assertNotNull(store.findProfile(deployed.profile.id))
        assertTrue(engine.removedContainers.isEmpty())
    }

    @Test
    fun `archive requires confirmation and removes container and profile`() {
        val deployed = manager.deploy(DeployContainerRequest("Cloud", "example/image:1"))

        assertFailsWith<ContainerException> {
            manager.archive(deployed.profile.id, confirm = false)
        }

        manager.archive(deployed.profile.id, confirm = true)
        assertNull(store.findProfile(deployed.profile.id))
        assertTrue(engine.removedContainers.contains("cnt-1"))
        assertTrue(!Files.exists(tempDir.resolve("volumes/${deployed.profile.id}")))
    }

    @Test
    fun `pinned flag is persisted`() {
        val deployed = manager.deploy(DeployContainerRequest("Cloud", "example/image:1"))
        val pinned = manager.setPinned(deployed.profile.id, true)

        assertTrue(pinned.instance.pinned)
        assertTrue(pinned.activity.pinned)
    }
}

private class FakeDockerEngine : DockerEngine {
    val pulledImages = mutableListOf<String>()
    val startedContainers = mutableListOf<String>()
    val stoppedContainers = mutableListOf<String>()
    val removedContainers = mutableListOf<String>()
    private val states = mutableMapOf<String, DockerContainerState>()
    private var nextId = 1

    override fun pullImage(imageRef: String) {
        pulledImages += imageRef
    }

    override fun createContainer(spec: CreateContainerSpec): String {
        val id = "cnt-${nextId++}"
        states[id] = DockerContainerState(
            id = id,
            running = false,
            status = "created",
            labels = spec.labels,
        )
        return id
    }

    override fun startContainer(containerId: String) {
        startedContainers += containerId
        states[containerId] = states.getValue(containerId).copy(running = true, status = "running")
    }

    override fun stopContainer(containerId: String, timeoutSeconds: Int) {
        stoppedContainers += containerId
        states[containerId] = states.getValue(containerId).copy(running = false, status = "exited")
    }

    override fun removeContainer(containerId: String, removeVolumes: Boolean) {
        removedContainers += containerId
        states.remove(containerId)
    }

    override fun inspectContainer(containerId: String): DockerContainerState? = states[containerId]

    override fun listManagedContainers(labelKey: String, labelValue: String): List<DockerContainerState> {
        return states.values.filter { it.labels[labelKey] == labelValue }
    }

    override fun exec(
        containerId: String,
        command: String,
        cwd: String?,
        timeoutMs: Long,
        onChunk: (stream: String, chunk: ByteArray) -> Unit,
    ): DockerExecResult = DockerExecResult(0, 0, false)

    fun setState(containerId: String, running: Boolean): DockerContainerState {
        val state = states.getValue(containerId).copy(
            running = running,
            status = if (running) "running" else "exited",
        )
        states[containerId] = state
        return state
    }
}

private class InMemoryContainerStore : ContainerStore {
    private val profiles = mutableMapOf<String, ContainerProfile>()
    private val instances = mutableMapOf<String, ContainerInstance>()

    override fun createProfile(profile: ContainerProfile): ContainerProfile {
        profiles[profile.id] = profile
        return profile
    }

    override fun findProfile(profileId: String): ContainerProfile? = profiles[profileId]

    override fun listProfiles(): List<ContainerProfile> = profiles.values.toList()

    override fun findInstance(profileId: String): ContainerInstance? = instances[profileId]

    override fun saveInstance(instance: ContainerInstance) {
        instances[instance.profileId] = instance
    }

    override fun updateInstance(
        profileId: String,
        phase: ContainerPhase,
        containerId: String?,
        lastError: String?,
    ) {
        val current = instances[profileId] ?: return
        instances[profileId] = current.copy(
            phase = phase,
            containerId = containerId ?: current.containerId,
            lastError = lastError,
        )
    }

    override fun setPinned(profileId: String, pinned: Boolean) {
        val current = instances[profileId] ?: return
        instances[profileId] = current.copy(pinned = pinned)
    }

    override fun deleteProfile(profileId: String) {
        profiles.remove(profileId)
        instances.remove(profileId)
    }
}
