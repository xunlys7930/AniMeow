package com.animeow.app

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.animeow.app.ui.AniMeowApp
import com.animeow.app.ui.branding.BrandingSplash
import com.animeow.app.ui.legacy.AniMeowFrontendTheme
import com.animeow.app.ui.preferences.AppearanceViewModel
import com.animeow.app.ui.preferences.BrandingViewModel
import com.animeow.app.ui.theme.AniMeowTheme
import com.animeow.app.ui.theme.NavigationBarStyle
import com.animeow.app.ui.theme.FrontendMode
import com.animeow.app.data.reminder.AnimeReminderNotifier
import com.animeow.app.data.branding.LauncherAction
import com.animeow.app.data.remote.CommunityLaunchRequest
import com.animeow.app.data.remote.BangumiOAuthCallback
import com.animeow.app.data.remote.extractCharacterGroupShareCode
import com.animeow.app.data.remote.parseAniMeowDeepLink
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val appearanceViewModel by viewModels<AppearanceViewModel>()
    private val brandingViewModel by viewModels<BrandingViewModel>()
    private val promotionViewModel by viewModels<com.animeow.app.ui.promotion.PromotionViewModel>()
    private val animeDeepLink = MutableStateFlow<Long?>(null)
    private val communityLaunchRequest = MutableStateFlow<CommunityLaunchRequest?>(null)
    private val reviewLaunchRequest = MutableStateFlow(false)
    private val announcementLaunchRequest = MutableStateFlow(false)
    private val libraryLaunchRequest = MutableStateFlow(false)
    private val shortcutLaunchRequest = MutableStateFlow<LauncherAction?>(null)
    private val bangumiOAuthRequest = MutableStateFlow<BangumiOAuthCallback?>(null)
    private val handledClipboardRequests = linkedSetOf<String>()

    // 授权回跳的 code/state 是一次性凭证：旋转重建时 handleIntent 会重放同一 Intent，
    // 已消费的 key 不再触发第二次 exchange
    private val handledBangumiCallbacks = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        if (savedInstanceState == null) promotionViewModel.onAppLaunch(intent?.action == Intent.ACTION_MAIN || intent?.action == null)
        if (savedInstanceState != null) {
            shortcutLaunchRequest.value = LauncherAction.fromIntentAction(savedInstanceState.getString(PENDING_SHORTCUT))
            announcementLaunchRequest.value = savedInstanceState.getBoolean(PENDING_ANNOUNCEMENT)
        }
        enableEdgeToEdge()
        // A fresh instance created only to show one work: back should leave it, not show the library.
        val deepLinkAsRoot = savedInstanceState == null && intent?.animeDeepLinkId() != null
        val returnToHistory = intent?.getBooleanExtra(com.animeow.app.ui.playback.EXTRA_RETURN_TO_HISTORY, false) == true

        setContent {
            val appearance by appearanceViewModel.settings.collectAsStateWithLifecycle()
            val appearanceLoaded by appearanceViewModel.loaded.collectAsStateWithLifecycle()
            val brandingState by brandingViewModel.uiState.collectAsStateWithLifecycle()
            val splashPending by brandingViewModel.splashPending.collectAsStateWithLifecycle()
            val pendingAnimeId by animeDeepLink.collectAsStateWithLifecycle()
            val pendingCommunityRequest by communityLaunchRequest.collectAsStateWithLifecycle()
            val pendingReviewRequest by reviewLaunchRequest.collectAsStateWithLifecycle()
            val pendingAnnouncement by announcementLaunchRequest.collectAsStateWithLifecycle()
            val pendingLibraryRequest by libraryLaunchRequest.collectAsStateWithLifecycle()
            val pendingShortcut by shortcutLaunchRequest.collectAsStateWithLifecycle()
            val pendingBangumiOAuth by bangumiOAuthRequest.collectAsStateWithLifecycle()

            AniMeowTheme(settings = appearance) {
                AniMeowFrontendTheme(settings = appearance) {
                val darkSystemBarIcons = MaterialTheme.colorScheme.background.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = darkSystemBarIcons
                        isAppearanceLightNavigationBars = darkSystemBarIcons
                    }
                }
                val branding = brandingState.settings
                if (branding == null || !appearanceLoaded) {
                    Surface(modifier = Modifier.fillMaxSize()) {}
                } else {
                    val splashFile = brandingViewModel.splashFile(branding)
                    val showSplash = splashPending && branding.customSplashEnabled && splashFile != null
                    val splashFadeDuration = (320 * appearance.motionLevel.durationScale).toInt()

                    LaunchedEffect(splashPending, branding.customSplashEnabled, splashFile) {
                        if (splashPending && !showSplash) brandingViewModel.consumeSplash()
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        AniMeowApp(
                            settings = appearance,
                            onFrontendModeSelected = appearanceViewModel::selectFrontendMode,
                            onStyleSelected = appearanceViewModel::selectStyle,
                            onThemeModeSelected = appearanceViewModel::selectThemeMode,
                            onPredictiveBackEnabledChanged = appearanceViewModel::setPredictiveBackEnabled,
                            onExitBehaviorChanged = appearanceViewModel::setExitBehavior,
                            onCalendarLayoutPresetSelected = appearanceViewModel::setCalendarLayoutPreset,
                            onHomeLayoutSelected = appearanceViewModel::selectHomeLayout,
                            onDetailLayoutSelected = appearanceViewModel::selectDetailLayout,
                            onContentDensitySelected = appearanceViewModel::selectContentDensity,
                            onMotionLevelSelected = appearanceViewModel::selectMotionLevel,
                            onFontScaleChanged = appearanceViewModel::setFontScale,
                            onCornerScaleChanged = appearanceViewModel::setCornerScale,
                            onGridColumnsChanged = appearanceViewModel::setGridColumns,
                            onCoverAspectRatioSelected = appearanceViewModel::setCoverAspectRatio,
                            onCoverTitlePositionSelected = appearanceViewModel::setCoverTitlePosition,
                            onDetailCardStyleSelected = appearanceViewModel::setDetailCardStyle,
                            onShowTitleChanged = appearanceViewModel::setShowTitle,
                            onShowStatusChanged = appearanceViewModel::setShowStatus,
                            onShowRatingChanged = appearanceViewModel::setShowRating,
                            onShowProgressChanged = appearanceViewModel::setShowProgress,
                            onCardInfoOrderChanged = appearanceViewModel::setCardInfoOrder,
                            onRatingBadgeColorStyleSelected = appearanceViewModel::setRatingBadgeColorStyle,
                            onRatingBadgeCustomColorSelected = appearanceViewModel::setRatingBadgeCustomColor,
                            onShowDiscoveryChanged = appearanceViewModel::setShowDiscovery,
                            onShowCalendarChanged = appearanceViewModel::setShowCalendar,
                            onShowCommunityChanged = appearanceViewModel::setShowCommunity,
                            onShowStatisticsChanged = appearanceViewModel::setShowStatistics,
                            onShowVersionInProfileChanged = appearanceViewModel::setShowVersionInProfile,
                            onDetailModuleOrderChanged = appearanceViewModel::setDetailModuleOrder,
                            onHiddenDetailModulesChanged = appearanceViewModel::setHiddenDetailModules,
                            onCardSwipeActionsEnabledChanged = appearanceViewModel::setCardSwipeActionsEnabled,
                            onSwipeStartActionSelected = appearanceViewModel::setSwipeStartAction,
                            onSwipeEndActionSelected = appearanceViewModel::setSwipeEndAction,
                            onAutoCompleteStatusChanged = appearanceViewModel::setAutoCompleteStatus,
                            onCompletionStatusSelected = appearanceViewModel::setCompletionStatus,
                            onAccentColorSelected = appearanceViewModel::setAccentColor,
                            onNavigationOrderChanged = appearanceViewModel::setNavigationOrder,
                            onStartDestinationSelected = appearanceViewModel::setStartDestination,
                            onCharacterLayoutSelected = appearanceViewModel::setCharacterLayout,
                            onCharacterImageAlignmentSelected = appearanceViewModel::setCharacterImageAlignment,
                            onCharacterShowMetadataChanged = appearanceViewModel::setCharacterShowMetadata,
                            onCharacterShowRatingChanged = appearanceViewModel::setCharacterShowRating,
                            onNavigationBarStyleSelected = appearanceViewModel::setNavigationBarStyle,
                            pendingAnimeId = pendingAnimeId,
                            // A fresh activity created just to show one work (history/downloads list).
                            animeDeepLinkAsRoot = deepLinkAsRoot,
                            returnToPlaybackHistory = returnToHistory,
                            onAnimeDeepLinkConsumed = { animeDeepLink.value = null },
                            pendingCommunityRequest = pendingCommunityRequest.takeUnless { splashPending },
                            onCommunityLaunchRequestConsumed = ::consumeCommunityLaunchRequest,
                            pendingReviewRequest = pendingReviewRequest && !splashPending,
                            onReviewRequestConsumed = { reviewLaunchRequest.value = false },
                            pendingAnnouncementRequest = pendingAnnouncement && !splashPending,
                            onAnnouncementRequestConsumed = { announcementLaunchRequest.value = false },
                            pendingLibraryRequest = pendingLibraryRequest && !splashPending,
                            onLibraryRequestConsumed = { libraryLaunchRequest.value = false },
                            pendingShortcut = pendingShortcut.takeUnless { splashPending },
                            onShortcutConsumed = { shortcutLaunchRequest.value = null },
                            pendingBangumiOAuth = pendingBangumiOAuth.takeUnless { splashPending },
                            onBangumiOAuthConsumed = ::consumeBangumiOAuthCallback,
                            startupReady = !splashPending,
                            onExitRequested = ::finish,
                        )
                        AnimatedVisibility(
                            visible = showSplash,
                            enter = fadeIn(tween(splashFadeDuration)),
                            exit = fadeOut(tween(splashFadeDuration)),
                        ) {
                            BrandingSplash(
                                settings = branding,
                                imageFile = checkNotNull(splashFile),
                                motionLevel = appearance.motionLevel,
                                onFinished = brandingViewModel::consumeSplash,
                            )
                        }
                    }
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        promotionViewModel.onAppLaunch(intent.action == Intent.ACTION_MAIN)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PENDING_SHORTCUT, shortcutLaunchRequest.value?.intentAction)
        outState.putBoolean(PENDING_ANNOUNCEMENT, announcementLaunchRequest.value)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (appearanceViewModel.settings.value.clipboardShareDetection) {
            detectClipboardShareCode()
        }
    }

    private fun handleIntent(intent: Intent?) {
        announcementLaunchRequest.value = intent?.action == com.animeow.app.data.announcements.AnnouncementNotifications.ACTION_OPEN
        shortcutLaunchRequest.value = LauncherAction.fromIntentAction(intent?.action)
        libraryLaunchRequest.value = intent?.action == com.animeow.app.data.widget.TrackerWidget.ACTION_OPEN_LIBRARY
        reviewLaunchRequest.value = intent?.action == com.animeow.app.data.community.CommunityReviewNotifications.ACTION_OPEN_REVIEWS
        val deepLink = parseAniMeowDeepLink(intent?.dataString)
        animeDeepLink.value = intent.animeDeepLinkId() ?: deepLink?.animeId
        communityLaunchRequest.value = deepLink?.community ?: intent.sharedCommunityRequest()
        // 只在真的带回跳载荷时覆盖：无关 Intent 不清除尚未消费的授权回跳
        deepLink?.bangumiOAuth?.let { callback ->
            if (callback.key !in handledBangumiCallbacks) bangumiOAuthRequest.value = callback
        }
    }

    private fun consumeBangumiOAuthCallback() {
        bangumiOAuthRequest.value?.key?.let { key ->
            handledBangumiCallbacks += key
            while (handledBangumiCallbacks.size > MAX_HANDLED_CLIPBOARD_REQUESTS) {
                handledBangumiCallbacks.remove(handledBangumiCallbacks.first())
            }
        }
        bangumiOAuthRequest.value = null
    }

    private fun detectClipboardShareCode() {
        if (communityLaunchRequest.value != null || animeDeepLink.value != null || shortcutLaunchRequest.value != null) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return
        if (clip.itemCount <= 0) return
        val item = clip.getItemAt(0)
        val raw = item.text?.toString() ?: item.uri?.toString() ?: return
        val code = extractCharacterGroupShareCode(raw) ?: return
        val request = CommunityLaunchRequest(shareCode = code)
        if (request.key !in handledClipboardRequests) communityLaunchRequest.value = request
    }

    private fun consumeCommunityLaunchRequest() {
        communityLaunchRequest.value?.key?.let { key ->
            handledClipboardRequests += key
            while (handledClipboardRequests.size > MAX_HANDLED_CLIPBOARD_REQUESTS) {
                handledClipboardRequests.remove(handledClipboardRequests.first())
            }
        }
        communityLaunchRequest.value = null
    }

    private companion object {
        const val MAX_HANDLED_CLIPBOARD_REQUESTS = 16
        const val PENDING_SHORTCUT = "pending_launcher_shortcut"
        const val PENDING_ANNOUNCEMENT = "pending_announcement"
    }
}

private fun Intent?.animeDeepLinkId(): Long? = this
    ?.takeIf { it.action == AnimeReminderNotifier.ACTION_OPEN_ANIME || it.hasExtra(AnimeReminderNotifier.EXTRA_ANIME_ID) }
    ?.getLongExtra(AnimeReminderNotifier.EXTRA_ANIME_ID, -1L)
    ?.takeIf { it > 0 }

private fun Intent?.sharedCommunityRequest(): CommunityLaunchRequest? {
    if (this?.action != Intent.ACTION_SEND || type?.startsWith("text/") != true) return null
    val raw = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        ?: return null
    parseAniMeowDeepLink(raw)?.community?.let { return it }
    return extractCharacterGroupShareCode(raw)?.let { CommunityLaunchRequest(shareCode = it) }
}
