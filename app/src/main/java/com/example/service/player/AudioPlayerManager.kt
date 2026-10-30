package com.example.service.player

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentAudioPath: String? = null,
    val currentChunkId: Long? = null,
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val isLoaded: Boolean = false,
    val error: String? = null
)

class AudioPlayerManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var progressTickerJob: Job? = null

    // BUG-21: Audio Focus — پخش دیگر با موسیقی دستگاه هم‌زمان نمی‌افتد
    private val audioManager: AudioManager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var hasAudioFocus = false
    @SuppressWarnings("deprecation") // requestAudioFocus ساده برای minSdk 24 کفایت می‌کند
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pause()
        }
    }
    @SuppressWarnings("deprecation")
    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result = audioManager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }
    @SuppressWarnings("deprecation")
    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            audioManager.abandonAudioFocus(audioFocusListener)
            hasAudioFocus = false
        }
    }

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    fun playOrPause(audioPath: String, chunkId: Long) {
        val file = File(audioPath)
        if (!file.exists() || !file.isFile || file.length() <= 44) {
            release()
            _playerState.value = PlayerState(
                error = "فایل صوتی در حافظه یافت نشد. ممکن است در خارج از برنامه پاک شده باشد."
            )
            return
        }

        if (_playerState.value.currentAudioPath == audioPath && mediaPlayer != null) {
            if (_playerState.value.isPlaying) {
                pause()
            } else {
                resume()
            }
            return
        }

        startPlayback(audioPath, chunkId)
    }

    private fun startPlayback(audioPath: String, chunkId: Long) {
        release()
        // BUG-21: بدون Audio Focus پخش شروع نمی‌شود (احترام به پخش‌کنندهٔ دیگر)
        if (!requestAudioFocus()) {
            _playerState.value = PlayerState(error = "پخش صوتی هم‌اکنون ممکن نیست؛ برنامهٔ صوتی دیگری در حال پخش است.")
            return
        }
        try {
            val player = MediaPlayer().apply {
                setDataSource(audioPath)
                setOnPreparedListener { mp ->
                    // BUG-48: اطمینان از اینکه کال‌بک مربوط به همین پلیر فعال است و پلیر تعویض یا آزاد نشده
                    if (mediaPlayer !== mp) return@setOnPreparedListener
                    try {
                        mp.start()
                        val duration = mp.duration.toLong().coerceAtLeast(0L)
                        _playerState.value = PlayerState(
                            isPlaying = true,
                            currentAudioPath = audioPath,
                            currentChunkId = chunkId,
                            currentPositionMs = 0L,
                            totalDurationMs = duration,
                            isLoaded = true
                        )
                        startTicker()
                    } catch (e: Exception) {
                        if (mediaPlayer === mp) {
                            _playerState.value = PlayerState(error = "خطا در شروع پخش: ${e.message}")
                        }
                    }
                }
                setOnCompletionListener { mp ->
                    // BUG-48: جلوگیری از بازنشانی وضعیت در صورتی که پلیر دیگری فعال شده است
                    if (mediaPlayer !== mp) return@setOnCompletionListener
                    _playerState.value = _playerState.value.copy(
                        isPlaying = false,
                        currentPositionMs = 0L
                    )
                    stopTicker()
                }
                setOnErrorListener { mp, what, extra ->
                    // BUG-48: نادیده گرفتن خطای پلیر قبلی پس از تعویض یا آزادسازی
                    if (mediaPlayer !== mp) return@setOnErrorListener true
                    _playerState.value = PlayerState(
                        error = "خطا در پخش فایل صوتی (کد $what)"
                    )
                    stopTicker()
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
        } catch (e: Exception) {
            _playerState.value = PlayerState(error = "خطا در بارگذاری صدا: ${e.message}")
        }
    }

    fun pause() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
            _playerState.value = _playerState.value.copy(isPlaying = false)
            stopTicker()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resume() {
        try {
            val player = mediaPlayer ?: return
            // If at end of track or position 0, seek to beginning before start
            if (_playerState.value.currentPositionMs >= _playerState.value.totalDurationMs && _playerState.value.totalDurationMs > 0) {
                player.seekTo(0)
            }
            player.start()
            _playerState.value = _playerState.value.copy(isPlaying = true)
            startTicker()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun seekTo(positionMs: Long) {
        try {
            mediaPlayer?.seekTo(positionMs.toInt())
            _playerState.value = _playerState.value.copy(currentPositionMs = positionMs)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        release()
        _playerState.value = PlayerState()
    }

    /** BUG-34: پاک‌کردن state خطا بعد از اینکه به کاربر نمایش داده شد */
    fun clearError() {
        if (_playerState.value.error != null) {
            _playerState.value = _playerState.value.copy(error = null)
        }
    }

    private fun startTicker() {
        stopTicker()
        progressTickerJob = scope.launch {
            while (isActive) {
                try {
                    val pos = mediaPlayer?.currentPosition?.toLong() ?: 0L
                    _playerState.value = _playerState.value.copy(currentPositionMs = pos)
                } catch (e: Exception) {
                    break
                }
                delay(200)
            }
        }
    }

    private fun stopTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = null
    }

    fun release() {
        stopTicker()
        // BUG-21: رهاکردن Audio Focus همراه با آزادکردن پلیر
        abandonAudioFocus()
        try {
            mediaPlayer?.let { mp ->
                try {
                    mp.setOnPreparedListener(null)
                    mp.setOnCompletionListener(null)
                    mp.setOnErrorListener(null)
                } catch (_: Exception) {}
                try {
                    if (mp.isPlaying) mp.stop()
                } catch (_: Exception) {}
                try {
                    mp.reset()
                } catch (_: Exception) {}
                mp.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaPlayer = null
    }
}
