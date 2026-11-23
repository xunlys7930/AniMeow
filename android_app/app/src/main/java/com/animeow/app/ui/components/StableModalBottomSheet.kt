@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.animeow.app.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

/**
 * A bottom sheet that never gets stranded on Material 3's partially-expanded anchor.
 *
 * Long sheets in AniMeow own their vertical scrolling, so the sheet itself must not
 * compete for the same gesture. Skipping the partial anchor keeps every internal
 * list reachable while [sheetGesturesEnabled] remains disabled by default.
 */
@Composable
fun StableModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetGesturesEnabled: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = sheetGesturesEnabled,
    ) {
        // Bounded modal lists should stop at their edges. Stretch overscroll and a draggable
        // parent otherwise produce competing vertical motion during repeated boundary flings.
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            content()
        }
    }
}
