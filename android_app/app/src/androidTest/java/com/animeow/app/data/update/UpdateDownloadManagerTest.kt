package com.animeow.app.data.update

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.BuildConfig
import com.animeow.app.data.cloud.SecureCloudSessionStore
import com.animeow.app.testing.ControlledHttpServer
import com.animeow.app.testing.TestHttpResponse
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class UpdateDownloadManagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun cancelDuringPreflightLeavesNoTrackedSystemDownload() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        ControlledHttpServer {
            started.countDown()
            check(release.await(8, TimeUnit.SECONDS))
            TestHttpResponse(headers = mapOf("Content-Type" to "application/vnd.android.package-archive"))
        }.use { server ->
            try {
                val service = AppUpdateService(context)
                val option = AppUpdateDownloadOption("本地测试", server.baseUrl + "/cancel.apk")
                val info = update("cancel-fixture", option)
                val work = launch(Dispatchers.Default) { service.enqueueDownload(info, option) }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                work.cancel()
                release.countDown()
                work.join()
                assertNull(service.trackedDownload())
            } finally { release.countDown() }
        }
    }

    /** Explicit read-only public download validation; omitted from normal offline runs. */
    @Test fun publicLegacyHttpAndHttpToHttpsRedirectCompleteInSystemDownloader() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("validatePublicDownloads") == "true")
        assertNull("Clear all synthetic sessions before enabling network", SecureCloudSessionStore(context).load())
        val records = JSONArray()
        val root = File(context.getExternalFilesDir(null), "download-213-review").apply { mkdirs() }
        val evidence = File(root, "system-download-results.json")
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val failures = mutableListOf<Throwable>()
        // A host-proxy comparison must keep the synthetic redirect on the emulator.
        // This only changes the test process; public HTTPS keeps normal certificate checks.
        val bypassLocalProxy = InstrumentationRegistry.getArguments().getString("bypassLocalProxy") == "true"
        val originalNonProxyHosts = System.getProperty("http.nonProxyHosts")
        if (bypassLocalProxy) System.setProperty("http.nonProxyHosts", "localhost|127.*|[::1]")
        try { ControlledHttpServer {
            TestHttpResponse(302, headers = mapOf("Location" to "https://www.xunlys.top/api/download/latest/0"))
        }.use { redirect ->
            val legacyUrl = InstrumentationRegistry.getArguments().getString("legacyHttpDownloadUrl").orEmpty().trim()
            val scenarios = buildList {
                if (legacyUrl.isNotEmpty()) add("legacy-http" to legacyUrl)
                add("http-to-https" to redirect.baseUrl + "/upgrade")
            }
            for ((name, url) in scenarios) {
                val service = AppUpdateService(context)
                val option = AppUpdateDownloadOption("公开兼容验证", url)
                val info = update("download-compat-$name", option)
                val record = JSONObject().put("scenario", name).put("requestedUrl", url).put("status", "STARTED")
                records.put(record)
                var id: Long? = null
                try {
                    id = service.enqueueDownload(info, option)
                    // Public delivery can be slow on a metered emulator. This is a test deadline,
                    // independent of the app's cancellable system download lifetime.
                    withTimeout(300_000) {
                        while (true) {
                            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                                if (cursor.moveToFirst()) {
                                    record.put("downloadManagerStatus", cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)))
                                        .put("downloadManagerReason", cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
                                        .put("downloadedBytes", cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)))
                                        .put("totalBytes", cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                                }
                            }
                            evidence.writeText(records.toString(2))
                            when (val status = service.downloadState(id)) {
                                UpdateDownloadState.Ready -> break
                                is UpdateDownloadState.Failed -> error(status.message)
                                UpdateDownloadState.Missing -> error("System download disappeared")
                                else -> delay(300)
                            }
                        }
                    }
                    val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates/AniMeow_${info.versionName}.apk")
                    record.put("fileBytes", file.length())
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    record.put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                    file.copyTo(File(root, "$name-downloaded.apk"), overwrite = true)
                    assertTrue(file.length() > 1_000_000L)
                    ZipFile(file).use { assertNotNull(it.getEntry("AndroidManifest.xml")) }
                    val pkg = requireNotNull(context.packageManager.getPackageArchiveInfo(file.absolutePath, 0))
                    assertEquals("com.animeow.app", pkg.packageName)
                    val resolved = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
                    }
                    if (name == "http-to-https") assertTrue(resolved.startsWith("https://"))
                    record.put("resolvedUrl", resolved).put("bytes", file.length())
                        .put("package", pkg.packageName).put("version", pkg.versionName).put("versionCode", pkg.longVersionCode).put("status", "SUCCESS")
                } catch (error: Throwable) {
                    record.put("status", "FAILED").put("error", error.javaClass.simpleName).put("detail", error.message)
                    failures += error
                } finally {
                    evidence.writeText(records.toString(2))
                    id?.let(service::discardTrackedDownload)
                }
            }
        } } finally {
            if (bypassLocalProxy) {
                if (originalNonProxyHosts == null) System.clearProperty("http.nonProxyHosts")
                else System.setProperty("http.nonProxyHosts", originalNonProxyHosts)
            }
        }
        evidence.writeText(records.toString(2))
        if (failures.isNotEmpty()) throw AssertionError("Public downloads: ${failures.size} failed; see system-download-results.json", failures.first())
    }

    private fun update(version: String, option: AppUpdateDownloadOption) =
        AppUpdateInfo(BuildConfig.VERSION_CODE + 1, version, "合成下载验证", false, listOf(option), 0)
}
