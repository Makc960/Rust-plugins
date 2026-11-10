package app.reshebnik.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(apiKey: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.paper,
    ) {
        SettingsContent(apiKey, onSave, onDismiss)
    }
}

@Composable
fun SettingsContent(apiKey: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    var draft by rememberSaveable { mutableStateOf(apiKey) }
    var visible by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Ключ Claude API", color = palette.text, fontFamily = Serif, fontSize = 26.sp)
        Text(
            text = "Задания по фото решает нейросеть Claude. Ключ создаётся в console.anthropic.com, в разделе API Keys. " +
                "Anthropic берёт плату за каждый запрос по своим тарифам.",
            color = palette.textMuted,
            fontFamily = Sans,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Ключ") },
            placeholder = { Text("sk-ant-…") },
            singleLine = true,
            textStyle = TextStyle(fontFamily = Sans, fontSize = 16.sp),
            shape = RoundedCornerShape(14.dp),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            trailingIcon = { TextAction(if (visible) "Скрыть" else "Показать", { visible = !visible }) },
        )
        Text(
            text = "Ключ хранится только на этом телефоне и отправляется только в Anthropic.",
            color = palette.textFaint,
            fontFamily = Sans,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        PrimaryButton("Сохранить", onClick = { onSave(draft); onDismiss() })
        if (apiKey.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TextAction("Удалить ключ", onClick = { onSave(""); onDismiss() }, color = palette.danger)
            }
        }
    }
}
