package com.animeow.app.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomModelTemperatureTest {
    @Test fun arkKimiUsesRequiredTemperatureForBaseUrlAndFullEndpoint() {
        val model = "kimi-k3-1-pro-261024"
        val base = "https://ark.cn-beijing.volces.com/api/v3"
        assertEquals(1f, fixedCustomModelTemperature(base, model))
        assertEquals(1f, fixedCustomModelTemperature("$base/chat/completions", model))
        assertNull(fixedCustomModelTemperature(base, "ep-20261113-example"))
        assertNull(fixedCustomModelTemperature("https://example.test/v1", model))
    }
}
