package com.github.jvsena42.loopky.ui.signup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.signup.SignupError
import com.github.jvsena42.loopky.ui.components.signupErrorMessage
import com.github.jvsena42.loopky.ui.components.signupErrorTitle
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * Shared chrome for the signup steps: back affordance, title, subtitle, and an error block.
 *
 * Exists so the five screens differ only in their content — the flow is a sequence of near
 * identical pages, and repeating the frame five times is how they drift apart.
 */
@Composable
fun SignupScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    error: SignupError? = null,
    content: @Composable () -> Unit,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("signup_back")) {
            Text(text = stringResource(R.string.deck_detail_back), color = colors.accentSecondary, fontSize = 14.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            color = colors.foregroundPrimary,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = subtitle,
            color = colors.foregroundSecondary,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(28.dp))

        content()

        if (error != null) {
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier.fillMaxWidth().testTag("signup_error"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = signupErrorTitle(error),
                    color = colors.danger,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = signupErrorMessage(error), color = colors.foregroundSecondary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
