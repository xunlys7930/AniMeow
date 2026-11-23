package com.animeow.app.ui.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.animeow.app.R
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.EpisodeDetailEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.DetailCardStyle
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.DetailModule
import com.animeow.app.ui.theme.LocalDetailLayout
import com.animeow.app.ui.theme.LocalDetailProgressMode

/** The real detail components at phone width, scaled to fit the settings preview. */
@Composable
internal fun DetailLayoutPreview(settings: AppearanceSettings) {
    val packageName = LocalContext.current.packageName
    val anime = remember(packageName) {
        AnimeEntity(id = -1, title = "星海旅人", originalTitle = "A Journey Beyond the Sky", status = "在看",
            coverUrl = "android.resource://$packageName/${R.drawable.preview_cover}", watchedEpisodes = 8, totalEpisodes = 12,
            airDate = "2026-07-10", watchStartDate = "2026-08-23", createdAt = "2026-08-20", rating = 85,
            mediaFormat = "TV", studio = "星海工作室", synopsis = "从雨后的车站出发，去寻找远方的光。将沿途遇见的风景、朋友与感动，慢慢写进自己的记录。")
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(360.dp).clip(MaterialTheme.shapes.large)
        .background(MaterialTheme.colorScheme.background)) {
        val density = LocalDensity.current
        val phoneWidth = LocalConfiguration.current.screenWidthDp.coerceAtLeast(360)
        val scale = (maxWidth.value / phoneWidth).coerceAtMost(1f)
        CompositionLocalProvider(
            LocalDensity provides Density(density.density * scale, density.fontScale),
            LocalDetailLayout provides settings.detailLayout,
            LocalDetailProgressMode provides settings.detailProgressMode,
            LocalDetailCardStyle provides settings.detailCardStyle,
        ) {
            DetailGlassScene(settings.detailCardStyle == DetailCardStyle.GLASS && settings.detailLayout != DetailLayout.MINIMAL,
                anime.coverUrl, Modifier.fillMaxSize()) {
                DetailModulesLayout(Modifier.fillMaxSize(), settings.detailLayout,
                    settings.detailModuleOrder.filterNot { it in settings.hiddenDetailModules || it == DetailModule.SERIES },
                    anime, listOf(TagEntity(id = -1, name = "冒险")), emptyList(), null, emptyList(),
                    settings.characterImageAlignment, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    episodeDetails = listOf(EpisodeDetailEntity(animeId = -1, episode = 1, title = "雨后的车站", durationMinutes = 24)),
                    onOpenEpisodes = {})
            }
        }
    }
}
