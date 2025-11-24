package org.mz.mzdkplayer.data.repository


import kotlinx.coroutines.flow.Flow
import org.mz.mzdkplayer.data.local.MediaHistoryDao
import org.mz.mzdkplayer.data.local.MediaHistoryEntity
import org.mz.mzdkplayer.data.model.HistoryWithMetadata
import org.mz.mzdkplayer.data.model.MediaHistoryRecord

class RoomMediaHistoryRepository(private val historyDao: MediaHistoryDao) {

    // 保存历史 (接收旧的 MediaHistoryRecord 对象以兼容现有代码，或者你直接改用 Entity)
    suspend fun saveHistory(record: MediaHistoryRecord) {
        val entity = MediaHistoryEntity(
            mediaUri = record.mediaUri,
            fileName = record.fileName,
            playbackPosition = record.playbackPosition,
            mediaDuration = record.mediaDuration,
            protocolName = record.protocolName,
            connectionName = record.connectionName,
            serverAddress = record.serverAddress,
            timestamp = System.currentTimeMillis(),
            mediaType = record.mediaType
        )
        historyDao.insertHistory(entity)
    }

    // 获取视频历史（带元数据）
    fun getVideoHistory(): Flow<List<HistoryWithMetadata>> {
        return historyDao.getVideoHistoryWithMetadata()
    }

    // 获取音频历史
    fun getAudioHistory(): Flow<List<MediaHistoryEntity>> {
        return historyDao.getAudioHistory()
    }

    suspend fun deleteHistory(uri: String) {
        historyDao.deleteHistoryByUri(uri)
    }

    suspend fun clearHistory() {
        historyDao.clearAllHistory()
    }

    // 供播放器查询断点
    suspend fun getHistoryPosition(uri: String): Long {
        return historyDao.getHistoryByUri(uri)?.playbackPosition ?: 0L
    }
}