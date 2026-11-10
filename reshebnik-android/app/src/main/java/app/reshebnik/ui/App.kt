package app.reshebnik.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Tab(val title: String, val icon: ImageVector) {
    Calculator("Калькулятор", AppIcons.Calculator),
    Solver("По фото", AppIcons.Camera),
    History("История", AppIcons.History),
}

/**
 * Каркас приложения: экран выбранного раздела и переключатель разделов.
 * В книжной ориентации переключатель внизу, в альбомной — узкой колонкой слева.
 *
 * @param content получает раздел и признак альбомной раскладки
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppScaffold(tab: Tab, onTabChange: (Tab) -> Unit, content: @Composable (tab: Tab, landscape: Boolean) -> Unit) {
    val palette = LocalPalette.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.desk)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
    ) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                TabRail(tab, onTabChange)
                Box(Modifier.weight(1f).fillMaxHeight()) { content(tab, true) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content(tab, false) }
                // Пока открыта клавиатура, место нужнее полю ввода.
                if (!WindowInsets.isImeVisible) TabBar(tab, onTabChange)
            }
        }
    }
}

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(54.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tab.entries.forEach { tab ->
            val active = tab == selected
            val color = if (active) palette.ink else palette.textMuted
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (active) palette.keyTint else Color.Transparent)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                Text(
                    text = tab.title,
                    color = color,
                    fontFamily = Sans,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun TabRail(selected: Tab, onSelect: (Tab) -> Unit) {
    val palette = LocalPalette.current
    Column(
        modifier = Modifier.fillMaxHeight().width(64.dp).statusBarsPadding().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Tab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (active) palette.keyTint else Color.Transparent)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) })
                    .semantics { contentDescription = tab.title },
                contentAlignment = Alignment.Center,
            ) {
                Icon(tab.icon, contentDescription = null, tint = if (active) palette.ink else palette.textMuted, modifier = Modifier.size(22.dp))
            }
        }
    }
}
