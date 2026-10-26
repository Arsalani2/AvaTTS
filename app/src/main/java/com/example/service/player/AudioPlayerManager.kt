package com.example.service.player

import android.content.Context
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
        try {
            val player = MediaPlayer().apply {
                setDataSource(audioPath)
                setOnPreparedListener { mp ->
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
                        _playerState.value = PlayerState(error = "خطا در شروع پخش: ${e.message}")
                    }
                }
                setOnCompletionListener {
                    _playerState.value = _playerState.value.copy(
                        isPlaying = false,
                        currentPositionMs = 0L
                    )
                    stopTicker()
                }
                setOnErrorListener { _, what, extra ->
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
        try {
            mediaPlayer?.let { mp ->
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
