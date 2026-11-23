package com.animeow.app.util

import android.os.Build
import java.util.Locale

/**
 * ROM 厂商检测工具
 * 用于识别国产ROM，提供针对性的后台设置引导
 */
enum class RomBrand {
    XIAOMI,      // 小米 MIUI / HyperOS
    HUAWEI,      // 华为 EMUI / HarmonyOS
    OPPO,        // OPPO ColorOS
    VIVO,        // vivo OriginOS / FuntouchOS
    REALME,      // realme UI
    ONEPLUS,     // 一加
    SAMSUNG,     // 三星 One UI
    MEIZU,       // 魅族 Flyme
    SMARTISAN,   // 锤子 SmartisanOS
    LENOVO,      // 联想 ZUI
    MOTOROLA,    // 摩托罗拉
    GOOGLE,      // 原生 Android / Pixel
    OTHER,       // 其他
}

/**
 * 一个厂商设置入口。
 *
 * 旧实现把 Activity 全名当 action 用、又用 `setPackage(本应用包名)` 限定范围，
 * 两条路都必然解析失败，按钮只会落到"应用信息"页。这里把两类目标分开表达：
 * [component] 走 `setClassName`，[action] 走 `setPackage(厂商包名)`。
 */
data class RomSettingTarget(
    val label: String,
    val component: Pair<String, String>? = null,
    val action: String? = null,
    val actionPackage: String? = null,
)

object RomDetector {

    /** 获取当前 ROM 厂商 */
    val brand: RomBrand by lazy { detectBrand() }

    /** 是否为国产 ROM（通常有更严格的后台限制） */
    val isChineseRom: Boolean by lazy {
        brand in setOf(
            RomBrand.XIAOMI, RomBrand.HUAWEI, RomBrand.OPPO, RomBrand.VIVO,
            RomBrand.REALME, RomBrand.ONEPLUS, RomBrand.MEIZU, RomBrand.SMARTISAN,
            RomBrand.LENOVO
        )
    }

    /**
     * 是否值得展示后台限制引导。
     *
     * 不能只看 [isChineseRom]：三星等非国产系统同样会限制后台，而它的引导文字是有的，
     * 之前的 `if (!isChinese) return` 让三星分支永远显示不出来。
     */
    val hasBackgroundGuide: Boolean by lazy { brand != RomBrand.OTHER }

    /**
     * 获取跳转到电池优化/自启动设置页的目标列表，按优先级排列，逐一尝试直到成功。
     *
     * 组件类名来自各厂商的公开实现，仍随系统版本变动；解析不到时会退回应用信息页，
     * 所以这里只保证"机制正确"，不承诺每个机型都能直达。
     */
    fun getBatterySettingIntents(): List<RomSettingTarget> {
        return when (brand) {
            RomBrand.XIAOMI -> listOf(
                RomSettingTarget(
                    "小米自启动管理",
                    component = "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
                RomSettingTarget(
                    "小米省电策略",
                    component = "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                ),
            )
            RomBrand.HUAWEI -> listOf(
                RomSettingTarget(
                    "华为启动管理",
                    component = "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                RomSettingTarget(
                    "华为受保护应用",
                    component = "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
                ),
            )
            RomBrand.OPPO, RomBrand.REALME -> listOf(
                RomSettingTarget(
                    "OPPO/realme 自启动",
                    component = "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
                RomSettingTarget(
                    "OPPO/realme 耗电管理",
                    component = "com.coloros.oppoguardelf" to "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
                ),
            )
            RomBrand.VIVO -> listOf(
                RomSettingTarget(
                    "vivo 后台高耗电",
                    component = "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
                RomSettingTarget(
                    "vivo/iQOO 白名单",
                    component = "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                ),
            )
            RomBrand.ONEPLUS -> listOf(
                RomSettingTarget(
                    "一加后台控制",
                    component = "com.oneplus.security" to "com.oneplus.security.settings.activity.BackgroundAppsControlActivity",
                ),
            )
            RomBrand.MEIZU -> listOf(
                RomSettingTarget(
                    "魅族待机耗电管理",
                    component = "com.meizu.safe" to "com.meizu.safe.powerui.PowerAppPermissionActivity",
                ),
            )
            RomBrand.SAMSUNG -> listOf(
                RomSettingTarget(
                    "三星电池优化",
                    component = "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
                ),
            )
            else -> emptyList()
        }
    }

    /** 获取厂商设置引导的说明文字 */
    fun getSettingGuide(): String {
        return when (brand) {
            RomBrand.XIAOMI -> "小米手机请在「安全中心 → 应用管理 → AniMeow → 省电策略」中选择「无限制」，并在「自启动」中允许"
            RomBrand.HUAWEI -> "华为手机请在「设置 → 电池 → 启动管理」中关闭 AniMeow 的自动管理，允许后台活动和自启动"
            RomBrand.OPPO, RomBrand.REALME -> "OPPO/realme 请在「设置 → 电池 → 应用耗电管理 → AniMeow」中允许「允许完全后台行为」和「自启动」"
            RomBrand.VIVO -> "vivo/iQOO 请在「i管家 → 应用管理 → 权限管理 → 自启动」中允许 AniMeow，并在「后台高耗电」中允许"
            RomBrand.ONEPLUS -> "一加手机请在「设置 → 电池 → 电池优化 → AniMeow」中选择「不优化」"
            RomBrand.MEIZU -> "魅族手机请在「手机管家 → 电量管理 → 待机耗电管理」中将 AniMeow 加入待机耗电白名单"
            RomBrand.SAMSUNG -> "三星手机请在「设置 → 电池和设备维护 → 电池 → 后台使用限制 → 从不休眠的应用」中添加 AniMeow"
            RomBrand.GOOGLE, RomBrand.MOTOROLA -> "请在「设置 → 电池 → 电池优化 → 所有应用 → AniMeow → 不优化」"
            RomBrand.LENOVO, RomBrand.SMARTISAN, RomBrand.OTHER -> "请在系统设置的电池或安全中心中，将 AniMeow 加入后台保护白名单并允许自启动"
        }
    }

    private fun detectBrand(): RomBrand {
        val brand = Build.BRAND.lowercase(Locale.ROOT)
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val product = Build.PRODUCT.lowercase(Locale.ROOT)
        val hardware = Build.HARDWARE.lowercase(Locale.ROOT)
        val fingerprint = Build.FINGERPRINT.lowercase(Locale.ROOT)

        return when {
            isXiaomi(brand, manufacturer, product, hardware) -> RomBrand.XIAOMI
            isHuawei(brand, manufacturer, product, hardware) -> RomBrand.HUAWEI
            isOppo(brand, manufacturer, product, hardware) -> RomBrand.OPPO
            isVivo(brand, manufacturer, product, hardware) -> RomBrand.VIVO
            isRealme(brand, manufacturer, product) -> RomBrand.REALME
            isOneplus(brand, manufacturer, product, hardware) -> RomBrand.ONEPLUS
            isSamsung(brand, manufacturer, fingerprint) -> RomBrand.SAMSUNG
            isMeizu(brand, manufacturer, product) -> RomBrand.MEIZU
            isSmartisan(brand, manufacturer, product) -> RomBrand.SMARTISAN
            isLenovo(brand, manufacturer, product) -> RomBrand.LENOVO
            isMotorola(brand, manufacturer, product) -> RomBrand.MOTOROLA
            isGoogle(brand, manufacturer, product, hardware) -> RomBrand.GOOGLE
            else -> RomBrand.OTHER
        }
    }

    private fun isXiaomi(brand: String, manufacturer: String, product: String, hardware: String): Boolean {
        return brand == "xiaomi" || manufacturer == "xiaomi" ||
            product.contains("miui") || product.contains("hyperos") ||
            hardware.contains("qcom") && product.contains("xiaomi")
    }

    private fun isHuawei(brand: String, manufacturer: String, product: String, hardware: String): Boolean {
        return brand == "huawei" || brand == "honor" ||
            manufacturer == "huawei" || manufacturer == "honor" ||
            product.contains("huawei") || product.contains("honor") ||
            hardware.contains("kirin") || hardware.contains("balong")
    }

    private fun isOppo(brand: String, manufacturer: String, product: String, hardware: String): Boolean {
        return brand == "oppo" || manufacturer == "oppo" ||
            product.contains("oppo") || product.contains("coloros") ||
            hardware.contains("oplus")
    }

    private fun isVivo(brand: String, manufacturer: String, product: String, hardware: String): Boolean {
        return brand == "vivo" || manufacturer == "bbk" || manufacturer == "vivo" ||
            product.contains("vivo") || product.contains("funtouch") || product.contains("originos") ||
            hardware.contains("mtk") && product.contains("vivo")
    }

    private fun isRealme(brand: String, manufacturer: String, product: String): Boolean {
        return brand == "realme" || manufacturer == "realme" || product.contains("realme")
    }

    private fun isOneplus(brand: String, manufacturer: String, product: String, hardware: String): Boolean {
        return brand == "oneplus" || manufacturer == "oneplus" ||
            product.contains("oneplus") || hardware.contains("oneplus")
    }

    private fun isSamsung(brand: String, manufacturer: String, fingerprint: String): Boolean {
        return brand == "samsung" || manufacturer == "samsung" ||
            fingerprint.contains("samsung")
    }

    private fun isMeizu(brand: String, manufacturer: String, product: String): Boolean {
        return brand == "meizu" || manufacturer == "meizu" ||
            product.contains("meizu") || product.contains("flyme")
    }

    private fun isSmartisan(brand: String, manufacturer: String, product: String): Boolean {
        // 曾经还判 product.contains("od")，但这会把摩托罗拉（codename odessa 之类）等机型
        // 因为 when 的顺序误判成锤子，并给出错误的引导。
        return brand == "smartisan" || manufacturer == "smartisan" || product.contains("smartisan")
    }

    private fun isLenovo(brand: String, manufacturer: String, product: String): Boolean {
        return brand == "lenovo" || manufacturer == "lenovo" ||
            product.contains("lenovo") || product.contains("zuk")
    }

    private fun isMotorola(brand: String, manufacturer: String, product: String): Boolean {
        return brand == "motorola" || manufacturer == "motorola" ||
            product.contains("motorola") || product.contains("moto")
    }

    private fun isGoogle(brand: String, manufacturer: String, product: String, hardware: String): Boolean {
        return brand == "google" || manufacturer == "google" ||
            product.contains("pixel") || product.contains("google") ||
            hardware.contains("marlin") || hardware.contains("sailfish") || hardware.contains("walleye")
    }
}
