package com.animeow.app.ui.preferences

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.data.branding.BrandingAssetStore
import com.animeow.app.data.branding.BrandingSettings
import com.animeow.app.data.branding.LauncherIcon
import com.animeow.app.data.branding.SplashBackgroundMode
import com.animeow.app.data.branding.SplashScaleMode
import com.animeow.app.data.preferences.BrandingPreferences
import com.animeow.app.util.runCatchingCancellable
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class BrandingUiState(
    val settings: BrandingSettings? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class BrandingViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = BrandingPreferences(application)
    private val assets = BrandingAssetStore(application)
    private val _uiState = MutableStateFlow(BrandingUiState())
    val uiState: StateFlow<BrandingUiState> = _uiState.asStateFlow()
    private val _splashPending = MutableStateFlow(true)
    val splashPending: StateFlow<Boolean> = _splashPending.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.settings.collectLatest { settings ->
                _uiState.value = _uiState.value.copy(settings = settings)
            }
        }
    }

    fun splashFile(settings: BrandingSettings?): File? =
        assets.resolve(settings?.splashImagePath)

    fun consumeSplash() {
        _splashPending.value = false
    }

    fun importSplash(uri: Uri) = runBusy {
        val path = assets.importSplash(uri)
        try {
            preferences.setSplashImage(path, enabled = true)
        } catch (error: Throwable) {
            assets.discardSplash(path)
            throw error
        }
        assets.cleanupSplashFiles(path)
        "启动封面已更新"
    }

    fun removeSplash() = runBusy {
        val current = preferences.snapshot()
        preferences.setSplashImage(path = null, enabled = false)
        assets.cleanupSplashFiles(keepRelativePath = null)
        "已清除自定义启动封面"
    }

    fun setCustomSplashEnabled(enabled: Boolean) = update {
        if (enabled && splashFile(preferences.snapshot()) == null) {
            error("请先选择一张启动封面")
        }
        preferences.setCustomSplashEnabled(enabled)
    }

    fun setSplashDurationMillis(value: Int) = update {
        preferences.setSplashDurationMillis(value)
    }

    fun setSplashScaleMode(value: SplashScaleMode) = update {
        preferences.setSplashScaleMode(value)
    }

    fun setSplashBackgroundMode(value: SplashBackgroundMode) = update {
        preferences.setSplashBackgroundMode(value)
    }

    fun setSplashFocalPoint(x: Float, y: Float) = update {
        preferences.setSplashFocalPoint(x, y)
    }

    fun resetSplashFraming() = update {
        preferences.setSplashScaleMode(SplashScaleMode.COVER)
        preferences.setSplashFocalPoint(0.5f, 0.5f)
    }

    fun setTapToSkip(enabled: Boolean) = update {
        preferences.setTapToSkip(enabled)
    }

    fun setLauncherIcon(icon: LauncherIcon) = runBusy {
        preferences.setLauncherIcon(icon)
        "已切换为“${icon.displayName}”，桌面可能需要几秒刷新"
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun update(block: suspend () -> Unit) = viewModelScope.launch {
        runCatchingCancellable { block() }
            .onFailure { error ->
                _uiState.value = _uiState.value.copy(message = error.message ?: "设置保存失败")
            }
    }

    private fun runBusy(block: suspend () -> String) = viewModelScope.launch {
        if (_uiState.value.busy) return@launch
        _uiState.value = _uiState.value.copy(busy = true, message = null)
        _uiState.value = runCatchingCancellable { block() }.fold(
            onSuccess = { message -> _uiState.value.copy(busy = false, message = message) },
            onFailure = { error ->
                _uiState.value.copy(
                    busy = false,
                    message = error.message ?: "操作失败",
                )
            },
        )
    }
}
