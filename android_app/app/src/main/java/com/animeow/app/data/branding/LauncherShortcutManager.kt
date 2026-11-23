package com.animeow.app.data.branding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import com.animeow.app.BuildConfig
import com.animeow.app.MainActivity
import com.animeow.app.R
import java.io.File

enum class LauncherAction(val key: String, val label: String, val icon: Int) {
    ADD_ANIME("add_anime", "添加番剧", R.drawable.shortcut_add_anime),
    IMAGE_SEARCH("image_search", "以图搜番", R.drawable.shortcut_image_search),
    SEARCH_LIBRARY("search_library", "搜索资料库", R.drawable.shortcut_search_library),
    ;

    val intentAction: String get() = "${BuildConfig.APP_NAMESPACE}.shortcut.$key"

    companion object {
        fun fromIntentAction(action: String?): LauncherAction? = entries.firstOrNull { it.intentAction == action }
    }
}

/** Shortcuts belong to the enabled launcher alias; their explicit intents always target MainActivity. */
class LauncherShortcutManager(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(ShortcutManager::class.java)
    val isPinSupported: Boolean get() = manager?.isRequestPinShortcutSupported == true

    fun publishForLauncher(selected: LauncherIcon) {
        val service = manager ?: return
        val activity = launcherComponent(selected)
        val shortcuts = LauncherAction.entries.take(service.maxShortcutCountPerActivity).mapIndexed { index, action ->
            ShortcutInfo.Builder(appContext, action.key)
                .setActivity(activity)
                .setShortLabel(action.label)
                .setLongLabel(action.label)
                .setIcon(Icon.createWithResource(appContext, action.icon))
                .setIntent(launchIntent(action.intentAction))
                .setRank(index)
                .build()
        }
        service.dynamicShortcuts = shortcuts
        // A pinned custom icon must stay usable after a different preset alias is enabled.
        service.pinnedShortcuts.firstOrNull { it.id == CUSTOM_ICON_ID }?.let { pinned ->
            service.updateShortcuts(listOf(ShortcutInfo.Builder(appContext, CUSTOM_ICON_ID)
                .setActivity(activity)
                .setShortLabel(pinned.shortLabel ?: "AniMeow")
                .setIntent(launchIntent(Intent.ACTION_MAIN))
                .build()))
        }
    }

    fun addOrUpdateCustomIcon(file: File, selected: LauncherIcon): String {
        val service = checkNotNull(manager) { "当前桌面不支持自定义入口" }
        check(isPinSupported) { "当前桌面不支持添加自定义入口，可更换桌面启动器后重试" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth in 1..1_024 && bounds.outHeight in 1..1_024) { "图标文件已失效，请重新选择图片" }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path)) { "图标文件已失效，请重新选择图片" }
        try {
            val shortcut = ShortcutInfo.Builder(appContext, CUSTOM_ICON_ID)
                .setActivity(launcherComponent(selected))
                .setShortLabel("AniMeow")
                .setLongLabel("打开 AniMeow")
                .setIcon(Icon.createWithAdaptiveBitmap(bitmap))
                .setIntent(launchIntent(Intent.ACTION_MAIN))
                .build()
            if (service.pinnedShortcuts.any { it.id == CUSTOM_ICON_ID }) {
                service.enableShortcuts(listOf(CUSTOM_ICON_ID))
                check(service.updateShortcuts(listOf(shortcut))) { "桌面正在限制更新，请稍后重试" }
                return "桌面入口图标已更新，桌面可能需要几秒刷新"
            }
            check(service.requestPinShortcut(shortcut, null)) { "桌面未接受添加请求，请稍后重试" }
            return "已请求添加到桌面，请在系统提示中确认"
        } finally {
            bitmap.recycle()
        }
    }

    private fun launcherComponent(icon: LauncherIcon) = ComponentName(
        appContext.packageName, launcherAliasClassName(BuildConfig.APP_NAMESPACE, icon.aliasName),
    )

    private fun launchIntent(action: String) = Intent(appContext, MainActivity::class.java)
        .setAction(action)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    companion object {
        const val CUSTOM_ICON_ID = "animeow_custom_icon"
    }
}
