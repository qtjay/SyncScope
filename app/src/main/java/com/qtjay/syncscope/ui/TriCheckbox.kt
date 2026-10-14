package com.qtjay.syncscope.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 三态取值。
 *  - ON：该目录/文件被选中同步
 *  - OFF：未选中（被忽略）
 *  - PARTIAL：自身未选中，但其下有被选中的子项（显示 "-" 标记）
 */
enum class TriState {
    ON,
    OFF,
    PARTIAL,
}

/**
 * 自绘的三态复选框。
 *
 * 不使用 material3 的 TriStateCheckbox，是为了避开不同 Compose 版本之间
 * ToggleableState 所属包不一致的问题（foundation.selection / ui.state）。
 *
 * @param onToggle 回调参数为「切换后是否应选中的新状态」
 */
@Composable
fun TriCheckbox(
    state: TriState,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val color: Color = if (state == TriState.OFF) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.primary
    }
    IconButton(
        onClick = { onToggle(state != TriState.ON) },
        enabled = enabled,
        modifier = modifier.size(40.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(2.dp, color, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                TriState.ON -> Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已选中",
                    tint = color,
                    modifier = Modifier.size(16.dp),
                )

                TriState.PARTIAL -> Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "部分选中",
                    tint = color,
                    modifier = Modifier.size(16.dp),
                )

                TriState.OFF -> Unit
            }
        }
    }
}
