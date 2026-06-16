package com.github.jvsena42.echo.ui.importflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.echo.R
import com.github.jvsena42.echo.data.repository.ImportRepository
import com.github.jvsena42.echo.domain.model.frontBackOf
import com.github.jvsena42.echo.ui.theme.EchoTheme
import org.koin.compose.koinInject

@Composable
fun TriageEditCardRoute(
    rowIndex: Int,
    onBack: () -> Unit = {},
) {
    val importRepository = koinInject<ImportRepository>()
    val currentBack by rememberUpdatedState(onBack)

    val initial = remember(rowIndex) {
        val draft = importRepository.currentDraft()
        val row = draft?.rows?.firstOrNull { it.index == rowIndex }
        if (draft != null && row != null) draft.frontBackOf(row) else "" to ""
    }
    var front by remember(rowIndex) { mutableStateOf(initial.first) }
    var back by remember(rowIndex) { mutableStateOf(initial.second) }

    TriageEditCardScreen(
        front = front,
        back = back,
        onFrontChange = { front = it },
        onBackChange = { back = it },
        onCancel = currentBack,
        onSave = {
            importRepository.updateRow(rowIndex, front, back)
            currentBack()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriageEditCardScreen(
    front: String,
    back: String,
    onFrontChange: (String) -> Unit,
    onBackChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = EchoTheme.colors

    Scaffold(
        containerColor = colors.surfacePrimary,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.edit_card_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.edit_card_cancel),
                            tint = colors.foregroundPrimary,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = onSave,
                        modifier = Modifier.testTag("triage_edit_save"),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                    ) {
                        Text(text = stringResource(R.string.edit_card_save), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surfacePrimary,
                    titleContentColor = colors.foregroundPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            FieldSection(
                label = stringResource(R.string.triage_front_label),
                value = front,
                onValueChange = onFrontChange,
                placeholder = stringResource(R.string.edit_card_front_placeholder),
                textStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                tag = "triage_edit_front",
            )
            FieldSection(
                label = stringResource(R.string.triage_back_label),
                value = back,
                onValueChange = onBackChange,
                placeholder = stringResource(R.string.edit_card_back_placeholder),
                textStyle = TextStyle(fontSize = 16.sp),
                tag = "triage_edit_back",
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldSection(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    tag: String,
) {
    val colors = EchoTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = colors.foregroundMuted,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag),
            textStyle = textStyle.copy(color = colors.foregroundPrimary),
            placeholder = { Text(text = placeholder, style = textStyle, color = colors.foregroundMuted) },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accentPrimary,
                unfocusedBorderColor = colors.borderSubtle,
                cursorColor = colors.accentPrimary,
            ),
        )
    }
}
