package com.mini.bot.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudControlPlaneEndpointTest {
    @Test
    fun `accepts ip with port`() {
        val result = CloudControlPlaneEndpoint.normalize("156.225.16.61:8787")
        assertTrue(result is CloudEndpointParseResult.Success)
        assertEquals(
            "http://156.225.16.61:8787",
            (result as CloudEndpointParseResult.Success).endpoint.baseUrl
        )
        assertFalse(result.endpoint.secure)
    }

    @Test
    fun `adds default port and http scheme`() {
        val result = CloudControlPlaneEndpoint.normalize("156.225.16.61")
        assertEquals(
            "http://156.225.16.61:8787",
            (result as CloudEndpointParseResult.Success).endpoint.baseUrl
        )
    }

    @Test
    fun `accepts explicit http and trailing slash`() {
        val result = CloudControlPlaneEndpoint.normalize("http://156.225.16.61:8787/")
        assertEquals(
            "http://156.225.16.61:8787",
            (result as CloudEndpointParseResult.Success).endpoint.baseUrl
        )
    }

    @Test
    fun `accepts https domain`() {
        val result = CloudControlPlaneEndpoint.normalize("https://example.com")
        assertEquals(
            "https://example.com",
            (result as CloudEndpointParseResult.Success).endpoint.baseUrl
        )
        assertTrue(result.endpoint.secure)
    }

    @Test
    fun `accepts bare domain and adds https`() {
        val result = CloudControlPlaneEndpoint.normalize("Example.COM")
        assertEquals(
            "https://example.com",
            (result as CloudEndpointParseResult.Success).endpoint.baseUrl
        )
    }

    @Test
    fun `rejects http domain and asks for https`() {
        val result = CloudControlPlaneEndpoint.normalize("http://example.com")
        assertEquals(
            CloudEndpointError.HTTPS_REQUIRED,
            (result as CloudEndpointParseResult.Error).error
        )
    }

    @Test
    fun `rejects https ip and asks for domain`() {
        val result = CloudControlPlaneEndpoint.normalize("https://1.2.3.4")
        assertEquals(
            CloudEndpointError.DOMAIN_REQUIRED,
            (result as CloudEndpointParseResult.Error).error
        )
    }

    @Test
    fun `rejects invalid domain label`() {
        val result = CloudControlPlaneEndpoint.normalize("https://bad_domain.example")
        assertEquals(
            CloudEndpointError.DOMAIN_INVALID,
            (result as CloudEndpointParseResult.Error).error
        )
    }

    @Test
    fun `rejects wildcard domain`() {
        val result = CloudControlPlaneEndpoint.normalize("https://*.example.com")
        assertEquals(
            CloudEndpointError.DOMAIN_INVALID,
            (result as CloudEndpointParseResult.Error).error
        )
    }

    @Test
    fun `rejects paths`() {
        val result = CloudControlPlaneEndpoint.normalize("http://156.225.16.61:8787/api")
        assertEquals(
            CloudEndpointError.INVALID_FORMAT,
            (result as CloudEndpointParseResult.Error).error
        )
    }

    @Test
    fun `rejects invalid port`() {
        val result = CloudControlPlaneEndpoint.normalize("156.225.16.61:99999")
        assertEquals(
            CloudEndpointError.INVALID_PORT,
            (result as CloudEndpointParseResult.Error).error
        )
    }

    @Test
    fun `rejects invalid ip octet`() {
        val result = CloudControlPlaneEndpoint.normalize("300.1.1.1:8787")
        assertEquals(
            CloudEndpointError.INVALID_HOST,
            (result as CloudEndpointParseResult.Error).error
        )
    }

    @Test
    fun `rejects blank input`() {
        val result = CloudControlPlaneEndpoint.normalize("   ")
        assertEquals(
            CloudEndpointError.EMPTY,
            (result as CloudEndpointParseResult.Error).error
        )
    }
}
