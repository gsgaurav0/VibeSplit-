package com.example.dualaudio.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.dualaudio.R
import com.example.dualaudio.audio.DualAudioEngine
import com.example.dualaudio.ui.MainActivity
import com.example.dualaudio.data.Song

class AudioService : Service() {
    private val binder = LocalBinder()
    lateinit var audioEngine: DualAudioEngine
        private set

    inner class LocalBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }

    override fun onCreate() {
        super.onCreate()
        audioEngine = DualAudioEngine(this)
        startForeground(1, createNotification())
        registerReceiver(noisyReceiver, IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> audioEngine.pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> audioEngine.pause()
            AudioManager.AUDIOFOCUS_GAIN -> audioEngine.resume()
        }
    }

    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                audioEngine.pause()
            }
        }
    }

    fun play() {
        val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioEngine.start()
        }
    }

    fun isPlaying(): Boolean = audioEngine.isPlaying()
    fun isPlayingA(): Boolean = audioEngine.isPlayingA()
    fun isPlayingB(): Boolean = audioEngine.isPlayingB()

    fun pause() {
        audioEngine.pause()
    }
    
    fun pauseA() = audioEngine.pauseA()
    fun pauseB() = audioEngine.pauseB()
    fun resumeA() = audioEngine.resumeA()
    fun resumeB() = audioEngine.resumeB()
    
    fun toggleSwap() = audioEngine.toggleSwap()
    fun isSwapped(): Boolean = audioEngine.isSwapped
    
    fun setVolumeA(volume: Float) { audioEngine.volumeA = volume }
    fun setVolumeB(volume: Float) { audioEngine.volumeB = volume }

    fun getProgressA(): Long = audioEngine.getProgressA()
    fun getDurationA(): Long = audioEngine.getDurationA()
    fun getProgressB(): Long = audioEngine.getProgressB()
    fun getDurationB(): Long = audioEngine.getDurationB()
    
    fun setOnCompletionListener(listener: DualAudioEngine.OnCompletionListener) {
        audioEngine.completionListener = listener
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                audioEngine.stop()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    private fun createNotification(): Notification {
        return buildNotification("Dual Audio Ready", "Waiting for music...")
    }
    
    fun updateNotification(titleA: String, titleB: String, isPlayingA: Boolean, isPlayingB: Boolean, mode: DualAudioEngine.Mode) {
        val titleText: String
        val contentText: String
        
        if (mode == DualAudioEngine.Mode.SAME) {
            titleText = if (isPlayingA) "Playing Smoothely 🎧" else "Paused"
            contentText = "Track: $titleA"
        } else {
            // Split Mode
            titleText = "Split Mode Active ↔️"
            contentText = "Left: ${if(isPlayingA) titleA else "Paused"} | Right: ${if(isPlayingB) titleB else "Paused"}"
        }
        
        val notification = buildNotification(titleText, contentText)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val channelId = "dual_audio_channel"
        val channelName = "Dual Audio Playback"
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AudioService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Service", stopIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0))
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(noisyReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioEngine.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "STOP"
    }
}
