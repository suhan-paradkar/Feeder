package com.nononsenseapps.feeder.ui.compose.utils

import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import com.nononsenseapps.feeder.ui.compose.theme.LocalTypographySettings

@Composable
fun ProvideScaledText(
    style: TextStyle,
    content: @Composable () -> Unit,
) {
    val typographySettings = LocalTypographySettings.current

    ProvideTextStyle(
        style.merge(
            TextStyle(
                fontSize = style.fontSize * typographySettings.fontScale,
            ),
        ),
    ) {
        content()
    }
}
