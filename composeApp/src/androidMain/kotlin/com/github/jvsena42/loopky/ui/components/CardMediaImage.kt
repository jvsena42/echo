package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.domain.model.MediaRef
import org.koin.compose.koinInject

/**
 * Renders a card/cover [MediaRef.Image]. Remote (web) images load directly from their URL via
 * Coil; blob images are fetched from the homeserver through [MediaRepository] and rendered from
 * their decoded bytes (also via Coil, which decodes the ByteArray).
 */
@Composable
fun CardMediaImage(
    image: MediaRef.Image,
    deckId: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (image.isRemote) {
        AsyncImage(
            model = image.url,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }

    val mediaRepository = koinInject<MediaRepository>()
    val bytes by produceState<ByteArray?>(initialValue = null, image.sha256, deckId) {
        value = mediaRepository.get(deckId, image).getOrNull()
    }
    val data = bytes
    if (data == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
    } else {
        AsyncImage(
            model = data,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}
