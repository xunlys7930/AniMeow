package com.animeow.app.ui.preferences

import com.animeow.app.data.preferences.personalizationSections
import com.animeow.app.data.preferences.personalizationFields

internal typealias CustomizationSection = com.animeow.app.data.preferences.PersonalizationSection
internal val customizationSections get() = personalizationSections

internal fun matchingCustomizationSections(query: String): List<CustomizationSection> {
    val tokens = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val sections = customizationSections + pageCustomizationSection
    fun matches(section: CustomizationSection, allowPartialPhrase: Boolean): Boolean =
        tokens.all { token ->
            (section.keywords + section.label + section.title + section.key).any {
                it.contains(token, ignoreCase = true) ||
                    (allowPartialPhrase && it.length >= 2 && token.contains(it, ignoreCase = true))
            }
        }
    // Prefer the named control; use phrase decomposition only when no direct result exists.
    return sections.filter { matches(it, false) }.ifEmpty {
        sections.filter { matches(it, true) }
    }
}

internal val pageCustomizationSection = CustomizationSection(
    "pages", "页面", listOf("页面"), "页面与卡片", "首页与详情、收藏卡片、底栏外形",
)
internal val appearanceOverviewSections: List<CustomizationSection> get() =
    listOf("visual", "size", "pages", "motion", "icons", "brand", "profile").mapNotNull(::customizationSection)
internal val pageCustomizationSections: List<CustomizationSection> get() =
    listOf("layout", "card", "nav").mapNotNull(::customizationSection)
internal fun customizationSection(key: String?): CustomizationSection? =
    if (key == "pages") pageCustomizationSection else customizationSections.firstOrNull { it.key == key }

/** Stable leaf keys also keep old search/shortcut links valid. */
internal fun visibleCustomizationKeys(key: String?): Set<String> = when (key) {
    "card" -> setOf("card", "cover")
    "cover" -> setOf("cover")
    "pages", null -> emptySet()
    else -> setOfNotNull(customizationSection(key)?.key)
}
internal fun customizationCategories(key: String): Set<String> = when (key) {
    "pages" -> setOf("layout", "card", "cover", "nav")
    else -> visibleCustomizationKeys(key)
}

internal data class CustomizationSearchTarget(val key: String, val section: String, val title: String, val path: String)

internal fun customizationControlTarget(query: String): CustomizationSearchTarget? {
    val field = personalizationFields.values.flatten().firstOrNull { it.control?.aliases?.contains(query.trim()) == true } ?: return null
    val control = field.control!!
    val key = control.key
    val section = field.searchSection!!
    val title = control.title
    val root = if (section == "behavior") "设置" else "外观与阅读"
    return CustomizationSearchTarget(key, section, title, "$root → ${customizationSection(section)!!.title} → $title")
}

internal fun searchCustomizationTargets(query: String): List<CustomizationSearchTarget> {
    if (query.isBlank()) return emptyList()
    customizationControlTarget(query)?.let { return listOf(it) }
    val namedFields = personalizationFields.values.flatten().filter { it.label == query.trim() && it.searchSection != null }
    val namedSections = namedFields.mapNotNull { customizationSection(it.searchSection) }.distinctBy { it.key }
    val sections = if (namedSections.isNotEmpty() && customizationSections.none { it.title == query.trim() }) namedSections
        else matchingCustomizationSections(query)
    return sections.map {
        val root = if (it.key in setOf("behavior", "navigation")) "设置" else "外观与阅读"
        CustomizationSearchTarget("section-${it.key}", it.key, it.title, "$root → ${it.title}")
    }
}
