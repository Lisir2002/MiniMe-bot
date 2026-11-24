package com.minibot.control.model

import com.minibot.control.security.SecretVault
import com.minibot.control.session.FakeContainerService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.UUID

class ModelServiceTest {
    private val store = FakeModelStore()
    private val service = ModelService(
        store = store,
        vault = SecretVault(ByteArray(32) { it.toByte() }),
        containers = FakeContainerService(),
    )
    private val profileId = UUID.randomUUID().toString()

    @Test
    fun `credential is stored without exposing the secret`() {
        val summary = service.createCredential(
            CreateCredentialRequest(
                provider = "openai",
                baseUrl = "https://api.openai.com/v1",
                secret = "sk-do-not-return",
            ),
        )
        assertEquals("UNASSIGNED", summary.status)
        assertTrue(summary.hasSecret)
        assertTrue(summary.toString().contains("sk-do-not-return").not())
    }

    @Test
    fun `binding activates credential and resolves for its container`() {
        val credential = service.createCredential(
            CreateCredentialRequest("openai", "https://api.openai.com/v1", "sk-test"),
        )
        val definition = service.createDefinition(
            CreateModelDefinitionRequest("openai", "gpt-4o-mini", "GPT-4o mini"),
        )
        val binding = service.createBinding(
            CreateModelBindingRequest(profileId, definition.id, credential.id),
        )
        assertEquals("ACTIVE", binding.status)
        assertEquals(CredentialStatus.ACTIVE.name, store.findCredential(credential.id)?.status?.name)
        assertEquals("sk-test", service.decryptCredential(service.resolve(binding.id, profileId).credential))
    }

    @Test
    fun `binding rejects a different provider`() {
        val credential = service.createCredential(
            CreateCredentialRequest("openai", "https://api.openai.com/v1", "sk-test"),
        )
        val definition = service.createDefinition(
            CreateModelDefinitionRequest("anthropic", "claude", "Claude"),
        )
        assertFailsWith<ModelException> {
            service.createBinding(CreateModelBindingRequest(profileId, definition.id, credential.id))
        }
    }

    @Test
    fun `resolver rejects binding from another container`() {
        val credential = service.createCredential(
            CreateCredentialRequest("openai", "https://api.openai.com/v1", "sk-test"),
        )
        val definition = service.createDefinition(
            CreateModelDefinitionRequest("openai", "gpt-4o-mini", "GPT-4o mini"),
        )
        val binding = service.createBinding(
            CreateModelBindingRequest(profileId, definition.id, credential.id),
        )
        assertFailsWith<ModelException> {
            service.resolve(binding.id, UUID.randomUUID().toString())
        }
    }
}
