package com.mini.bot.feature.settings.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudInstallCommandTest {
    @Test
    fun `raw ip input uses access ip`() {
        val command = CloudInstallCommand.forInput("156.225.16.61:8787")
        assertTrue(command.contains("--access ip"))
        assertFalse(command.contains("--domain"))
    }

    @Test
    fun `https domain input uses domain parameter`() {
        val command = CloudInstallCommand.forInput("https://Example.COM")
        assertTrue(command.contains("--domain 'example.com'"))
        assertFalse(command.contains("--access ip"))
    }

    @Test
    fun `invalid input falls back to ip test`() {
        val command = CloudInstallCommand.forInput("not a valid address")
        assertTrue(command.contains("--access ip"))
    }

    @Test
    fun `release assets are versioned under the control plane tag`() {
        assertTrue(CloudInstallCommand.INSTALLER_URL.contains(CloudInstallCommand.RELEASE_TAG))
        assertTrue(CloudInstallCommand.DISTRIBUTION_URL.endsWith("minibot-control-dist.tar.gz"))
    }
}
