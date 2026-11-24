package com.mini.bot.feature.settings.domain.model

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDeviceIdentityTest {
    @Test
    fun `generates distinct EC key pairs`() {
        val first = CloudDeviceIdentity.generate()
        val second = CloudDeviceIdentity.generate()
        assertTrue(first.publicKeyBase64.isNotBlank())
        assertTrue(first.privateKeyBase64.isNotBlank())
        assertNotEquals(first.publicKeyBase64, second.publicKeyBase64)
    }
}
