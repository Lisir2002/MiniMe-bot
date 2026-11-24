package com.minibot.control.container

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DockerRegistryAuthTest {
    @Test
    fun `builds auth only for exact registry prefix`() {
        val auth = buildRegistryAuth(
            imageRef = "ghcr.io/lisir2002/minibot-base:alpine-amd64",
            registryHost = "GHCR.IO",
            registryUsername = "Lisir2002",
            registryToken = "read-token",
        )
        assertEquals("Lisir2002", auth?.username)
        assertEquals("read-token", auth?.password)
        assertEquals("ghcr.io", auth?.registryAddress)
    }

    @Test
    fun `does not leak auth to lookalike registry`() {
        assertNull(
            buildRegistryAuth(
                imageRef = "ghcr.io.evil/minibot-base:latest",
                registryHost = "ghcr.io",
                registryUsername = "Lisir2002",
                registryToken = "read-token",
            ),
        )
    }

    @Test
    fun `requires all auth fields`() {
        assertNull(buildRegistryAuth("ghcr.io/lisir2002/base:tag", "ghcr.io", "Lisir2002", null))
        assertNull(buildRegistryAuth("ghcr.io/lisir2002/base:tag", "ghcr.io", null, "token"))
        assertNull(buildRegistryAuth("ghcr.io/lisir2002/base:tag", null, "Lisir2002", "token"))
    }
}
