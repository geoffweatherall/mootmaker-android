package com.mootmaker.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

/** The image loader avatars use. The app provides one that shares its HTTP client; without one, Coil's default. */
val LocalAvatarLoader = staticCompositionLocalOf<ImageLoader?> { null }

/**
 * A person's avatar: their photo once it has loaded, their initials until then and whenever they
 * have none or it fails to load, so a missing image never leaves a hole. The photo is immutable at
 * its URL (the API hashes the image into the path), so Coil's cache never goes stale.
 */
@Composable
fun Avatar(name: String, avatarUrl: String?, size: Dp = 28.dp, textStyle: TextStyle = MaterialTheme.typography.labelSmall) {
    var loaded by remember(avatarUrl) { mutableStateOf(false) }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .semantics { contentDescription = if (loaded) "Photo of $name" else "Initials of $name" },
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            // The initials belong to the fixed-size circle, so they don't grow with the font size and
            // spill out of it; the name itself is read out from the content description.
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
                Text(initials(name), style = textStyle, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                imageLoader = LocalAvatarLoader.current ?: SingletonImageLoader.get(LocalContext.current),
                contentScale = ContentScale.Crop,
                onState = { loaded = it is AsyncImagePainter.State.Success },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

fun initials(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
