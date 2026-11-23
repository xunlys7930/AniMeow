package com.animeow.app.ui.legacy

import androidx.compose.foundation.layout.aspectRatio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.formatAnimeRating
import com.animeow.app.data.local.subjectTypeDisplayName
import com.animeow.app.ui.anime.AnimeDetailScreen
import com.animeow.app.ui.anime.AnimeDetailViewModel
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.DetailCardStyle
import com.animeow.app.ui.theme.DetailModule
import com.animeow.app.ui.theme.FrontendMode

/** Keep the Flutter-era poster while sharing all detail actions and persistence. */
@Composable
fun LegacyAnimeDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onEditEpisodes: (Long) -> Unit,
    settings: AppearanceSettings,
    onDetailModuleOrderChanged: (List<DetailModule>) -> Unit,
    onHiddenDetailModulesChanged: (Set<DetailModule>) -> Unit,
    onDetailCardStyleSelected: (DetailCardStyle) -> Unit,
    onManageCharacters: () -> Unit,
    onAnimeOpen: (Long) -> Unit,
    onSeriesOpen: (Long) -> Unit,
    onTagOpen: (Long) -> Unit,
    onCharacterOpen: (Long) -> Unit,
    onOpenLegadoSync: (() -> Unit)? = null,
    viewModel: AnimeDetailViewModel = viewModel(),
) {
    CompositionLocalProvider(LocalFrontendMode provides FrontendMode.LEGACY) {
        AnimeDetailScreen(
            onBack = onBack,
            onEdit = onEdit,
            onEditEpisodes = onEditEpisodes,
            settings = settings,
            onDetailModuleOrderChanged = onDetailModuleOrderChanged,
            onHiddenDetailModulesChanged = onHiddenDetailModulesChanged,
            onDetailCardStyleSelected = onDetailCardStyleSelected,
            onManageCharacters = onManageCharacters,
            onAnimeOpen = onAnimeOpen,
            onSeriesOpen = onSeriesOpen,
            onTagOpen = onTagOpen,
            onCharacterOpen = onCharacterOpen,
            onOpenLegadoSync = onOpenLegadoSync,
            viewModel = viewModel,
        )
    }
}

@Composable
internal fun LegacyAnimeHeader(anime: AnimeEntity, onCoverClick: () -> Unit) {
    val rating = formatAnimeRating(anime.rating).ifBlank { anime.ratingGrade.orEmpty() }
    Box(
        modifier = Modifier.fillMaxWidth().then(if (anime.coverOrientation in listOf("portrait", "landscape")) Modifier.aspectRatio(com.animeow.app.data.local.coverAspectRatio(anime.coverOrientation, 2f / 3f)) else Modifier.height(400.dp))
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(role = Role.Button, onClickLabel = "查看封面大图", onClick = onCoverClick),
    ) {
        AsyncImage(
            model = anime.coverUrl,
            contentDescription = anime.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.1f), Color.Transparent, Color.Black.copy(alpha = 0.94f)),
                ),
            ),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                subjectTypeDisplayName(anime.subjectType),
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Text(
                anime.title,
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            anime.originalTitle?.takeIf { it.isNotBlank() && it != anime.title }?.let {
                Text(it, color = Color.White.copy(alpha = 0.84f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    anime.status,
                    modifier = Modifier.background(
                        if (anime.status == "看完") LegacyGreen else LegacyBlue,
                        CircleShape,
                    ).padding(horizontal = 12.dp, vertical = 5.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                if (rating.isNotBlank()) {
                    Text("★ $rating", color = Color(0xFFFFD54F), style = MaterialTheme.typography.titleLarge)
                }
            }
            anime.airDate?.takeIf(String::isNotBlank)?.let {
                Text(it, color = Color.White.copy(alpha = 0.84f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
