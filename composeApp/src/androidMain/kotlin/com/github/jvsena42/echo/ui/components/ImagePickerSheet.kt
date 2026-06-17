package com.github.jvsena42.echo.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.github.jvsena42.echo.R
import com.github.jvsena42.echo.platform.MediaProcessor
import com.github.jvsena42.echo.presentation.media.ImageSheetViewModel
import com.github.jvsena42.echo.ui.theme.EchoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Reusable bottom sheet for choosing an image — web search (Unsplash) + a 3-column grid, plus a
 * "From gallery" button using the system photo picker (no storage permission). Backs both the
 * card-image sheet (cEXuT) and the cover sheet (OQ2QL).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Suppress("CyclomaticComplexMethod", "LongMethod") // Single sheet; grid/gallery/states read top-to-bottom.
@Composable
fun ImagePickerSheet(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    onSelected: (ImageSelection) -> Unit,
) {
    val colors = EchoTheme.colors
    val viewModel = koinInject<ImageSheetViewModel>()
    val mediaProcessor = koinInject<MediaProcessor>()
    DisposableEffect(viewModel) { onDispose { viewModel.onDispose() } }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUrl by remember { mutableStateOf<String?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val processed = withContext(Dispatchers.IO) {
                    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@withContext null
                    mediaProcessor.compressImage(raw)
                }
                if (processed != null) onSelected(ImageSelection.Gallery(processed.bytes, processed.mime))
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfacePrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { testTagsAsResourceId = true }
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.W800, color = colors.foregroundPrimary)
                Text(
                    text = stringResource(R.string.image_sheet_done),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selectedUrl != null) colors.accentPrimary else colors.foregroundMuted,
                    modifier = Modifier
                        .testTag("image_sheet_done")
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = selectedUrl != null) {
                            selectedUrl?.let { onSelected(ImageSelection.Web(it)) }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            subtitle?.let {
                Text(text = it, fontSize = 13.sp, color = colors.foregroundSecondary)
            }

            // Search bar
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("image_search_input"),
                placeholder = { Text(stringResource(R.string.image_sheet_search_placeholder), color = colors.foregroundMuted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = colors.foregroundMuted) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accentPrimary,
                    unfocusedBorderColor = colors.borderSubtle,
                    cursorColor = colors.accentPrimary,
                ),
            )

            // From gallery
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(12.dp))
                    .background(colors.surfaceCard)
                    .clickable {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    }
                    .testTag("image_pick_gallery")
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Image, null, tint = colors.foregroundSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.image_sheet_from_gallery),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W600,
                    color = colors.foregroundSecondary,
                )
            }

            // Web image grid
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) {
                when {
                    state.isLoading && state.photos.isEmpty() ->
                        CircularProgressIndicator(
                            color = colors.accentPrimary,
                            modifier = Modifier.align(Alignment.Center),
                        )

                    !state.isUnsplashConfigured ->
                        CenteredHint(stringResource(R.string.image_sheet_empty))

                    state.photos.isEmpty() ->
                        CenteredHint(stringResource(R.string.image_sheet_no_results))

                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("image_grid"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(state.photos, key = { _, p -> p.id }) { index, photo ->
                            val selected = selectedUrl == photo.fullUrl
                            AsyncImage(
                                model = photo.thumbUrl,
                                contentDescription = photo.authorName,
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        width = if (selected) 2.5.dp else 1.dp,
                                        color = if (selected) colors.accentPrimary else colors.borderSubtle,
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    .clickable { selectedUrl = photo.fullUrl }
                                    .testTag(if (index == 0) "image_grid_cell" else "image_grid_cell_$index"),
                            )
                        }
                    }
                }
            }

            state.error?.let { Text(it, fontSize = 12.sp, color = colors.danger) }
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    val colors = EchoTheme.colors
    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = colors.foregroundMuted)
    }
}
