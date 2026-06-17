package com.github.jvsena42.echo.ui.importflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.jvsena42.echo.R
import com.github.jvsena42.echo.data.repository.ImportRepository
import com.github.jvsena42.echo.domain.model.DraftCardImage
import com.github.jvsena42.echo.domain.model.frontBackOf
import com.github.jvsena42.echo.ui.components.ImagePickerSheet
import com.github.jvsena42.echo.ui.components.ImageSelection
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
    var frontImage by remember(rowIndex) { mutableStateOf(importRepository.rowImage(rowIndex, isFront = true)) }
    var backImage by remember(rowIndex) { mutableStateOf(importRepository.rowImage(rowIndex, isFront = false)) }

    TriageEditCardScreen(
        front = front,
        back = back,
        frontImage = frontImage,
        backImage = backImage,
        onFrontChange = { front = it },
        onBackChange = { back = it },
        onFrontImageSelected = { img ->
            frontImage = img
            importRepository.setRowImage(rowIndex, isFront = true, image = img)
        },
        onBackImageSelected = { img ->
            backImage = img
            importRepository.setRowImage(rowIndex, isFront = false, image = img)
        },
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
    frontImage: DraftCardImage?,
    backImage: DraftCardImage?,
    onFrontChange: (String) -> Unit,
    onBackChange: (String) -> Unit,
    onFrontImageSelected: (DraftCardImage?) -> Unit,
    onBackImageSelected: (DraftCardImage?) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = EchoTheme.colors
    // Which side's image picker sheet is open (true = front, false = back, null = none).
    var pickerSide by remember { mutableStateOf<Boolean?>(null) }

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
                image = frontImage,
                onPickImage = { pickerSide = true },
                onRemoveImage = { onFrontImageSelected(null) },
            )
            FieldSection(
                label = stringResource(R.string.triage_back_label),
                value = back,
                onValueChange = onBackChange,
                placeholder = stringResource(R.string.edit_card_back_placeholder),
                textStyle = TextStyle(fontSize = 16.sp),
                tag = "triage_edit_back",
                image = backImage,
                onPickImage = { pickerSide = false },
                onRemoveImage = { onBackImageSelected(null) },
            )
        }
    }

    pickerSide?.let { isFront ->
        ImagePickerSheet(
            title = stringResource(if (isFront) R.string.image_sheet_front_title else R.string.image_sheet_back_title),
            subtitle = null,
            onDismiss = { pickerSide = null },
            onSelected = { selection ->
                val img = when (selection) {
                    is ImageSelection.Web -> DraftCardImage(url = selection.url)
                    is ImageSelection.Gallery -> DraftCardImage(bytes = selection.bytes, mime = selection.mime)
                }
                if (isFront) onFrontImageSelected(img) else onBackImageSelected(img)
                pickerSide = null
            },
        )
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
    image: DraftCardImage?,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
) {
    val colors = EchoTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = colors.foregroundMuted,
            )
            TextButton(
                onClick = onPickImage,
                modifier = Modifier.testTag("${tag}_image"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
            ) {
                Icon(Icons.Default.Image, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(if (image != null) R.string.image_sheet_change else R.string.edit_card_add_image),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W600,
                )
            }
        }
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
        image?.let { img ->
            Row(
                modifier = Modifier
                    .testTag("${tag}_image_chip")
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceSecondary)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AsyncImage(
                    model = img.url ?: img.bytes,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onRemoveImage,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.srsAgain),
                ) {
                    Text(stringResource(R.string.image_sheet_remove), fontSize = 12.sp)
                }
            }
        }
    }
}
