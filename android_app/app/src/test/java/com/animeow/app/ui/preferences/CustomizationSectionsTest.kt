package com.animeow.app.ui.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomizationSectionsTest {
    @Test
    fun emptyQueryKeepsAllCategoriesInDisplayOrder() {
        assertEquals(listOf("visual", "icons", "layout", "size", "card", "cover", "motion", "nav", "behavior", "navigation", "brand", "profile", "pages"),
            matchingCustomizationSections("  ").map { it.key })
    }

    @Test
    fun searchUsesAllWordsAndFindsHiddenCategoryControls() {
        assertEquals(listOf("card"), matchingCustomizationSections("  评分   底色  ").map { it.key })
        assertEquals(listOf("size"), matchingCustomizationSections("字体").map { it.key })
        assertEquals(listOf("size"), matchingCustomizationSections("字体大小").map { it.key })
        assertEquals(listOf("visual"), matchingCustomizationSections("miuix").map { it.key })
        assertEquals(listOf("visual", "icons"), matchingCustomizationSections("配色").map { it.key })
        assertEquals(listOf("visual", "card"), matchingCustomizationSections("渐变 流动").map { it.key })
        assertEquals(listOf("visual"), matchingCustomizationSections("独立主题色").map { it.key })
        assertEquals(listOf("card"), matchingCustomizationSections("评分 渐变").map { it.key })
        assertEquals(listOf("icons", "card"), matchingCustomizationSections("图标 颜色").map { it.key })
        assertEquals(listOf("profile"), matchingCustomizationSections("档案 导出").map { it.key })
        assertEquals(listOf("icons"), matchingCustomizationSections("图标 变体").map { it.key })
        assertEquals(listOf("icons"), matchingCustomizationSections("单独 图标").map { it.key })
    }

    @Test
    fun unrelatedWordsProduceAnEmptyResultInsteadOfAnInvalidJump() {
        assertTrue(matchingCustomizationSections("不存在的设置xyz").isEmpty())
        assertTrue(matchingCustomizationSections("字体 账号登录").isEmpty())
    }

    @Test fun appearanceOverviewExcludesOperatingHabitsAndUsesAnObjectHub() {
        assertEquals(listOf("visual", "size", "pages", "motion", "icons", "brand", "profile"), appearanceOverviewSections.map { it.key })
        assertEquals(listOf("layout", "card", "nav"), pageCustomizationSections.map { it.key })
        assertTrue(appearanceOverviewSections.none { it.key in setOf("behavior", "navigation") })
        assertEquals("页面与卡片", customizationSection("pages")?.title)
    }

    @Test fun legacyLeafKeysStillResolveAndCardAndCoverShareTheirObject() {
        listOf("visual", "icons", "layout", "size", "card", "cover", "motion", "nav", "brand", "profile").forEach {
            assertEquals(it, customizationSection(it)?.key)
        }
        assertEquals(setOf("card", "cover"), visibleCustomizationKeys("card"))
        assertEquals(setOf("cover"), visibleCustomizationKeys("cover"))
        assertTrue(visibleCustomizationKeys("pages").isEmpty())
        assertTrue(visibleCustomizationKeys("invalid").isEmpty())
        assertEquals(setOf("layout", "card", "cover", "nav"), customizationCategories("pages"))
    }

    @Test fun operatingHabitSearchNeverReturnsVisualMotionOrLayout() {
        assertEquals(listOf("behavior"), matchingCustomizationSections("进度操作").map { it.key })
        assertEquals(listOf("behavior"), matchingCustomizationSections("返回 退出").map { it.key })
        assertEquals(listOf("behavior"), matchingCustomizationSections("触觉反馈").map { it.key })
        assertEquals(listOf("navigation"), matchingCustomizationSections("启动页").map { it.key })
        assertEquals(listOf("nav"), matchingCustomizationSections("底栏 透明").map { it.key })
        assertEquals(listOf("icons", "layout", "nav"), matchingCustomizationSections("圆角").map { it.key })
    }

    @Test fun conversationalFontQueriesResolveAControlWithBreadcrumbs() {
        listOf("字太小", "字太大", "字号", "文字大小", "字体大小", "字体缩放").forEach { query ->
            val target = searchCustomizationTargets(query).single()
            assertEquals("font-scale", target.key)
            assertEquals("size", target.section)
            assertEquals("文字大小", target.title)
            assertTrue(target.path.contains("字体与阅读"))
        }
    }

    @Test fun searchResultsAreStableAndEmptySearchDoesNotExposeAControlDump() {
        assertTrue(searchCustomizationTargets("").isEmpty())
        assertTrue(searchCustomizationTargets("不存在xyz").isEmpty())
        val targets = searchCustomizationTargets("图标 颜色")
        assertEquals(targets.size, targets.map { it.key }.distinct().size)
        assertEquals(listOf("icons", "card"), targets.map { it.section })
    }

    @Test fun displayedSectionTitlesResolveToTheirOwnSettingsPage() {
        (customizationSections + pageCustomizationSection).forEach { section ->
            val targets = searchCustomizationTargets(section.title)
            assertTrue(section.title, targets.any { it.section == section.key && it.title == section.title })
            assertEquals(section.title, targets.size, targets.map { it.key }.distinct().size)
        }
    }

    @Test fun taskNamesFindPagesVisualProfilesAndReadingSettings() {
        for ((query, section) in listOf("页面" to "pages", "页面 卡片" to "pages",
            "视觉方案" to "profile", "我的视觉方案" to "profile")) {
            val target = searchCustomizationTargets(query).single()
            assertEquals(query, section, target.section)
            assertEquals("外观与阅读 → ${customizationSection(section)!!.title}", target.path)
        }
        assertTrue(searchCustomizationTargets("阅读").any { it.section == "size" })
        assertEquals(setOf("layout", "card", "cover", "nav"), customizationCategories("pages"))
    }

    @Test fun frequentQueriesResolveControlsWithoutChangingStoredSectionKeys() {
        for ((query, key, section) in listOf(
            Triple("底栏透明度", "nav-opacity", "nav"),
            Triple("返回退出方式", "exit-behavior", "behavior"),
            Triple("合并操作", "detail-progress", "behavior"),
        )) {
            val target = searchCustomizationTargets(query).single()
            assertEquals(key, target.key)
            assertEquals(section, target.section)
            assertTrue(target.path.endsWith(target.title))
            assertEquals(target, customizationControlTarget(query))
        }
        assertTrue(searchCustomizationTargets("我的视觉方案").any { it.section == "profile" && it.title == "个性化配置" })
        assertEquals(null, customizationControlTarget("不存在的设置"))
    }
}
