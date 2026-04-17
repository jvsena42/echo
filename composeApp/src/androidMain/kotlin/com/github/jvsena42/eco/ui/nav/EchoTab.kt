package com.github.jvsena42.eco.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.graphics.vector.ImageVector

enum class EchoTab(val label: String, val icon: ImageVector) {
    STUDY("STUDY", Icons.Rounded.LocalFireDepartment),
    DECKS("DECKS", Icons.Rounded.Layers),
    DISCOVER("DISCOVER", Icons.Rounded.Explore),
    PROFILE("PROFILE", Icons.Rounded.Person),
}
