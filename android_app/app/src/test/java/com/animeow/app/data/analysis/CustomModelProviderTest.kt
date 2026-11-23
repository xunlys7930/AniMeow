package com.animeow.app.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CustomModelProviderTest {
    @Test fun acceptsExistingFullEndpointsAndVersionedBases() {
        assertEquals("https://example.test/v1", customModelBaseUrl(" https://example.test/v1/chat/completions/ "))
        assertEquals("https://example.test/v1/models", customModelBaseUrl("https://example.test/v1/models"))
        assertEquals("https://example.test/a%2Fb", customModelBaseUrl("https://example.test/a%2Fb/chat/completions"))
        assertEquals(DEFAULT_ENDPOINT, customModelBaseUrl(DEFAULT_ENDPOINT))
        assertEquals("http://127.0.0.1:8080/v1", customModelBaseUrl("http://127.0.0.1:8080/v1"))
    }

    @Test fun rejectsMalformedAndCredentialBearingAddresses() {
        listOf("", "example.test", "https:///v1", "http://example.test/v1", "https://key@example.test/v1", "https://example.test/v1?key=secret", "https://example.test/#key").forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) { customModelBaseUrl(endpoint) }
        }
    }

    @Test fun recognizesPresetsWithoutChangingUnknownExistingSettings() {
        assertEquals("火山方舟", customModelProviderFor("https://ark.cn-beijing.volces.com/api/v3/chat/completions").name)
        assertEquals("自定义 / 中转服务", customModelProviderFor("https://example.test/custom").name)
        customModelProviders.filter { it.endpoint.isNotBlank() }.forEach {
            assertEquals(it.endpoint, customModelBaseUrl(it.endpoint))
        }
    }
}
