package com.github.jvsena42.loopky.ui.backup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.backup.BackupEffect
import com.github.jvsena42.loopky.presentation.backup.BackupQuizUiState
import com.github.jvsena42.loopky.presentation.backup.BackupQuizViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.signup.SignupScaffold
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.NoAutofill
import com.github.jvsena42.loopky.ui.util.SecureScreen
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

/**
 * The confirm quiz. Passing it is what records the phrase as backed up — seeing the words is not.
 */
@Composable
fun BackupQuizRoute(
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupQuizViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnDone by rememberUpdatedState(onDone)

    SecureScreen()
    NoAutofill()

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                BackupEffect.Done -> currentOnDone()
                else -> Unit
            }
        }
    }

    BackupQuizScreen(
        state = state,
        onAnswer = viewModel::onAnswer,
        onSubmit = viewModel::onSubmit,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun BackupQuizScreen(
    state: BackupQuizUiState,
    onAnswer: (Int, String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    SignupScaffold(
        title = stringResource(R.string.backup_quiz_title),
        subtitle = stringResource(R.string.backup_quiz_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
        state.positions.forEachIndexed { questionIndex, position ->
            Text(
                text = stringResource(R.string.backup_quiz_position, position),
                color = colors.foregroundMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.options.getOrNull(questionIndex).orEmpty().forEach { option ->
                    OptionChip(
                        label = option,
                        selected = state.answers[questionIndex] == option,
                        onClick = { onAnswer(questionIndex, option) },
                        modifier = Modifier.testTag("backup_quiz_${questionIndex}_$option"),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        if (state.wrong) {
            // Clears the answers rather than locking: this checks that the words were written
            // down, and someone holding their own paper copy should be able to try again.
            Text(
                text = stringResource(R.string.backup_quiz_wrong),
                modifier = Modifier.testTag("backup_quiz_wrong"),
                color = colors.danger,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
        }

        LoopkyPrimaryButton(
            label = stringResource(R.string.backup_quiz_submit),
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier.testTag("backup_quiz_submit"),
        )
    }
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accentPrimary.copy(alpha = SELECTED_ALPHA) else colors.surfaceSecondary)
            .border(
                BorderStroke(1.dp, if (selected) colors.accentPrimary else colors.borderSubtle),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = colors.foregroundPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private const val SELECTED_ALPHA = 0.15f

@Preview
@Composable
private fun BackupQuizPreview() {
    LoopkyTheme {
        BackupQuizScreen(
            state = BackupQuizUiState(
                isLoading = false,
                positions = listOf(3, 6, 9),
                options = List(3) { listOf("abandon", "ability", "about", "above") },
            ),
            onAnswer = { _, _ -> }, onSubmit = {}, onBack = {},
        )
    }
}
