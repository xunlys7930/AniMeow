package com.animeow.app.data.games

internal fun gameTagNames(value: String): List<String> = value.split(',', '，').map(String::trim).filter(String::isNotBlank).distinct()

internal fun updatedGameTags(current: String, selected: String, remove: Boolean): String {
    val names = gameTagNames(selected)
    return (if (remove) gameTagNames(current) - names.toSet() else (gameTagNames(current) + names).distinct())
        .joinToString("，").also { require(it.length <= 1024) { "游戏标签请控制在 1024 字以内" } }
}
