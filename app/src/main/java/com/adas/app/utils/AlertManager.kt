package com.adas.app.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.adas.app.detection.CollisionCriteria
import com.adas.app.detection.WarningLevel
import java.util.Locale

/**
 * Alert manager handling:
 * - Haptic vibration (on/off via criteria)
 * - Speaker beep tones (on/off via criteria)
 * - TTS spoken alerts (on/off via criteria)
 * Per-level cooldowns prevent alert spam.
 */
class AlertManager(private val context: Context) {

    companion object {
        private const val TAG = "AlertManager"
    }

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var toneGen: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var lastLevelMs   = mutableMapOf<WarningLevel, Long>()
    private var lastSpeechMs  = 0L
    private var criteria      = CollisionCriteria()

    init {
        setupTone()
        setupTts()
    }

    private fun setupTone() {
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 90)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator unavailable: ${e.message}")
        }
    }

    private fun setupTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.1f)
                ttsReady = true
                Log.i(TAG, "TTS ready")
            }
        }
    }

    fun updateCriteria(c: CollisionCriteria) { criteria = c }

    fun alert(level: WarningLevel, message: String = "") {
        val now = System.currentTimeMillis()
        val cooldown = when (level) {
            WarningLevel.DANGER  -> 700L
            WarningLevel.WARNING -> 1500L
            WarningLevel.CAUTION -> 3000L
            WarningLevel.SAFE    -> Long.MAX_VALUE
        }
        val last = lastLevelMs[level] ?: 0L
        if (now - last < cooldown) return
        lastLevelMs[level] = now

        if (criteria.vibrationEnabled) vibrate(level)
        if (criteria.speakerEnabled) playTone(level)
        if (criteria.speakerEnabled && ttsReady && message.isNotBlank()
            && now - lastSpeechMs > 4000L) {
            speak(message)
            lastSpeechMs = now
        }
    }

    private fun vibrate(level: WarningLevel) {
        val (pattern, amps) = when (level) {
            WarningLevel.DANGER  -> Pair(longArrayOf(0,100,50,100,50,200), intArrayOf(0,255,0,255,0,255))
            WarningLevel.WARNING -> Pair(longArrayOf(0,80,80,80),          intArrayOf(0,200,0,200))
            WarningLevel.CAUTION -> Pair(longArrayOf(0,50),                intArrayOf(0,140))
            WarningLevel.SAFE    -> return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, amps, -1))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) { Log.w(TAG, "Vibrate error: ${e.message}") }
    }

    private fun playTone(level: WarningLevel) {
        try {
            val (tone, duration) = when (level) {
                WarningLevel.DANGER  -> Pair(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
                WarningLevel.WARNING -> Pair(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE,  300)
                WarningLevel.CAUTION -> Pair(ToneGenerator.TONE_PROP_BEEP,             200)
                WarningLevel.SAFE    -> return
            }
            toneGen?.startTone(tone, duration)
        } catch (e: Exception) { Log.w(TAG, "Tone error: ${e.message}") }
    }

    private fun speak(text: String) {
        try {
            val bundle = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, "adas_alert")
        } catch (e: Exception) { Log.w(TAG, "TTS speak error: ${e.message}") }
    }

    fun release() {
        toneGen?.release()
        tts?.stop()
        tts?.shutdown()
    }
}
