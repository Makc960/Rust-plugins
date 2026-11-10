package app.reshebnik.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.reshebnik.data.HistoryEntry
import app.reshebnik.solver.TaskMode
import app.reshebnik.solver.toPlainText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Список решённых заданий.
 *
 * @param loadThumbnail миниатюра снимка по идентификатору записи
 */
@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    loadThumbnail: suspend (String) -> Bitmap?,
    onOpen: (HistoryEntry) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleting by remember { mutableStateOf<HistoryEntry?>(null) }
    var clearing by remember { mutableStateOf(false) }

    NotebookPage(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = PageStartPadding, top = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Wordmark(Modifier.padding(vertical = 10.dp))
                if (entries.isNotEmpty()) TextAction("Очистить", { clearing = true }, color = LocalPalette.current.textMuted)
            }
            if (entries.isEmpty()) {
                Column(Modifier.padding(start = PageStartPadding, end = PageEndPadding, top = 20.dp)) {
                    PageTitle("История")
                    PageText("Здесь появятся решённые задания — к любому можно вернуться и задать вопрос.", Modifier.padding(top = 10.dp))
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
                    item { PageTitle("История", Modifier.padding(start = PageStartPadding, bottom = 8.dp)) }
                    items(entries, key = { it.id }) { entry ->
                        HistoryRow(entry, loadThumbnail, onClick = { onOpen(entry) }, onDelete = { deleting = entry })
                    }
                }
            }
        }
    }

    deleting?.let { entry ->
        Confirm(
            title = "Удалить запись?",
            text = "Решение и вопросы по нему пропадут.",
            confirm = "Удалить",
            onConfirm = { onDelete(entry.id) },
            onDismiss = { deleting = null },
        )
    }
    if (clearing) {
        Confirm(
            title = "Очистить историю?",
            text = "Все решённые задания будут удалены с телефона.",
            confirm = "Очистить",
            onConfirm = onClear,
            onDismiss = { clearing = false },
        )
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, loadThumbnail: suspend (String) -> Bitmap?, onClick: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalPalette.current
    val thumbnail by produceState<Bitmap?>(null, entry.id) { value = if (entry.hasPhoto) loadThumbnail(entry.id) else null }
    val problem = remember(entry) { entry.solution.problem.toPlainText(" ").ifBlank { entry.text } }
    val answer = remember(entry) { entry.solution.answer.toPlainText(" ") }

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = PageStartPadding, end = 0.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(10.dp)
        Box(Modifier.size(54.dp).clip(shape).background(palette.keyTint), contentAlignment = Alignment.Center) {
            val image = thumbnail
            if (image != null) {
                Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(if (entry.hasPhoto) AppIcons.Gallery else AppIcons.Pencil, contentDescription = null, tint = palette.ink, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(problem, color = palette.text, fontFamily = Serif, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (answer.isNotEmpty()) {
                Text(answer, color = palette.ink, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                text = formatDate(entry.createdAt) + if (entry.mode == TaskMode.CHECK) " · проверка решения" else "",
                color = palette.textFaint,
                fontFamily = Sans,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconAction(AppIcons.Trash, "Удалить запись", onDelete, tint = palette.textFaint)
    }
}

private fun formatDate(millis: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(millis))

@Composable
private fun Confirm(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.paper,
        title = { Text(title, color = palette.text, fontFamily = Serif, fontSize = 22.sp) },
        text = { Text(text, color = palette.textMuted, fontFamily = Sans, fontSize = 15.sp, lineHeight = 22.sp) },
        confirmButton = { TextAction(confirm, { onConfirm(); onDismiss() }, color = palette.danger) },
        dismissButton = { TextAction("Отмена", onDismiss, color = palette.textMuted) },
    )
}
