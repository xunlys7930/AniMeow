package com.animeow.app.ui.legacy

import androidx.compose.runtime.Composable
import com.animeow.app.ui.anime.AnimeEditorScreen
import com.animeow.app.ui.anime.AnimeEditorViewModel

/**
 * The native editor already mirrors the v1.3.9 card order and interaction model closely.
 * Keeping it behind this entry point lets the classic shell share the mature save,
 * duplicate detection, cover crop, remote matching and unsaved-draft protections.
 */
@Composable
fun LegacyAnimeEditorScreen(
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    viewModel: AnimeEditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    AnimeEditorScreen(
        onBack = onBack,
        onSaved = onSaved,
        viewModel = viewModel,
    )
}
