package com.animeow.app.data.announcements

import android.content.Context
import com.animeow.app.AniMeowApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class AnnouncementState(
    val items: List<AppAnnouncement> = emptyList(),
    val readIds: Set<Long> = emptySet(),
    val isDeveloper: Boolean = false,
    val loaded: Boolean = false,
    val notificationsEnabled: Boolean = true,
) {
    val unreadCount: Int get() = items.count { !it.withdrawn && it.id !in readIds }
}

internal fun announcementsToNotify(items: List<AppAnnouncement>, readIds: Set<Long>, watermark: Long) =
    items.filter { !it.withdrawn && it.id > watermark && it.id !in readIds }

class AnnouncementRepository(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("app_announcements", Context.MODE_PRIVATE)
    private val service = AnnouncementService(this.context)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AnnouncementState(
        items = runCatching { parseAnnouncements(JSONArray(preferences.getString("items", "[]"))) }.getOrDefault(emptyList()),
        readIds = preferences.getStringSet("read", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet(),
        loaded = preferences.contains("items"),
        notificationsEnabled = preferences.getBoolean("notifications", true),
    ))
    val state = _state.asStateFlow()
    val configured get() = service.configured

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!configured) return@withLock
            val feed = service.list()
            val active = feed.items.filterNot { it.withdrawn }
            val read = _state.value.readIds.intersect(active.map { it.id }.toSet())
            val newest = active.maxOfOrNull { it.id } ?: 0L
            val firstSync = !preferences.contains("notified")
            preferences.edit().putString("items", active.toAnnouncementJson().toString())
                .putStringSet("read", read.map(Long::toString).toSet()).apply()
            _state.update { it.copy(items = active, readIds = read, loaded = true, isDeveloper = feed.isDeveloper) }
            // Existing announcements remain readable without sending a burst on first installation.
            if (firstSync) preferences.edit().putLong("notified", newest).apply()
            val pending = announcementsToNotify(active, read, preferences.getLong("notified", 0))
            val foreground = (context as? AniMeowApplication)?.isInForeground == true
            if (!firstSync && pending.isNotEmpty() && !foreground && _state.value.notificationsEnabled &&
                AnnouncementNotifications.show(context, pending)) {
                preferences.edit().putLong("notified", newest).apply()
            }
            if (pending.isEmpty()) AnnouncementNotifications.cancel(context)
        }
    }

    suspend fun markRead(id: Long? = null) = mutex.withLock {
        val current = _state.value
        val ids = if (id == null) current.items.map { it.id }.toSet() else setOf(id).intersect(current.items.map { it.id }.toSet())
        val read = current.readIds + ids
        preferences.edit().putStringSet("read", read.map(Long::toString).toSet()).apply()
        _state.update { it.copy(readIds = read) }
        if (announcementsToNotify(current.items, read, preferences.getLong("notified", 0)).isEmpty()) {
            AnnouncementNotifications.cancel(context)
        }
    }

    fun setNotifications(enabled: Boolean) {
        preferences.edit().putBoolean("notifications", enabled).apply()
        _state.update { it.copy(notificationsEnabled = enabled) }
        if (!enabled) AnnouncementNotifications.cancel(context)
        AnnouncementNotifications.schedule(context, enabled)
    }

    fun clearDeveloperAccess() { _state.update { it.copy(isDeveloper = false) } }
}
