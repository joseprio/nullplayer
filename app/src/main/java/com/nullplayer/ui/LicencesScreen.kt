package com.nullplayer.ui

import androidx.annotation.RawRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplayer.R

/** One piece of someone else's work that ships inside the app, and whose it is. */
private data class Component(val name: String, val holder: String)

/** A licence, the full text it is reproduced from, and everything shipped under it. */
private class Licence(val name: String, @RawRes val text: Int, val components: List<Component>)

/**
 * Everything in the release build that is not ours. Taken from the release runtime classpath --
 * `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` -- so a dependency added
 * there belongs here too. THIRD_PARTY_LICENSES.md says the same for the repository.
 */
private val LICENCES = listOf(
    Licence(
        name = "SIL Open Font License 1.1",
        text = R.raw.licence_ofl,
        components = listOf(
            Component("Mona Sans Mono", "Copyright 2022 The Mona Sans Project Authors"),
            // A Modified Version, so under its own name: the licence reserves "Mona".
            Component(
                "Null Sans, modified from Mona Sans",
                "Copyright 2022 The Mona Sans Project Authors",
            ),
        ),
    ),
    Licence(
        name = "BSD 3-Clause License",
        text = R.raw.licence_bsd_nanohttpd,
        components = listOf(
            Component("NanoHTTPD", "Paul S. Hawke, Jarno Elonen and Konstantinos Togias"),
        ),
    ),
    Licence(
        name = "Apache License 2.0",
        text = R.raw.licence_apache,
        components = listOf(
            Component("AndroidX, Jetpack Compose and Media3", "The Android Open Source Project"),
            Component("Kotlin, kotlinx and Compose Multiplatform", "JetBrains s.r.o."),
            Component("Guava", "The Guava Authors"),
            Component("Okio", "Square, Inc."),
            Component("Haze", "Chris Banes"),
            Component("Poko", "Drew Hamilton"),
        ),
    ),
)

/**
 * The licences of everything the app is built from, reached from the foot of Settings. Each
 * licence is one panel: what is shipped under it and whose it is, with the full text folded away
 * beneath, since nobody reads two hundred lines of Apache to find out who wrote Guava.
 */
@Composable
fun LicencesScreen(
    onClose: () -> Unit,
    miniPlayer: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassScaffold(
        topBar = { glass -> ScreenHeader(title = "licences", onBack = onClose, modifier = glass) },
        bottomBar = miniPlayer,
        modifier = modifier,
    ) { top, inset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = 20.dp + inset),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    "nullplayer is built on the work of others, used under these licences.",
                    color = MUTED,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(LICENCES, key = { it.name }) { LicencePanel(it) }
        }
    }
}

@Composable
private fun LicencePanel(licence: Licence) {
    var open by rememberSaveable(licence.name) { mutableStateOf(false) }
    Panel {
        Text(licence.name, color = TEXT, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        licence.components.forEach { component ->
            Text(component.name, color = TEXT, fontSize = 13.sp)
            Text(component.holder, color = MUTED, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (open) "Hide the licence" else "Read the licence",
                color = ACCENT,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Glyph(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                ACCENT,
                contentDescription = null,
                size = 20.dp,
            )
        }
        if (open) {
            val context = LocalContext.current
            val text = remember(licence.text) {
                context.resources.openRawResource(licence.text).bufferedReader().use { it.readText() }
            }
            Text(text, color = MUTED, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = MonaSansMono)
        }
    }
}
