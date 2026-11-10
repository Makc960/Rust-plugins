package app.reshebnik.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Tab(val title: String, val icon: ImageVector) {
    Calculator("Калькулятор", AppIcons.Calculator),
    Solver("По фото", AppIcons.Camera),
}

/** Каркас приложения: экран выбранного раздела и переключатель разделов внизу. */
@Composable
fun AppScaffold(tab: Tab, onTabChange: (Tab) -> Unit, content: @Composable (Tab) -> Unit) {
    val palette = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.desk)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content(tab) }
        Tabs(tab, onTabChange)
    }
}

@Composable
private fun Tabs(selected: Tab, onSelect: (Tab) -> Unit) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tab.entries.forEach { tab ->
            val active = tab == selected
            val color = if (active) palette.ink else palette.textMuted
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (active) palette.keyTint else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = { onSelect(tab) }),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tab.title,
                    color = color,
                    fontFamily = Sans,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 15.sp,
                )
            }
        }
    }
}
