package com.animeow.app.data.overlay

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.ResultReceiver
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import com.animeow.app.AniMeowApplication
import com.animeow.app.R
import com.animeow.app.MainActivity
import com.animeow.app.data.ProgressChange
import com.animeow.app.data.reminder.AnimeReminderNotifier
import com.animeow.app.data.preferences.AppearancePreferences
import com.animeow.app.ui.theme.OverlayStyle
import com.animeow.app.ui.theme.ThemeMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/** User-started, local-only controls. Never inspect the foreground app or restart automatically. */
class ProgressOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository get() = (application as AniMeowApplication).libraryRepository
    private val windows get() = getSystemService(WindowManager::class.java)
    private var panel: LinearLayout? = null
    private var observeJob: Job? = null
    private var visibilityJob: Job? = null
    private var change: ProgressChange? = null
    private var animeId = 0L
    private var busy = false
    private var expanded = false
    private var dockSide = 0
    private val appearancePreferences by lazy { AppearancePreferences(this) }
    private var overlayStyle = OverlayStyle.QUIET_PAW
    private var themeMode = ThemeMode.SYSTEM
    private var progressFraction: Float? = null
    private lateinit var ballArt: OverlayBallDrawable
    private lateinit var headerArt: ImageView
    private lateinit var collapse: Button
    private lateinit var close: ImageButton
    private lateinit var ball: OverlayDragHandle
    private lateinit var controls: LinearLayout
    private lateinit var title: OverlayDragHandle
    private lateinit var progress: TextView
    private lateinit var plus: Button
    private lateinit var undo: Button

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val id = intent.getLongExtra(EXTRA_ID, 0)
        if (id <= 0) { stopSelf(); return START_NOT_STICKY }
        if (!canPostNotifications(this)) {
            Toast.makeText(this, "请开启系统通知及悬浮记录通知渠道后重试", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        var startResult = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT, ResultReceiver::class.java)
        animeId = id
        change = null
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "悬浮记录进度", NotificationManager.IMPORTANCE_LOW),
            )
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
                .putExtra(AnimeReminderNotifier.EXTRA_ANIME_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle("悬浮记录已开启").setContentText("点此返回作品；可随时关闭")
                .setContentIntent(open).setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", stop).build()
            ServiceCompat.startForeground(this, 219, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            if (panel == null) createPanel()
            setExpanded(false)
            plus.isEnabled = false; undo.isEnabled = false
            title.text = "正在读取作品"; progress.text = ""
            observeJob?.cancel()
            observeJob = scope.launch {
                repository.observeAnime(id).catch { stopSelf() }.collectLatest { anime ->
                    if (anime == null || anime.deletedAt != null) {
                        Toast.makeText(this@ProgressOverlayService, "作品已移除，悬浮记录已关闭", Toast.LENGTH_LONG).show()
                        stopSelf(); return@collectLatest
                    }
                    title.text = anime.title
                    title.contentDescription = "${anime.title}，拖动可移动悬浮窗"
                    val unit = if (anime.subjectType == "book") "话" else "集"
                    progress.text = getString(R.string.overlay_progress, anime.watchedEpisodes, anime.totalEpisodes.takeIf { it > 0 }?.toString() ?: "?", unit)
                    ball.contentDescription = "${anime.title}，${progress.text}，展开悬浮记录"
                    progressFraction = if (anime.totalEpisodes > 0) anime.watchedEpisodes.toFloat() / anime.totalEpisodes else null
                    ballArt.progress = progressFraction
                    plus.text = getString(R.string.overlay_add_one, unit)
                    plus.isEnabled = !busy && (anime.totalEpisodes <= 0 || anime.watchedEpisodes < anime.totalEpisodes)
                    undo.isEnabled = !busy && change != null
                    startResult?.send(RESULT_STARTED, null)
                    startResult = null
                }
            }
            visibilityJob?.cancel()
            visibilityJob = scope.launch {
                while (isActive) {
                    if (!Settings.canDrawOverlays(this@ProgressOverlayService) || !canPostNotifications(this@ProgressOverlayService)) { stopSelf(); break }
                    panel?.visibility = if (getSystemService(KeyguardManager::class.java).isDeviceLocked) View.GONE else View.VISIBLE
                    panel?.let { view ->
                        val params = view.layoutParams as WindowManager.LayoutParams
                        val width = overlayWidth()
                        val half = if (expanded) 0 else width / 2
                        val right = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
                        val x = if (!expanded && dockSide != 0) { if (dockSide < 0) -half else right + half }
                            else params.x.coerceIn(-half, right + half)
                        val y = params.y.coerceIn(0, (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0))
                        if (params.width != width || params.x != x || params.y != y) {
                            params.width = width; params.x = x; params.y = y
                            runCatching { windows.updateViewLayout(view, params) }
                        }
                    }
                    delay(500)
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, "悬浮窗无法开启，请检查悬浮窗权限后重试", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createPanel() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (getSystemService(KeyguardManager::class.java).isDeviceLocked) View.GONE else View.VISIBLE
        }
        ball = OverlayDragHandle(this).apply {
            text = ""; gravity = Gravity.CENTER
            contentDescription = "展开悬浮记录"; setOnClickListener { setExpanded(true) }
        }
        root.addView(ball, LinearLayout.LayoutParams(dp(56), dp(56)))
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)) }
        title = OverlayDragHandle(this).apply {
            textSize = 14f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD); contentDescription = "拖动悬浮窗"
        }
        progress = TextView(this).apply { textSize = 12f; setPadding(0, 0, 0, dp(10)) }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; textSize = 13f; minWidth = 0; minimumWidth = 0; minimumHeight = dp(48); isAllCaps = false
            setPadding(dp(6), dp(4), dp(6), dp(4)); stateListAnimator = null
            setOnClickListener { action() }
        }
        plus = button("+1 集") { mutate { id ->
            val settings = appearancePreferences.snapshot()
            repository.incrementProgress(id, settings.autoCompleteStatus, settings.completionStatus)
        } }
        undo = button("撤销") {
            val previous = change
            mutate { _ -> previous?.let { repository.restoreProgress(it) }; null }
        }.apply { isEnabled = false }
        collapse = button("收起") { setExpanded(false) }.apply { contentDescription = "收起为悬浮球" }
        listOf(plus, undo, collapse).forEachIndexed { index, view ->
            view.tag = listOf("overlay.plus", "overlay.undo", "overlay.collapse")[index]
            row.addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (index == 0) 1.2f else 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        headerArt = ImageView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        header.addView(headerArt, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) })
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        close = ImageButton(this).apply {
            contentDescription = "关闭悬浮记录"; tag = "overlay.close"; setPadding(dp(14), dp(14), dp(14), dp(14))
            setImageResource(R.drawable.ami_line_close_a); setOnClickListener { stopSelf() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        controls.addView(header); controls.addView(progress); controls.addView(row)
        root.addView(controls)
        val params = WindowManager.LayoutParams(overlayWidth(),
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(120) }
        var initialX = 0; var initialY = 0; var touchX = 0f; var touchY = 0f
        var dragging = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        fun moveBy(dx: Int, dy: Int) {
            dockSide = 0
            params.x = (params.x + dx).coerceIn(0, (resources.displayMetrics.widthPixels - root.width).coerceAtLeast(0))
            params.y = (params.y + dy).coerceIn(0, (resources.displayMetrics.heightPixels - root.height).coerceAtLeast(0))
            runCatching { windows.updateViewLayout(root, params) }
        }
        listOf(R.string.overlay_move_left to (-24 to 0), R.string.overlay_move_right to (24 to 0),
            R.string.overlay_move_up to (0 to -24), R.string.overlay_move_down to (0 to 24)).forEach { (label, delta) ->
            listOf(title, ball).forEach { handle ->
                ViewCompat.addAccessibilityAction(handle, getString(label)) { _, _ -> moveBy(dp(delta.first), dp(delta.second)); true }
            }
        }
        ViewCompat.addAccessibilityAction(ball, "关闭悬浮记录") { _, _ -> stopSelf(); true }
        val dragListener = View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { initialX = params.x; initialY = params.y; touchX = event.rawX; touchY = event.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.hypot(event.rawX - touchX, event.rawY - touchY) > slop) dragging = true
                    if (dragging) {
                        dockSide = 0
                        ball.alpha = 1f
                        val half = if (expanded) 0 else params.width / 2
                        params.x = (initialX + event.rawX - touchX).toInt().coerceIn(-half, (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0) + half)
                        params.y = (initialY + event.rawY - touchY).toInt().coerceIn(0, (resources.displayMetrics.heightPixels - root.height).coerceAtLeast(0))
                        runCatching { windows.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragging) view.performClick() else if (!expanded) dockIfNearEdge(); dragging = false; true }
                MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                else -> false
            }
        }
        title.setOnTouchListener(dragListener)
        ball.setOnTouchListener(dragListener)
        windows.addView(root, params)
        panel = root
        applyAppearance()
        scope.launch {
            appearancePreferences.settings.map { it.overlayStyle to it.themeMode }.distinctUntilChanged().collect { (style, theme) ->
                overlayStyle = style; themeMode = theme
                applyAppearance()
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun applyAppearance() {
        val dark = themeMode == ThemeMode.DARK || (themeMode == ThemeMode.SYSTEM &&
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        val palette = overlayStyle.palette(dark)
        ballArt = OverlayBallDrawable(this, overlayStyle).apply { progress = progressFraction }
        ball.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(35, 255, 255, 255)), ballArt,
            overlaySurface(Color.WHITE, dp(24).toFloat()),
        )
        headerArt.setImageDrawable(OverlayBallDrawable(this, overlayStyle))
        title.setTextColor(palette.text); progress.setTextColor(palette.secondaryText)
        listOf(plus, undo, collapse).forEach { button ->
            val enabled = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(30, 128, 128, 128)),
                overlaySurface(if (button == plus) palette.primary else palette.button, dp(14).toFloat()),
                overlaySurface(Color.WHITE, dp(14).toFloat()),
            )
            button.backgroundTintList = null
            button.background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled), overlaySurface(palette.button, dp(14).toFloat()))
                addState(intArrayOf(), enabled)
            }
            button.setTextColor(palette.textColors(button == plus))
        }
        close.imageTintList = android.content.res.ColorStateList.valueOf(palette.text)
        close.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(palette.button),
            overlaySurface(Color.TRANSPARENT, dp(24).toFloat()), null,
        )
        panel?.background = if (expanded) overlaySurface(palette.surface, dp(24).toFloat(), palette.border) else null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (panel != null) applyAppearance()
    }

    private fun overlayWidth(): Int = minOf(
        ((if (expanded) 300 else 56) * resources.displayMetrics.density).toInt(),
        (resources.displayMetrics.widthPixels - 16).coerceAtLeast(1),
    )

    private fun setExpanded(value: Boolean) {
        expanded = value
        ball.alpha = if (!value && dockSide != 0) 0.6f else 1f
        ball.visibility = if (value) View.GONE else View.VISIBLE
        controls.visibility = if (value) View.VISIBLE else View.GONE
        panel?.let { root ->
            applyAppearance()
            val params = root.layoutParams as WindowManager.LayoutParams
            params.width = overlayWidth()
            params.x = params.x.coerceIn(0, (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
            if (!value && dockSide != 0) params.x = if (dockSide < 0) -params.width / 2 else resources.displayMetrics.widthPixels - params.width / 2
            runCatching { windows.updateViewLayout(root, params) }
            root.post {
                params.y = params.y.coerceIn(0, (resources.displayMetrics.heightPixels - root.height).coerceAtLeast(0))
                runCatching { windows.updateViewLayout(root, params) }
            }
        }
    }

    private fun dockIfNearEdge() {
        val root = panel ?: return
        val params = root.layoutParams as WindowManager.LayoutParams
        val threshold = (32 * resources.displayMetrics.density).toInt()
        val right = (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0)
        dockSide = when { params.x <= threshold -> -1; params.x >= right - threshold -> 1; else -> 0 }
        if (dockSide != 0) {
            ball.alpha = 0.6f
            params.x = if (dockSide < 0) -params.width / 2 else right + params.width / 2
            ball.contentDescription = "贴边悬浮记录，点击展开，拖动可移出边缘"
            runCatching { windows.updateViewLayout(root, params) }
        }
    }

    private fun mutate(action: suspend (Long) -> ProgressChange?) {
        if (busy) return
        busy = true; plus.isEnabled = false; undo.isEnabled = false
        val requestId = animeId
        scope.launch {
            try {
                val result = action(requestId)
                if (requestId == animeId) change = result
            }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { Toast.makeText(this@ProgressOverlayService, "记录未完成，请返回应用检查后重试", Toast.LENGTH_LONG).show() }
            finally {
                busy = false
                val anime = (application as AniMeowApplication).database.libraryDao().getAnime(animeId)
                plus.isEnabled = anime != null && anime.deletedAt == null && (anime.totalEpisodes <= 0 || anime.watchedEpisodes < anime.totalEpisodes)
                undo.isEnabled = change != null
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        panel?.let { runCatching { windows.removeView(it) } }; panel = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "overlay_anime_id"
        const val CHANNEL = "progress_overlay"
        const val RESULT_STARTED = 1
        private const val EXTRA_RESULT = "overlay_start_result"
        private const val ACTION_STOP = "com.animeow.app.overlay.STOP"
        fun intent(context: Context, id: Long, result: ResultReceiver? = null) =
            Intent(context, ProgressOverlayService::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_RESULT, result)

        fun canPostNotifications(context: Context): Boolean =
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private class OverlayDragHandle(context: Context) : TextView(context) {
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
