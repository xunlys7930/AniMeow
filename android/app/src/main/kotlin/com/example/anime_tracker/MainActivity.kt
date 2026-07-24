package com.example.anime_tracker

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.pm.PackageManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : FlutterActivity() {

    private val channelName = "anime_tracker/app_icon"
    private val updateInstallerChannelName = "anime_tracker/update_installer"

    // 所有备选 alias 名（与 AndroidManifest.xml 中 <activity-alias android:name=".icon_NN"/> 对应）
    private val allAliases = listOf(
        "icon_02", "icon_04", "icon_06",
        "icon_07", "icon_08", "icon_09", "icon_12",
        "icon_13", "icon_14", "icon_15", "icon_16",
        "icon_18",
    )

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "setIcon" -> {
                        val alias = call.argument<String?>("alias")
                        try {
                            applyIcon(alias)
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("SET_ICON_FAILED", e.message, null)
                        }
                    }
                    "getCurrentIcon" -> {
                        result.success(getCurrentIcon())
                    }
                    else -> result.notImplemented()
                }
            }
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, updateInstallerChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "openInstaller" -> {
                        val path = call.argument<String>("path")
                        try {
                            require(!path.isNullOrBlank()) { "安装包路径为空" }
                            openInstaller(File(path))
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("OPEN_INSTALLER_FAILED", e.message, null)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }


    private fun openInstaller(file: File) {
        require(file.exists()) { "安装包不存在：${file.absolutePath}" }

        val extension = file.extension.lowercase()
        if (extension == "apk" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(settingsIntent)
            throw IllegalStateException("请允许追番喵安装未知应用后，再点击打开安装包")
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } else {
            Uri.fromFile(file)
        }
        val mimeType = if (extension == "apk") {
            "application/vnd.android.package-archive"
        } else {
            "application/octet-stream"
        }

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(intent)
    }

    /// 同步切换：启用目标 alias（或 MainActivity 默认入口），禁用所有其他启动器入口。
    /// 使用 DONT_KILL_APP 让当前进程继续运行；launcher 进程会异步刷新图标。
    private fun applyIcon(alias: String?) {
        val pm = packageManager
        val pkg = packageName
        val mainComponent = ComponentName(pkg, "$pkg.MainActivity")

        if (alias == null) {
            // 默认图标：启用 MainActivity，禁用所有 alias
            pm.setComponentEnabledSetting(
                mainComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            for (a in allAliases) {
                val cn = ComponentName(pkg, "$pkg.$a")
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        } else {
            require(allAliases.contains(alias)) { "Unknown alias: $alias" }
            val targetComponent = ComponentName(pkg, "$pkg.$alias")
            // 先启用目标，再禁用其他（避免一瞬间没有任何 launcher 入口）
            pm.setComponentEnabledSetting(
                targetComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            pm.setComponentEnabledSetting(
                mainComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            for (a in allAliases) {
                if (a == alias) continue
                val cn = ComponentName(pkg, "$pkg.$a")
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }

    private fun getCurrentIcon(): String? {
        val pm = packageManager
        val pkg = packageName
        for (a in allAliases) {
            val cn = ComponentName(pkg, "$pkg.$a")
            val state = pm.getComponentEnabledSetting(cn)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return a
        }
        return null
    }
}
