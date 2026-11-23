package com.animeow.app.data

enum class BatchMetadataMode(val label: String, val action: String) {
    KEEP("仅收藏，已有资料不变", "批量收藏"),
    FILL_BLANKS("补全空白资料", "批量补全"),
    OVERWRITE("覆盖公开资料", "批量覆盖"),
}

data class BatchSeriesDestination(val existingSeriesId: Long? = null, val newSeriesName: String? = null)

data class BatchImportSummary(val succeededKeys: Set<String>, val failures: Map<String, String>) {
    val message: String get() = "已处理 ${succeededKeys.size} 部作品" +
        if (failures.isEmpty()) "，已保存到资料库" else "，${failures.size} 部失败，保留勾选可重试"
}
