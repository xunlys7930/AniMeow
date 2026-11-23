package com.animeow.app.ui.promotion

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.preferences.PromotionMode
import com.animeow.app.data.preferences.PromotionPreferences
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PromotionViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PromotionPreferences(application)
    private val _pending = MutableStateFlow(false)
    val pending = _pending.asStateFlow()
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError = _saveError.asStateFlow()

    fun onAppLaunch(fromLauncher: Boolean) = viewModelScope.launch {
        _pending.value = false
        if (!fromLauncher) return@launch
        runCatchingCancellable { preferences.registerLaunch() }
            .onSuccess { _pending.value = it }
    }
    fun markShown() {
        _pending.value = false
        viewModelScope.launch { runCatchingCancellable { preferences.markShown() } }
    }
    fun choose(mode: PromotionMode, onSaved: () -> Unit) = viewModelScope.launch {
        _saveError.value = null
        runCatchingCancellable { preferences.setMode(mode) }
            .onSuccess { onSaved() }
            .onFailure { _saveError.value = "没能保存选择，请再试一次" }
    }
}

@Composable
fun PromotionPrompt(eligible: Boolean, blocked: Boolean, onSupportRequested: () -> Unit, viewModel: PromotionViewModel = viewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val saveError by viewModel.saveError.collectAsStateWithLifecycle()
    var showing by rememberSaveable { mutableStateOf(false) }
    var shareError by rememberSaveable { mutableStateOf<String?>(null) }
    val focused = LocalWindowInfo.current.isWindowFocused
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val context = LocalContext.current
    LaunchedEffect(pending, eligible, blocked, focused, lifecycle) {
        if (pending && eligible && !blocked && focused && lifecycle == Lifecycle.State.RESUMED && !showing) {
            // Focus belongs to the main window only while no sheet, dialog or permission prompt owns it.
            delay(3_000)
            showing = true
            viewModel.markShown()
        }
    }
    if (showing && eligible && !blocked) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text("一起支持小喵继续成长") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("如果 AniMeow 对你有帮助，可以在爱发电自愿支持；需要安装包时，也可以通过夸克网盘转存。\n\n符合夸克平台规则的转存可为开发者带来推广收益，用于服务器维护与后续开发。\n\n支持完全自愿，现有功能免费使用，也可以继续选择其他下载渠道。")
                    OutlinedButton(onClick = { showing = false; onSupportRequested() }, modifier = Modifier.fillMaxWidth()) {
                        Text("了解支持方式")
                    }
                    OutlinedButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                            "我在用 AniMeow 记录追番、整理喜欢的作品和角色，也想介绍给你 (=^･ω･^=)\nhttps://github.com/xunlys7930/AniMeow")
                        shareError = null
                        runCatching { context.startActivity(Intent.createChooser(share, "分享 AniMeow")) }
                            .onSuccess { showing = false }
                            .onFailure { shareError = "暂时无法打开分享面板，请稍后重试" }
                    }, modifier = Modifier.fillMaxWidth()) { Text("分享给朋友") }
                    saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    shareError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.choose(PromotionMode.OCCASIONAL) { showing = false } }) { Text("暂时不用") } },
            dismissButton = { TextButton(onClick = { viewModel.choose(PromotionMode.NEVER) { showing = false } }) { Text("不再显示") } },
        )
    }
}
