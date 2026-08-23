package com.github.jvsena42.loopky.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.TypedAnswerOutcome
import com.github.jvsena42.loopky.presentation.study.TypePhase
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The input the answer is written into, in the slot the SRS buttons take once it is revealed.
 *
 * [cardKey] re-focuses the field per card, so a session of typing is not one tap of setup per
 * card. [languageTag] is the *back's* language when the deck happens to have declared one — the
 * keyboard then comes up with that layout, and its accented keys within reach. Purely additive:
 * typing works with no pair declared at all, which is the whole reason the mode is not gated on
 * one (`Deck.speechReady`).
 */
@Composable
internal fun TypeAnswerRow(
    value: String,
    languageTag: String?,
    cardKey: Int,
    onValueChange: (String) -> Unit,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(cardKey) { focusRequester.requestFocus() }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = {
                Text(stringResource(R.string.study_type_placeholder), fontSize = 15.sp)
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                hintLocales = languageTag?.let { LocaleList(it) },
            ),
            keyboardActions = KeyboardActions(onDone = { onCheck() }),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accentPrimary,
                unfocusedBorderColor = colors.borderSubtle,
                focusedContainerColor = colors.surfaceCard,
                unfocusedContainerColor = colors.surfaceCard,
                focusedTextColor = colors.foregroundPrimary,
                unfocusedTextColor = colors.foregroundPrimary,
                cursorColor = colors.accentPrimary,
            ),
            modifier = Modifier
                .testTag("study_type_input")
                .weight(1f)
                .focusRequester(focusRequester),
        )
        Button(
            onClick = onCheck,
            enabled = value.isNotBlank(),
            modifier = Modifier
                .testTag("study_type_check")
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accentPrimary,
                contentColor = colors.foregroundOnAccent,
            ),
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.study_type_check),
                fontSize = 15.sp,
                fontWeight = FontWeight.W700,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * The way out of a card you cannot answer.
 *
 * Always on offer while answering, with no confirm step — a mode that can trap a session is worse
 * than no mode. It reveals the answer and says nothing about how the card should be graded.
 */
@Composable
internal fun GiveUpButton(onGiveUp: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onGiveUp, modifier = modifier.testTag("study_give_up")) {
        Text(
            text = stringResource(R.string.study_type_give_up),
            fontSize = 13.sp,
            fontWeight = FontWeight.W600,
            color = LoopkyTheme.colors.foregroundMuted,
        )
    }
}

/**
 * What the check made of the answer, between the card and the grade buttons.
 *
 * Reporting, not scoring — nothing here pre-selects or tints an SRS button, and giving up shows
 * no line at all, because the answer is the only thing that button promised.
 */
@Composable
internal fun TypeResultLine(phase: TypePhase.Checked, modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    val (message, tint) = when (phase.outcome) {
        TypedAnswerOutcome.Correct ->
            stringResource(R.string.study_type_correct) to colors.srsGood
        TypedAnswerOutcome.NearMiss ->
            stringResource(R.string.study_type_near_miss) to colors.srsHard
        TypedAnswerOutcome.Wrong ->
            stringResource(R.string.study_type_wrong) to colors.srsAgain
    }
    Column(
        modifier = modifier.fillMaxWidth().testTag("study_type_result"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = message, fontSize = 14.sp, fontWeight = FontWeight.W700, color = tint)
        // Shown for a near miss too: "almost" is only useful next to what you actually wrote.
        if (phase.outcome != TypedAnswerOutcome.Correct) {
            Text(
                text = stringResource(R.string.study_type_you_typed, phase.typed),
                fontSize = 12.sp,
                color = colors.foregroundMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
