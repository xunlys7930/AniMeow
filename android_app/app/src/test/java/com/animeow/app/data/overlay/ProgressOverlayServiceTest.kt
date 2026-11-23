package com.animeow.app.data.overlay

import android.app.Service
import android.app.NotificationManager
import android.app.NotificationChannel
import android.os.ResultReceiver
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class ProgressOverlayServiceTest {
    @Test
    @Config(sdk = [30, 36])
    fun blockedChannelPreventsStartingEvenWhenAppNotificationsAreEnabled() {
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(ProgressOverlayService::class.java).create()
        try {
            val service = controller.get()
            shadowOf(service.application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            service.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(ProgressOverlayService.CHANNEL, "悬浮记录", NotificationManager.IMPORTANCE_NONE))
            service.onStartCommand(ProgressOverlayService.intent(service, 10), 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
            org.junit.Assert.assertNull(shadowOf(service).lastForegroundNotification)
        } finally { controller.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }

    @Test
    @Config(sdk = [30])
    fun foregroundFailureDoesNotSendAStartedReceipt() {
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(ProgressOverlayService::class.java).create()
        try {
            val service = controller.get()
            shadowOf(service).setThrowInStartForeground(SecurityException("permission revoked"))
            var started = false
            val result = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { started = true }
            }
            service.onStartCommand(ProgressOverlayService.intent(service, 10, result), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(service).isStoppedBySelf)
            org.junit.Assert.assertFalse(started)
        } finally { controller.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }
    @Test
    @Config(sdk = [30])
    fun disabledNotificationsStopBeforeCreatingAnOverlayOnOldAndroid() {
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(ProgressOverlayService::class.java).create()
        try {
            val service = controller.get()
            shadowOf(service.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
            service.onStartCommand(Intent().putExtra(ProgressOverlayService.EXTRA_ID, 10L), 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
            org.junit.Assert.assertNull(shadowOf(service).lastForegroundNotification)
        } finally { controller.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }
    @Test fun missingPermissionStopsWithoutStartingOrRestartingTheService() {
        ShadowSettings.setCanDrawOverlays(false)
        val controller = Robolectric.buildService(ProgressOverlayService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent().putExtra(ProgressOverlayService.EXTRA_ID, 10L), 0, 1))
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally { controller.destroy() }
    }
    @Test fun missingWorkIdAndNullRestartCannotOpenAnOverlay() {
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(ProgressOverlayService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally { controller.destroy(); ShadowSettings.setCanDrawOverlays(false) }
    }
}
