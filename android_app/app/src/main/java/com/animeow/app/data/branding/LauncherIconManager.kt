package com.animeow.app.data.branding

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.animeow.app.BuildConfig

class LauncherIconManager(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun reconcile(selected: LauncherIcon) {
        val target = component(selected)
        setStateIfNeeded(target, enabled = true, manifestEnabled = selected.enabledByDefault)
        LauncherIcon.entries.asSequence()
            .filterNot { it == selected }
            .forEach { option ->
                setStateIfNeeded(
                    component = component(option),
                    enabled = false,
                    manifestEnabled = option.enabledByDefault,
                )
            }
        REMOVED_LEGACY_ALIASES.forEach { aliasName ->
            setStateIfNeeded(
                component = ComponentName(
                    appContext.packageName,
                    launcherAliasClassName(BuildConfig.APP_NAMESPACE, aliasName),
                ),
                enabled = false,
                manifestEnabled = false,
            )
        }
    }

    private fun component(icon: LauncherIcon): ComponentName =
        ComponentName(
            appContext.packageName,
            launcherAliasClassName(BuildConfig.APP_NAMESPACE, icon.aliasName),
        )

    private fun setStateIfNeeded(
        component: ComponentName,
        enabled: Boolean,
        manifestEnabled: Boolean,
    ) {
        val current = packageManager.getComponentEnabledSetting(component)
        val currentlyEnabled = when (current) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> false
            else -> manifestEnabled
        }
        if (currentlyEnabled == enabled) return
        packageManager.setComponentEnabledSetting(
            component,
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }

    private companion object {
        val REMOVED_LEGACY_ALIASES = listOf("icon_02")
    }
}

internal fun launcherAliasClassName(namespace: String, aliasName: String): String =
    "${namespace.trimEnd('.')}.${aliasName.trimStart('.')}"
