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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.animeow.app.data.remote.CommunityLaunchRequest
import com.animeow.app.data.remote.extractCharacterGroupShareCode
import com.animeow.app.data.remote.parseAniMeowDeepLink
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val appearanceViewModel by viewModels<AppearanceViewModel>()
    private val brandingViewModel by viewModels<BrandingViewModel>()
    private val animeDeepLink = MutableStateFlow<Long?>(null)
    private val communityLaunchRequest = MutableStateFlow<CommunityLaunchRequest?>(null)
    private val handledClipboardRequests = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()

        setContent {
            val appearance by appearanceViewModel.settings.collectAsStateWithLifecycle()
            val brandingState by brandingViewModel.uiState.collectAsStateWithLifecycle()
            val splashPending by brandingViewModel.splashPending.collectAsStateWithLifecycle()
            val pendingAnimeId by animeDeepLink.collectAsStateWithLifecycle()
            val pendingCommunityRequest by communityLaunchRequest.collectAsStateWithLifecycle()

            AniMeowTheme(settings = appearance) {
                AniMeowFrontendTheme(settings = appearance) {
                val branding = brandingState.settings
                if (branding == null) {
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
                            onAnimeDeepLinkConsumed = { animeDeepLink.value = null },
                            pendingCommunityRequest = pendingCommunityRequest.takeUnless { splashPending },
                            onCommunityLaunchRequestConsumed = ::consumeCommunityLaunchRequest,
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
    }

    override fun onResume() {
        super.onResume()
        if (appearanceViewModel.settings.value.clipboardShareDetection) {
            detectClipboardShareCode()
        }
    }

    private fun handleIntent(intent: Intent?) {
        val deepLink = parseAniMeowDeepLink(intent?.dataString)
        animeDeepLink.value = intent.animeDeepLinkId() ?: deepLink?.animeId
        communityLaunchRequest.value = deepLink?.community ?: intent.sharedCommunityRequest()
    }

    private fun detectClipboardShareCode() {
        if (communityLaunchRequest.value != null || animeDeepLink.value != null) return
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
