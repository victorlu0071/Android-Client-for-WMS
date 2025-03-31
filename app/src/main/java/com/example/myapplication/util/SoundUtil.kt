package com.example.myapplication.util

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator

/**
 * Utility class for playing sound effects in the app
 */
object SoundUtil {
    private var soundPool: SoundPool? = null
    private var toneGenerator: ToneGenerator? = null
    
    /**
     * Initialize the sound pool and tone generator
     */
    fun initialize() {
        // Create tone generator for beeps
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Also initialize sound pool for future use
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
            
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(attributes)
            .build()
    }
    
    /**
     * Play a beep sound using the ToneGenerator
     */
    fun playScanBeep() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }
    
    /**
     * Release resources when no longer needed
     */
    fun release() {
        soundPool?.release()
        soundPool = null
        
        toneGenerator?.release()
        toneGenerator = null
    }
} 