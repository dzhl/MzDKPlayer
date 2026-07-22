package org.mz.mzdkplayer.player.vlc

import android.content.Context
import android.net.Uri
import android.util.Log
import org.mz.mzdkplayer.ui.screen.common.MzToastManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.media3.common.util.UnstableApi
import org.mz.mzdkplayer.player.core.IMzPlayer
import org.mz.mzdkplayer.player.core.MzBasicTrack
import org.mz.mzdkplayer.player.core.MzIsoTitle
import org.mz.mzdkplayer.player.core.MzVideoTrack
import org.mz.mzdkplayer.tool.FtpDataSource
import org.mz.mzdkplayer.tool.SmbDataSource
import org.mz.mzdkplayer.tool.Tools
import org.mz.mzdkplayer.tool.WebDavDataSource
import org.mz.mzdkplayer.ui.screen.vm.SettingsViewModel

import org.mz.mzdkplayer.ui.screen.vm.VideoPlayerStatus
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.collections.mapIndexed
import kotlin.time.Duration.Companion.milliseconds

@UnstableApi
class MzVlcPlayer(
    private val context: Context,
    private val mediaUri: String,
    dataSourceType: String,
    // 如果需要，可以把语言偏好也传进来
    settingsViewModel: SettingsViewModel
) : IMzPlayer {
    val isPassthroughEnabled = settingsViewModel.uiState.value.enablePassthrough // 获取当前的设置状态
    val preferredAudioLang: String = settingsViewModel.uiState.value.audioLang
    val preferredTextLang: String =settingsViewModel.uiState.value.subLang
    private val isNetworkProtocol: Boolean by lazy {
        val lower = mediaUri.lowercase()
        lower.startsWith("http://") || lower.startsWith("https://") ||
                lower.startsWith("ftp://") || lower.startsWith("smb://") ||
                lower.startsWith("nfs://") || lower.startsWith("rtsp://") ||
                lower.startsWith("rtmp://") ||
                !lower.startsWith("file:///")   // 其他全部当网络处理
    }
    // 1. 初始化 VLC 命令行参数
    private val options = arrayListOf(
        "-vvv",
        "--mediacodec-dr",
        // "--vout=android_display", // VLC 4.0 不再建议显式指定，可能导致黑屏
        // "--no-video-deco",
        "--aout=audiotrack",
        // 动态 caching（本地快，网络稳）
        "--file-caching=${if (!isNetworkProtocol) 500 else 1200}",
        "--network-caching=5000",
        "--clock-jitter=0",

        "--clock-synchro=0",
        "--sub-autodetect-file",
        "--sub-autodetect-fuzzy=2" // 模糊匹配：1=始终匹配，2=匹配文件名（含后缀）
    ).apply {
        // 如果开启了直通，在全局参数里也加上支持
        if (isPassthroughEnabled) {
            add("--spdif")

        }
        //add(":no-bluray-menu")
        // 1. 获取解压后的字体绝对路径
        //val internalFontPath = Tools.prepareFont(context, "SmileySans-Oblique.ttf")
         //强制 FreeType 渲染器使用该字体文件
        add("--freetype-font=Noto Serif CJK SC")
        //add("--freetype-font=Roboto")
        //add("--freetype-font=sans-serif")
        //add("--freetype-font=思源黑体 CN")
        //add("--freetype-font=得意黑")
        //add("--freetype-bold-font=$internalFontPath")
       // add("--freetype-italic-font=$internalFontPath")
        //add("--freetype-monospaced-font=$internalFontPath")
        // 允许使用相对字体样式（增强兼容性）

        // 强制字幕解码器使用 UTF-8，防止某些非特效字幕乱码
        //add("--subsdec-encoding=UTF-8")
        // 核心字体设置
        //add("--freetype-font=Roboto")   // 改成思源黑体
        add("--freetype-rel-fontsize=20")
        add("--freetype-opacity=255")
        add("--freetype-color=0xFFFFFFFF")
        add("--freetype-background-opacity=180")
        add("--freetype-background-color=0x000000")
        add("--text-renderer=freetype")
        // VLC 的语言设置通常在初始化时通过参数传入
        add("--audio-language=$preferredAudioLang")
        add("--sub-language=$preferredTextLang")

    }

    private val libVLC = LibVLC(context, options)
    private val mediaPlayer = MediaPlayer(libVLC)

    private var lastSubtitleCount = 0

    // 2. 实现接口要求的状态流
    private val _playerStatus = MutableStateFlow<VideoPlayerStatus>(VideoPlayerStatus.IDLE)
    override val playerStatus: StateFlow<VideoPlayerStatus> = _playerStatus.asStateFlow()

    private val _isPlayingFlow = MutableStateFlow(false)
    override val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow.asStateFlow()

    private val _videoTracks = MutableStateFlow<List<MzVideoTrack>>(emptyList())
    override val videoTracks: StateFlow<List<MzVideoTrack>> = _videoTracks.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<MzBasicTrack>>(emptyList())
    override val audioTracks: StateFlow<List<MzBasicTrack>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<MzBasicTrack>>(emptyList())
    override val subtitleTracks: StateFlow<List<MzBasicTrack>> = _subtitleTracks.asStateFlow()
    // 1. 增加变量
    private val _isoTitles = MutableStateFlow<List<MzIsoTitle>>(emptyList())
    override val isoTitles: StateFlow<List<MzIsoTitle>> = _isoTitles.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    // 3. 接口回调
    override var onError: ((String) -> Unit)? = null
    override var onCuesChanged: ((Any) -> Unit)? = null // VLC 不需要，留空

    init {

        mediaPlayer.setAudioDigitalOutputEnabled(isPassthroughEnabled)
        Log.e("VLCPlayer", "isPassthroughEnabled → $isPassthroughEnabled")
//        if (isPassthroughEnabled) {
        mediaPlayer.setAudioOutput("audiotrack")
        //    }
        // 设置事件监听器同步状态
        Log.d("VLCPlayer", "mediaStr → $mediaUri")

        // 🟢 关键修复：不再使用可能导致二次编码错误的 Tools.encodeUrlForPlayer
        // 既然是从 Base64 解码回来的原始字符串，直接解析为 Uri 即可
        val media = Media(libVLC, mediaUri.toUri()).apply {
            setHWDecoderEnabled(true, true)
            //addOption(":codec=mediacodec_ndk")

//            // 3. 只有开启直通时，才注入这些 Media Option
//            if (isPassthroughEnabled) {
//                addOption(":audio-passthrough=1")
//                addOption(":spdif=hdmi")
//                addOption(":audio-passthrough=hdmi")
////                addOption(":http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
//                // 注意：如果电视不支持 TrueHD，VLC 只要看到这几个参数就会尝试透传
//                // 如果透传失败就会没声音。目前最稳妥是让用户在设置里切开关。
//            } else {
//                addOption(":audio-passthrough=0")
//            }
            //addOption(":demux=ts")
            addOption(":http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            addOption(":no-osd")
            addOption(":file-caching=${if (!isNetworkProtocol) 500 else 1200}")          // 实验代码里有的，建议加上
        }
        // 针对网络流优化


        mediaPlayer.media = media
        // 3. ⭐️ 关键：在这里立即释放局部引用
        media.release()
        setupMediaParseListener()
        setupEventListener()

    }
    // 在 MzVlcPlayer 类中添加成员
    private var _videoWidth = 1920
    private var _videoHeight = 1080

    // 在 VLC 视频尺寸变化回调里更新（通常是 onNewVideoLayout 或 IVLCVout.Callback）
    override val videoWidth: Int get() = _videoWidth
    override val videoHeight: Int get() = _videoHeight
    private fun setupEventListener() {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    _isPlayingFlow.value = true
                    _playerStatus.value = VideoPlayerStatus.READY
//                    CoroutineScope(Dispatchers.Main).launch {
//                        delay(1000)
//                        updateTracks()
//                    }
                    //updateTracks() // 播放开始后刷新轨道信息
                }
                MediaPlayer.Event.LengthChanged -> {
                    // 当 VLC 解析出总时长时会触发这里
                    Log.d("VLCPlayer", "检测到总时长变化: ${event.lengthChanged} ms")
                    // 这里可以触发 UI 刷新，确保进度条的总长不再是 0
                }
                // ⭐️ 新增：监听 Title 改变（蓝光切换章节/正片最有效的信号）
                // 当从菜单进入正片，Title 通常会从 0 变更为正片的索引
                MediaPlayer.Event.Paused -> {
                    _isPlayingFlow.value = false
                }
                MediaPlayer.Event.Stopped -> {
                    _isPlayingFlow.value = false
                    _playerStatus.value = VideoPlayerStatus.ENDED
                }
                MediaPlayer.Event.Buffering -> {
                    // VLC 的 buffering 状态包含百分比，缓冲到 100% 时切回 READY
                    if (event.buffering == 100f) {
                        _isPlayingFlow.value = true // 缓冲开始，视为停止播放
                        _playerStatus.value = VideoPlayerStatus.READY
                    } else {
                        _isPlayingFlow.value = false // 缓冲结束
                        _playerStatus.value = VideoPlayerStatus.BUFFERING
                    }
                }
                MediaPlayer.Event.EndReached -> {
                    _playerStatus.value = VideoPlayerStatus.ENDED
                }
                MediaPlayer.Event.EncounteredError -> {
                    onError?.invoke("VLC 播放出错")
                }
                // 当轨道发生变化时（例如添加了外部字幕），刷新列表
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted -> {
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(1000.milliseconds)
                        updateTracks()
                    }
                }
            }
        }
    }
    private fun setupMediaParseListener() {
        val media = mediaPlayer.media ?: return

        media.setEventListener { event ->
            if (event.type == IMedia.Event.ParsedChanged &&
                event.parsedStatus == IMedia.ParsedStatus.Done) {
                Log.d("MzVlcPlayer", " Media 解析完成！协议: ${if(isNetworkProtocol) "网络(SMB/NFS/FTP)" else "本地 file:///"}")
                updateTracks()
            }
        }

        // 立即启动解析（关键修复）
        if (!media.isParsed) {
            val parseFlag = if (isNetworkProtocol)
                IMedia.Parse.ParseNetwork      // SMB/NFS/FTP/HTTP 必须用这个
            else
                IMedia.Parse.ParseLocal        // file:/// 用这个更快

            media.parseAsync(parseFlag)
            Log.d("MzVlcPlayer", " 启动解析模式: ${if(isNetworkProtocol) "ParseNetwork" else "ParseLocal"}")
        }
    }
    private fun updateTracks() {

        // 1. 获取 Media 的所有轨道（4.0 使用 getTracks API）
        // 注意：4.0 不再返回 id 为 -1 的禁用轨，我们需要在 UI 层或此处手动构造一个 "None" 轨

        // 2. 🎧 音频轨道
        val audioTracks = mediaPlayer.getTracks(IMedia.Track.Type.Audio) ?: emptyArray()
        val audioList = mutableListOf<MzBasicTrack>()
        // 添加禁用选项
        audioList.add(
            MzBasicTrack(
                id = "-1",
                index = -1,
                name = "关闭音频",
                isSelected = mediaPlayer.getSelectedTrack(IMedia.Track.Type.Audio) == null,
                rawData = "-1"
            )
        )
        audioTracks.forEachIndexed { index, track ->
            val audioTrack = track as? IMedia.AudioTrack
            audioList.add(
                MzBasicTrack(
                    id = track.id,
                    index = index,
                    language = track.language ?: "",
                    channelCount = audioTrack?.channels ?: 0,
                    mimeType = track.codec ?: "",
                    sampleRate = audioTrack?.rate ?: 0,
                    bitrate = track.bitrate,
                    name = track.name ?: "音轨 $index",
                    isSelected = track.selected,
                    rawData = track.id
                )
            )
        }
        _audioTracks.value = audioList

        // 3. 📝 字幕轨道
        val spuTracks = mediaPlayer.getTracks(IMedia.Track.Type.Text) ?: emptyArray()
        val spuList = mutableListOf<MzBasicTrack>()
        spuList.add(
            MzBasicTrack(
                id = "-1",
                index = -1,
                name = "关闭字幕",
                isSelected = mediaPlayer.getSelectedTrack(IMedia.Track.Type.Text) == null,
                rawData = "-1"
            )
        )
        spuTracks.forEachIndexed { index, track ->
            spuList.add(
                MzBasicTrack(
                    id = track.id,
                    index = index,
                    language = track.language ?: "",
                    mimeType = track.codec ?: "",
                    name = track.name ?: "字幕 $index",
                    isSelected = track.selected,
                    rawData = track.id
                )
            )
        }
        _subtitleTracks.value = spuList

        // 4. 📺 视频轨道
        val videoTracks = mediaPlayer.getTracks(IMedia.Track.Type.Video) ?: emptyArray()
        _videoTracks.value = videoTracks.mapIndexed { index, track ->
            val vTrack = track as? IMedia.VideoTrack
            MzVideoTrack(
                id = track.id,
                index = index,
                height = vTrack?.height ?: 0,
                bitrate = track.bitrate,
                codecs = track.codec ?: "",
                isSelected = track.selected,
                rawData = track.id
            )
        }

        // 5. 💿 蓝光/DVD Titles
        val titles = mediaPlayer.titles // 4.0 titles 属性
        if (titles != null && titles.isNotEmpty()) {
            val currentTitleIdx = mediaPlayer.title // 4.0 title 属性
            _isoTitles.value = titles.mapIndexed { index, title ->
                MzIsoTitle(
                    index = index,
                    name = if (title.name.isNullOrBlank()) "视频片段 ${index + 1}" else title.name,
                    isSelected = index == currentTitleIdx,
                    durationText = Tools.formatTime(title.duration)
                )
            }
        } else {
            _isoTitles.value = emptyList()
        }
    }

    // 3. 实现接口方法
    override fun selectIsoTitle(index: Int) {
        mediaPlayer.title = index
        updateTracks() // 切换后刷新一下选中状态
    }

    override val isPlaying: Boolean get() = mediaPlayer.isPlaying
    override val currentPosition: Long get() = mediaPlayer.time
    override val duration: Long get() = mediaPlayer.length

    override fun play() { mediaPlayer.play() }
    override fun pause() { mediaPlayer.pause() }
    override fun seekTo(positionMs: Long) { mediaPlayer.time = positionMs }
    override fun seekForward(ms: Long) { mediaPlayer.time = currentPosition + ms }
    override fun seekBack(ms: Long) { mediaPlayer.time = currentPosition - ms }

    override fun selectVideoTrack(track: MzVideoTrack) {
        val id = track.rawData as? String ?: track.id
        mediaPlayer.selectTrack(id)
    }

    override fun selectAudioTrack(track: MzBasicTrack) {
        val id = track.rawData as? String ?: track.id
        if (id == "-1") {
            mediaPlayer.unselectTrackType(IMedia.Track.Type.Audio)
        } else {
            mediaPlayer.selectTrack(id)
        }
        updateTracks()
    }

    override fun selectSubtitleTrack(track: MzBasicTrack) {
        val id = track.rawData as? String ?: track.id
        if (id == "-1") {
            mediaPlayer.unselectTrackType(IMedia.Track.Type.Text)
        } else {
            mediaPlayer.selectTrack(id)
        }
        updateTracks()
    }


    override fun release() {
        mediaPlayer.stop()
        mediaPlayer.release()

        libVLC.release()

        // 虽然 VLC 可能有自己的连接管理，但如果共用了 DataSources 或为了保险，统一清理
        SmbDataSource.releaseGlobalResources()
        FtpDataSource.releaseGlobalResources()
        WebDavDataSource.releaseGlobalResources()
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (isPassthroughEnabled && speed != 1.0f) {
            return
        }
        _playbackSpeed.value = speed
        mediaPlayer.rate = speed
    }

    @Composable
    override fun PlayerView(modifier: Modifier) {
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).apply {
                    mediaPlayer.attachViews(this, null, true, false)
                    // 关键修改：在这里才真正开始播放，或者触发一个状态通知
                    if (!mediaPlayer.isPlaying) {
                        mediaPlayer.play()
                    }
                }
            },
            modifier = modifier.fillMaxSize(),
            onRelease = {
                mediaPlayer.detachViews()
            }
        )
    }

    override fun addExternalSubtitles(subtitles: List<Pair<String, String>>) {
        val media = mediaPlayer.media ?: return

        lastSubtitleCount = media.getTracks()?.size ?: 0 
        subtitles.forEach { (uri, _) ->
            // true 表示优先选中最后添加的那个
            mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, uri.toUri(), true)

        }
        MzToastManager.show("加载外部字幕中...")

    }
}
