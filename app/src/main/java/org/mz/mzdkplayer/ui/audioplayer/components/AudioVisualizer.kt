package org.mz.mzdkplayer.ui.audioplayer.components


import android.media.audiofx.Visualizer
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.log10


@Composable
fun AudioVisualizer(
    audioSessionId: Int,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 30,
    color: Color = Color.Cyan
) {
    // 1. 获取 Composable 作用域，用于安全地切换到主线程更新状态
    val coroutineScope = rememberCoroutineScope()

    // 检查输入参数
    LaunchedEffect(audioSessionId, isPlaying) {
        Log.d("VisDebug", "Composable Update -> ID: $audioSessionId, isPlaying: $isPlaying")
    }

    var fftBytes by remember { mutableStateOf(ByteArray(0)) }
    // ⭐ 计数器状态依然保留，但这次只在主线程更新，用于确保重绘
    var dataUpdateTick by remember { mutableIntStateOf(0) }

    DisposableEffect(audioSessionId) {
        if (audioSessionId == 0) {
            Log.w("VisDebug", "❌ Abort Init: audioSessionId is 0. Waiting for real ID...")
            return@DisposableEffect onDispose { }
        }

        Log.d("VisDebug", "🚀 Starting Visualizer init for ID: $audioSessionId")

        var visualizer: Visualizer? = null
        try {
            visualizer = Visualizer(audioSessionId)
            if (!visualizer.enabled) {
                Log.d("VisDebug", "Visualizer created but not enabled yet.")
            }

            visualizer.apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                val desiredRate = Visualizer.getMaxCaptureRate()

                Log.d("VisDebug", "Device Max Rate: $desiredRate mHz")

                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                    var logCounter = 0
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {

                        if (fft != null) {
                            // 1. 在 Coroutine Scope 内更新状态，保证在主线程执行，触发重绘
                            coroutineScope.launch {
                                fftBytes = fft
                                dataUpdateTick = (dataUpdateTick + 1) % 100000
                            }

                            // 2. 调整日志频率，现在每 10 次回调打印一次，更细致地观察速率
                            if (logCounter++ % 10 == 0) {
                                val hasData = fft.any { it != 0.toByte() }
                                // 注意：此日志在后台线程打印
                                Log.d("VisDebug", "📡 Data received (BGT). Has Non-Zero Data: $hasData")
                            }
                        } else {
                            if (logCounter++ % 60 == 0) Log.w("VisDebug", "⚠️ Received NULL FFT data")
                        }
                    }
                }, desiredRate , false, true) // 使用 desiredRate

                enabled = true
                Log.d("VisDebug", "✅ Visualizer enabled successfully")
            }
        } catch (e: Exception) {
            Log.e("VisDebug", "🔥 FATAL ERROR initializing visualizer: ${e.message}", e)
        }

        onDispose {
            Log.d("VisDebug", "🗑️ Releasing Visualizer")
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
        }
    }

    // 4. 检查绘制条件，依赖 dataUpdateTick 来确保每次数据更新都重绘
    if (isPlaying && fftBytes.isNotEmpty() && dataUpdateTick >= 0) {
        Canvas(modifier = modifier) {
            if (size.width <= 0 || size.height <= 0) {
                Log.e("VisDebug", "⚠️ Canvas size is INVALID: ${size.width} x ${size.height}")
                return@Canvas
            }

            val widthPerBar = size.width / barCount
            val gap = 4.dp.toPx()
            val barWidth = widthPerBar - gap
            val n = fftBytes.size
            val step = (n / 2) / barCount

            for (i in 0 until barCount) {
                val index = (i * step) * 2
                if (index + 1 >= n) break

                val r = fftBytes[index].toFloat()
                val img = fftBytes[index + 1].toFloat()

                // 1. 计算振幅，并添加偏置 (Bias) 消除底噪带来的全零静止
                val magnitude = hypot(r, img)
                val magnitudeWithBias = magnitude + 10f

                // 2. 转换为分贝值。注意使用 log10(x) 的输入 x >= 1
                // dbValue = 10 * log10(magnitude^2)
                val dbValue = 10 * log10(magnitudeWithBias * magnitudeWithBias)

                // 3. 动态计算高度，使用一个较大的缩放因子（例如 15），让频谱更灵敏
                val barHeight = (dbValue * 15 * size.height / 200)
                    .coerceIn(0.0F, size.height.toFloat())
                    .toFloat()

                // ⚠️ 调试成功后请移除此行日志，因为它会严重影响性能
                 Log.d("VisDebug",barHeight.toString())

                drawLine(
                    brush = SolidColor(color),
                    start = Offset(x = i * widthPerBar + gap / 2, y = size.height),
                    end = Offset(x = i * widthPerBar + gap / 2, y = size.height - barHeight),
                    strokeWidth = barWidth
                )
            }
        }
    } else {
        // 记录为什么不显示
        if (!isPlaying) Log.v("VisDebug", "🙈 Hidden: Not playing")
        else if (fftBytes.isEmpty()) Log.v("VisDebug", "🙈 Hidden: No FFT data yet")
    }
}