package com.animeow.app.data.cloud

import android.app.Application
import androidx.room.Room
import com.animeow.app.data.backup.NativeBackupService
import com.animeow.app.data.importer.LegacyBackupImporter
import com.animeow.app.data.local.AniMeowDatabase
import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class DeviceLoginRequestTest {
    @Test fun sendsOnlyThisDevicesRegistrationIdWithoutRequiringUsernameOrPassword() = checkRequest(401,
        """{"status":"error","message":"此设备尚未关联账号，请使用密码或 Bangumi 登录"}""", "此设备尚未关联账号")
    @Test fun oldServerHasAClearFallbackInsteadOfAnHtmlError() = checkRequest(404, "<html>not found</html>", "服务器尚未开通本机登录")

    private fun checkRequest(status: Int, response: String, expectedMessage: String) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        var requested: JSONObject? = null
        var path: String? = null
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    path = input.readLine().split(' ')[1]
                    var length = 0
                    while (true) {
                        val header = input.readLine()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length)
                    var offset = 0
                    while (offset < length) { val count = input.read(body, offset, length - offset); check(count > 0); offset += count }
                    requested = JSONObject(String(body))
                    val bytes = response.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status Fixture\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }
        try {
            val service = CloudAccountService(context, NativeBackupService(context, db), LegacyBackupImporter(context, db),
                apiBase = "http://127.0.0.1:${server.localPort}")
            try { service.loginWithDevice(); fail("The fixture response must reject login") }
            catch (error: IllegalStateException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(expectedMessage)) }
            responder.join(5_000)
            assertEquals("/api/auth/device-login", path)
            val body = requireNotNull(requested)
            assertEquals(setOf("device_registration_id"), body.keys().asSequence().toSet())
            assertEquals(DeviceRegistrationId.from(context), body.getString("device_registration_id"))
            assertTrue(body.getString("device_registration_id").matches(Regex("[a-f0-9]{64}")))
        } finally { server.close(); responder.join(5_000); db.close() }
    }
}
