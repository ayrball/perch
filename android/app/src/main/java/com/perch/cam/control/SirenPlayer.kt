package com.perch.cam.control

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Loud looping alarm sound the web viewer can trigger to scare someone off. */
class SirenPlayer(context: Context) {

    private val tag = "Perch:Siren"
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var previousAlarmVolume: Int? = null
    private val stopRunnable = Runnable { stop() }

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Plays for [seconds] (restarting the countdown if already sounding). Safe from any thread. */
    fun start(seconds: Int) {
        // Report the new state right away so an API response sent a moment later is truthful;
        // the audio itself starts on the main thread and resets this if it fails.
        _active.value = true
        handler.post {
            handler.removeCallbacks(stopRunnable)
            if (player == null && !begin()) {
                _active.value = false
                return@post
            }
            handler.postDelayed(stopRunnable, seconds * 1000L)
        }
    }

    fun stop() {
        _active.value = false
        handler.post {
            handler.removeCallbacks(stopRunnable)
            end()
        }
    }

    private fun begin(): Boolean {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return false.also { Log.w(tag, "No alarm sound available on this device") }

        return try {
            previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(appContext, uri)
                isLooping = true
                prepare()
                start()
            }
            _active.value = true
            true
        } catch (e: Exception) {
            Log.e(tag, "Could not start siren", e)
            end()
            false
        }
    }

    private fun end() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
        previousAlarmVolume?.let {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0)
            } catch (_: Exception) {
            }
        }
        previousAlarmVolume = null
        _active.value = false
    }
}
