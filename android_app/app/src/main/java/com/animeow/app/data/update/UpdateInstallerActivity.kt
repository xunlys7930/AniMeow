package com.animeow.app.data.update

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class UpdateInstallerActivity : ComponentActivity() {
    private val updateService by lazy { AppUpdateService(this) }
    private var downloadId: Long = -1L
    private var permissionRequestAttempted = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        continueInstallation()
    }

    private val installerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
        permissionRequestAttempted = savedInstanceState?.getBoolean(KEY_PERMISSION_REQUESTED) ?: false
        if (downloadId <= 0L) {
            finish()
            return
        }
        continueInstallation()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_PERMISSION_REQUESTED, permissionRequestAttempted)
        super.onSaveInstanceState(outState)
    }

    private fun continueInstallation() {
        when (val preparation = updateService.prepareInstaller(downloadId)) {
            is UpdateInstallerPreparation.Ready -> runCatching {
                installerLauncher.launch(preparation.intent)
            }.onFailure { finishWithMessage(it.message ?: "系统安装器无法打开") }

            is UpdateInstallerPreparation.PermissionRequired -> {
                if (permissionRequestAttempted) {
                    finishWithMessage("尚未允许 AniMeow 安装更新，可在系统设置的“安装未知应用”中重新开启")
                } else {
                    permissionRequestAttempted = true
                    runCatching { permissionLauncher.launch(preparation.intent) }
                        .onFailure { finishWithMessage("无法打开安装权限设置") }
                }
            }

            is UpdateInstallerPreparation.Unavailable -> finishWithMessage(preparation.message)
        }
    }

    private fun finishWithMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val EXTRA_DOWNLOAD_ID = "update_download_id"
        private const val KEY_PERMISSION_REQUESTED = "permission_request_attempted"
    }
}
