package com.animeow.app.data.preferences

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ConfigurationProfile(
    val id: String,
    val name: String,
    val createdAt: String,
    val updatedAt: String,
    val configuration: ConfigurationBundle,
) {
    /** Compatibility accessor used by existing appearance summaries. */
    val settings: com.animeow.app.ui.theme.AppearanceSettings get() = configuration.appearance
}

class ConfigurationProfileRepository(
    context: Context,
    private val appearancePreferences: AppearancePreferences = AppearancePreferences(context),
    private val trackerPreferences: TrackerPreferences = TrackerPreferences(context),
    private val discoveryPreferences: DiscoveryPreferences = DiscoveryPreferences(context),
    private val calendarPreferences: CalendarPreferences = CalendarPreferences(context),
    private val editorPreferences: AnimeEditorPreferences = AnimeEditorPreferences(context),
    private val brandingPreferences: BrandingPreferences = BrandingPreferences(context),
) {
    private val appContext = context.applicationContext
    private val profileFile = File(appContext.filesDir, PROFILE_FILE_NAME)
    private val fileMutex = Mutex()
    private val applyMutex = Mutex()

    suspend fun list(): List<ConfigurationProfile> = withContext(Dispatchers.IO) {
        fileMutex.withLock { readProfiles().sortedByDescending(ConfigurationProfile::updatedAt) }
    }

    suspend fun snapshotCurrent(): ConfigurationBundle = coroutineScope {
        val appearance = async { appearancePreferences.snapshot() }
        val tracker = async { trackerPreferences.snapshot() }
        val discovery = async { discoveryPreferences.snapshot() }
        val calendar = async { calendarPreferences.snapshot() }
        val editor = async { editorPreferences.snapshot() }
        val branding = async { brandingPreferences.snapshot() }
        ConfigurationBundle(
            appearance = appearance.await(),
            tracker = tracker.await(),
            discovery = discovery.await(),
            calendar = calendar.await(),
            editor = editor.await(),
            branding = PortableBrandingSettings.from(branding.await()),
        )
    }

    suspend fun save(name: String, configuration: ConfigurationBundle): ConfigurationProfile =
        withContext(Dispatchers.IO) {
            fileMutex.withLock {
                val cleanName = sanitizeName(name)
                val profiles = readProfiles().toMutableList()
                require(profiles.size < MAX_PROFILES) { "最多保存 $MAX_PROFILES 个配置档案" }
                val now = Instant.now().toString()
                val profile = ConfigurationProfile(
                    id = UUID.randomUUID().toString(),
                    name = cleanName,
                    createdAt = now,
                    updatedAt = now,
                    configuration = configuration,
                )
                profiles += profile
                writeProfiles(profiles)
                profile
            }
        }

    suspend fun saveCurrent(name: String): ConfigurationProfile = save(name, snapshotCurrent())

    suspend fun duplicate(profile: ConfigurationProfile): ConfigurationProfile =
        save("${profile.name} 副本", profile.configuration)

    suspend fun delete(profileId: String) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val profiles = readProfiles()
            writeProfiles(profiles.filterNot { it.id == profileId })
        }
    }

    suspend fun apply(profile: ConfigurationProfile) = applyConfiguration(profile.configuration)

    /** Applies all sections present in the bundle and rolls back if any store rejects the change. */
    suspend fun applyConfiguration(configuration: ConfigurationBundle) {
        applyMutex.withLock {
            val rollback = snapshotCurrent()
            try {
                applyUnchecked(configuration)
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    runCatching { applyUnchecked(rollback) }
                        .exceptionOrNull()
                        ?.let(error::addSuppressed)
                }
                throw error
            }
        }
    }

    suspend fun exportCurrent(uri: Uri, name: String) = export(uri, name, snapshotCurrent())

    suspend fun export(uri: Uri, name: String, configuration: ConfigurationBundle) =
        withContext(Dispatchers.IO) {
            val now = Instant.now().toString()
            val root = JSONObject()
                .put("type", FORMAT_TYPE)
                .put("version", FORMAT_VERSION)
                .put(
                    "profile",
                    profileToJson(
                        ConfigurationProfile(
                            id = "exported",
                            name = sanitizeName(name),
                            createdAt = now,
                            updatedAt = now,
                            configuration = configuration,
                        ),
                    ),
                )
            appContext.contentResolver.openOutputStream(uri, "w")
                ?.bufferedWriter(Charsets.UTF_8)
                ?.use { writer -> writer.write(root.toString(2)) }
                ?: error("无法写入配置文件")
        }

    suspend fun import(uri: Uri): ConfigurationProfile = withContext(Dispatchers.IO) {
        val text = appContext.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_IMPORT_BYTES) { "配置文件过大" }
                output.write(buffer, 0, read)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        } ?: error("无法读取配置文件")
        val root = JSONObject(text)
        val profileJson = when {
            root.optString("type") == FORMAT_TYPE -> root.getJSONObject("profile")
            root.has("configuration") || root.has("settings") -> root
            root.has("appearance") -> JSONObject().put("name", "导入配置").put("configuration", root)
            else -> JSONObject().put("name", "导入配置").put("settings", root)
        }
        val imported = profileFromJson(profileJson).copy(
            id = UUID.randomUUID().toString(),
            name = sanitizeName(profileJson.optString("name", "导入配置")),
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString(),
        )
        fileMutex.withLock {
            val profiles = readProfiles().toMutableList()
            require(profiles.size < MAX_PROFILES) { "最多保存 $MAX_PROFILES 个配置档案" }
            profiles += imported
            writeProfiles(profiles)
        }
        imported
    }

    /** Returns a validated, canonical snapshot for inclusion in a full native backup. */
    internal suspend fun snapshotJson(): String = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            if (profileFile.exists()) normalizeSnapshotJson(profileFile.readText(Charsets.UTF_8))
            else emptySnapshotJson()
        }
    }

    /** Atomically replaces all saved profiles with a previously validated backup snapshot. */
    internal suspend fun restoreSnapshotJson(snapshot: String) = withContext(Dispatchers.IO) {
        fileMutex.withLock { writeSnapshotText(normalizeSnapshotJson(snapshot)) }
    }

    private suspend fun applyUnchecked(configuration: ConfigurationBundle) {
        appearancePreferences.restore(configuration.appearance)
        configuration.tracker?.let { trackerPreferences.save(it) }
        configuration.discovery?.let { discoveryPreferences.save(it) }
        configuration.calendar?.let { calendarPreferences.save(it) }
        configuration.editor?.let { editorPreferences.save(it) }
        configuration.branding?.let { portable ->
            brandingPreferences.save(portable.applyTo(brandingPreferences.snapshot()))
        }
    }

    private fun readProfiles(): List<ConfigurationProfile> {
        if (!profileFile.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(profileFile.readText(Charsets.UTF_8))
            val array = root.optJSONArray("profiles") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { json ->
                        runCatching { profileFromJson(json) }.getOrNull()?.let(::add)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeProfiles(profiles: List<ConfigurationProfile>) {
        val root = JSONObject()
            .put("schemaVersion", PROFILE_SCHEMA_VERSION)
            .put("profiles", JSONArray().apply { profiles.forEach { put(profileToJson(it)) } })
        writeSnapshotText(root.toString())
    }

    private fun writeSnapshotText(value: String) {
        val temp = File(profileFile.parentFile, "${profileFile.name}.tmp")
        temp.writeText(value, Charsets.UTF_8)
        runCatching {
            Files.move(
                temp.toPath(),
                profileFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temp.toPath(), profileFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sanitizeName(value: String): String {
        val clean = sanitizeProfileName(value)
        require(clean.isNotEmpty()) { "请输入配置名称" }
        return clean
    }

    internal companion object {
        const val PROFILE_FILE_NAME = "configuration_profiles.json"
        const val FORMAT_TYPE = "animeow-configuration-profile"
        const val FORMAT_VERSION = 2
        const val PROFILE_SCHEMA_VERSION = 2
        const val MAX_PROFILES = 24
        const val MAX_IMPORT_BYTES = 1024 * 1024

        fun emptySnapshotJson(): String = JSONObject()
            .put("schemaVersion", PROFILE_SCHEMA_VERSION)
            .put("profiles", JSONArray())
            .toString()

        fun normalizeSnapshotJson(value: String): String {
            require(value.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) { "配置档案文件过大" }
            val root = JSONObject(value)
            val source = root.optJSONArray("profiles") ?: JSONArray()
            require(source.length() <= MAX_PROFILES) { "配置档案数量超过上限" }
            val normalized = JSONArray()
            for (index in 0 until source.length()) {
                val profileJson = source.optJSONObject(index) ?: error("配置档案格式损坏")
                val profile = profileFromJson(profileJson)
                normalized.put(profileToJson(profile))
            }
            return JSONObject()
                .put("schemaVersion", PROFILE_SCHEMA_VERSION)
                .put("profiles", normalized)
                .toString()
        }
    }
}

private fun profileToJson(profile: ConfigurationProfile): JSONObject = JSONObject()
    .put("id", profile.id)
    .put("name", profile.name)
    .put("createdAt", profile.createdAt)
    .put("updatedAt", profile.updatedAt)
    // Keep this mirror so AniMeow v1 can still import the appearance portion.
    .put("settings", AppearanceSettingsCodec.toJson(profile.configuration.appearance))
    .put("configuration", ConfigurationBundleCodec.toJson(profile.configuration))

private fun profileFromJson(json: JSONObject): ConfigurationProfile {
    val configuration = json.optJSONObject("configuration")?.let(ConfigurationBundleCodec::fromJson)
        ?: ConfigurationBundle(
            appearance = AppearanceSettingsCodec.fromJson(
                json.optJSONObject("settings") ?: json.optJSONObject("appearance")
                ?: error("配置档案缺少设置内容"),
            ),
        )
    return ConfigurationProfile(
        id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
        name = sanitizeProfileName(json.optString("name", "未命名配置")).ifBlank { "未命名配置" },
        createdAt = json.optString("createdAt").ifBlank { Instant.now().toString() },
        updatedAt = json.optString("updatedAt").ifBlank { Instant.now().toString() },
        configuration = configuration,
    )
}

private fun sanitizeProfileName(value: String): String =
    value.trim().replace(Regex("[\\r\\n\\t]+"), " ").take(32)
