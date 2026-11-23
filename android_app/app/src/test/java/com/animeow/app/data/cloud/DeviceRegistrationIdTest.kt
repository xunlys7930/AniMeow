package com.animeow.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeviceRegistrationIdTest {
    @Test
    fun derivedIdIsStableAndNamespaced() {
        val first = DeviceRegistrationId.derive("device-123", "com.animeow.app")
        val second = DeviceRegistrationId.derive("device-123", "com.animeow.app")
        val otherApp = DeviceRegistrationId.derive("device-123", "com.example.other")

        assertEquals(64, first.length)
        assertEquals(first, second)
        assertNotEquals(first, otherApp)
    }
}
