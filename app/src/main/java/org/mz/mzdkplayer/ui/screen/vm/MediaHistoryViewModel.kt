package org.mz.mzdkplayer.ui.screen.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.mz.mzdkplayer.data.model.MediaHistoryRecord
import org.mz.mzdkplayer.data.repository.RoomMediaHistoryRepository

class MediaHistoryViewModel(
    private val repository: RoomMediaHistoryRepository
) : ViewModel() {

    // 视频历史流 (StateFlow)
    val videoHistory = repository.getVideoHistory()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    /**
     * 保存历史记录
     * 这里的 launch 确保了即使 UI 销毁，ViewModel 作用域内的保存操作通常也能完成
     */
    fun saveHistory(record: MediaHistoryRecord) {
        viewModelScope.launch {
            repository.saveHistory(record)
        }
    }
    // 音频历史流
    val audioHistory = repository.getAudioHistory()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun deleteHistory(uri: String) {
        viewModelScope.launch {
            repository.deleteHistory(uri)
        }
    }
    /**
     * 【新增】获取历史播放位置
     * 这是一个 suspend 函数，必须在协程中调用
     */
    suspend fun getHistoryPosition(mediaUri: String): Long {
        return repository.getHistoryPosition(mediaUri)
    }
    fun clearAll() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }
}