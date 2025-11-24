package org.mz.mzdkplayer.ui.screen.history



import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.mz.mzdkplayer.data.model.HistoryWithMetadata
import org.mz.mzdkplayer.ui.screen.common.MediaCard

@Composable
fun VideoHistoryCard(
    historyItem: HistoryWithMetadata,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val history = historyItem.history
    val metadata = historyItem.metadata

    // 优先使用元数据中的标题和图片，如果没有则使用文件名
    val title = metadata?.title ?: history.fileName
    val posterPath = metadata?.posterPath
    val year = metadata?.releaseDate?.take(4)

    Box(modifier = modifier) {
        // 1. 复用你现有的 MediaCard 基础外观
        MediaCard(
            title = title,
            posterPath = posterPath,
            year = year,
            onClick = onClick,
            modifier = Modifier.fillMaxSize()
        )

        // 2. 覆盖层：显示协议标签 (左上角)
        Box(
            modifier = Modifier
                .padding(12.dp) // 对应 MediaCard 的圆角内边距
                .align(Alignment.TopStart)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = history.connectionName.ifEmpty { history.protocolName },
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // 3. 覆盖层：显示进度条 (图片底部，文字上方)
        // 注意：MediaCard 内部布局是 Column(Image, Text)。
        // 这里的覆盖层可能需要调整位置，或者你需要修改 MediaCard 允许传入 overlay。
        // 为了简单，我们直接浮动在 Card 内容之上，位于图片区域底部。

        // 计算进度
        val percentage = history.getPlaybackPercentage() / 100f

        if (percentage > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center) // 粗略对齐，建议根据 MediaCard 的比例调整
                    .padding(horizontal = 12.dp) // 对应 Card padding
                    .padding(bottom = 60.dp) // 上移以避开 MediaCard底部的文字区域
            ) {
                // 剩余时间提示 (可选)
                /* Text(
                    text = "${percentage * 100}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.End)
                )
                */

                // 底部对齐的进度条容器
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                ) {
                    // 实际进度
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(percentage)
                            .fillMaxHeight()
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color(0xFFFFC200), Color(0xFFFF9800))
                                ),
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
        }
    }
}