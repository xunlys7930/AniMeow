package com.animeow.app.data.reader.legado

import android.content.Context
import android.content.Intent

internal enum class LegadoOpenResult(val feedback: String? = null) {
    BOOK_OPENED,
    APP_OPENED("已打开《开源阅读》，请从书架选择这本书"),
    UNAVAILABLE("无法打开《开源阅读》，请确认本机已安装并启用客户端"),
}

internal object LegadoLauncher {
    private const val READ_BOOK_ACTIVITY = "io.legado.app.ui.book.read.ReadBookActivity"
    val KNOWN_PACKAGES = listOf(
        "io.legado.app.release",
        "io.legado.app",
        "io.legado.app.beta",
        "io.legado.play",
        "gedoor.monkeybook",
    )

    fun openLegado(context: Context, legadoBookUrl: String): LegadoOpenResult {
        val launchIntents = KNOWN_PACKAGES.mapNotNull(context.packageManager::getLaunchIntentForPackage)
        if (legadoBookUrl.isNotBlank()) {
            for (launchIntent in launchIntents) {
                val pkg = launchIntent.component?.packageName ?: continue
                val readerIntent = Intent().setClassName(pkg, READ_BOOK_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("bookUrl", legadoBookUrl)
                val reader = context.packageManager.resolveActivity(readerIntent, 0)?.activityInfo
                if (reader?.exported == true && runCatching { context.startActivity(readerIntent) }.isSuccess) {
                    return LegadoOpenResult.BOOK_OPENED
                }
            }
        }

        for (launchIntent in launchIntents) {
            if (runCatching { context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
                return LegadoOpenResult.APP_OPENED
            }
        }
        return LegadoOpenResult.UNAVAILABLE
    }
}
