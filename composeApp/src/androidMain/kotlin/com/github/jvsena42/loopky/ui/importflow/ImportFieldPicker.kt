package com.github.jvsena42.loopky.ui.importflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.data.anki.ApkgFieldMapping
import com.github.jvsena42.loopky.presentation.importflow.ApkgFields
import com.github.jvsena42.loopky.presentation.importflow.SampleCard
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/** Which two Anki fields became the card, and an invitation to pick differently. */
@Composable
internal fun FieldsChip(fields: ApkgFields, onClick: () -> Unit) {
    val colors = LoopkyTheme.colors
    Text(
        text = if (fields.canChoose) {
            stringResource(R.string.bulk_fields_changeable, fields.frontName, fields.backName)
        } else {
            stringResource(R.string.bulk_fields, fields.frontName, fields.backName)
        },
        fontSize = 13.sp,
        fontWeight = FontWeight.W600,
        color = if (fields.canChoose) colors.accentPrimary else colors.foregroundSecondary,
        modifier = Modifier
            .testTag("bulk_fields_chip")
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = fields.canChoose, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Pick which two Anki fields become the card.
 *
 * Front first, then back, because that is the order the answer matters in and it keeps the sheet to
 * one list rather than two side by side. Each row shows the field's name and what the deck actually
 * has in it — a name like `Ranking` or `Picture` is only half the story, and the sample is what
 * makes an id column obvious at a glance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FieldMappingSheet(
    fields: ApkgFields,
    sample: SampleCard?,
    onPick: (ApkgFieldMapping) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    var front by remember(fields) { mutableStateOf<Int?>(null) }
    val choosingFront = front == null

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surfacePrimary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .semantics { testTagsAsResourceId = true },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    if (choosingFront) R.string.bulk_fields_sheet_front else R.string.bulk_fields_sheet_back,
                ),
                color = colors.foregroundPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            fields.names.forEachIndexed { ord, name ->
                // The front cannot also be the back; hiding it beats offering a choice that makes
                // a card with the same text twice.
                if (!choosingFront && ord == front) return@forEachIndexed
                val selected = if (choosingFront) {
                    ord == fields.mapping.frontOrd
                } else {
                    ord == fields.mapping.backOrd
                }
                FieldOption(
                    name = name.ifBlank { stringResource(R.string.bulk_fields_unnamed, ord + 1) },
                    selected = selected,
                    onClick = {
                        val chosen = front
                        if (chosen == null) front = ord else onPick(ApkgFieldMapping(chosen, ord))
                    },
                )
            }
            sample?.let {
                Text(
                    text = stringResource(R.string.bulk_fields_sheet_hint),
                    color = colors.foregroundSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun FieldOption(name: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LoopkyTheme.colors
    Text(
        text = name,
        color = if (selected) colors.accentPrimary else colors.foregroundPrimary,
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("bulk_field_option")
            .padding(vertical = 12.dp),
    )
}
