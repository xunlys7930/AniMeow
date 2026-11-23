package com.animeow.app.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.animeow.app.ui.preferences.CloudConfigurationCard
import com.animeow.app.ui.theme.AniMeowTheme
import com.animeow.app.ui.theme.AppearanceSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CloudConfigurationInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loginAndReviewOfBackupScopePrecedeUploadAndBusyPreventsRepeats() {
        val username = mutableStateOf<String?>(null)
        val busy = mutableStateOf(false)
        var logins = 0
        var uploads = 0
        compose.setContent { AniMeowTheme(AppearanceSettings(useDynamicColor = false, fontScale = 1.4f)) {
            Surface { CloudConfigurationCard(true, username.value, busy.value, busy.value,
                onLogin = { logins++ }, onSave = { uploads++; busy.value = true }, onManage = {}) }
        } }
        compose.onNodeWithText("登录后保存到云端").performClick()
        compose.runOnIdle { assertEquals(1, logins); assertEquals(0, uploads); username.value = "合成测试账号" }
        compose.onNodeWithText("保存到云端").performClick()
        compose.onNodeWithText("包含当前预览配置", substring = true).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, uploads) }
        compose.onNodeWithText("保存到云端").performClick()
        compose.onNodeWithText("确认保存").performClick()
        compose.onNodeWithText("正在保存到云端…").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, uploads) }
    }
}
