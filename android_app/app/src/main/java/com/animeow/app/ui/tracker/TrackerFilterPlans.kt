package com.animeow.app.ui.tracker

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.animeow.app.data.preferences.CollectionFilterConditions
import com.animeow.app.ui.components.CollectionFilterPlans

@Composable
internal fun TrackerFilterPlans(model: TrackerViewModel, enabled: Boolean = true, subjectType: String? = null) {
    val query by model.query.collectAsStateWithLifecycle()
    val status by model.status.collectAsStateWithLifecycle()
    val sort by model.sort.collectAsStateWithLifecycle()
    val ascending by model.sortAscending.collectAsStateWithLifecycle()
    val advanced by model.advanced.collectAsStateWithLifecycle()
    val tags by model.tags.collectAsStateWithLifecycle()
    val statuses by model.statusOptions.collectAsStateWithLifecycle()
    CollectionFilterPlans("library", CollectionFilterConditions(query, status, advanced.subjectType,
        advanced.tagIds.map { id -> tags.firstOrNull { it.id == id }?.name ?: "已移除标签 #$id" }, advanced.matchAllTags,
        advanced.years.toList(), sort.storageKey, ascending), tags.map { it.name }, statuses, enabled,
        requiredSubjectType = subjectType, onApply = model::applyCollectionFilter)
}
