package com.example.dualaudio.audio

import android.content.Context
import android.media.*
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class DualAudioEngine(private val context: Context) {
    private var audioTrack: AudioTrack? = null
    var decoderA: AudioDecoder? = null
    var decoderB: AudioDecoder? = null
    
    // Playback State
    private var isPlaying = AtomicBoolean(false)
    private var isPausedA = AtomicBoolean(false)
    private var isPausedB = AtomicBoolean(false)
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    /** Modes: 
     * 0 = SPLIT (A -> Left, B -> Right)
     * 1 = SAME (A -> Both)
     */
    var mode = Mode.SPLIT
    @Volatile var isSwapped = false
    @Volatile var volumeA: Float = 1.0f
    @Volatile var volumeB: Float = 1.0f
    // For SAME mode: Which source to use?
    enum class Source { A, B }
    var primarySource = Source.A

    enum class Mode { SPLIT, SAME }
    
    interface OnCompletionListener {
        fun onCompletion(source: Source)
    }
    
    var completionListener: OnCompletionListener? = null

    fun setSourceA(uri: Uri) {
        decoderA?.release()
        decoderA = AudioDecoder(context, uri)
    }

    fun setSourceB(uri: Uri) {
        decoderB?.release()
        decoderB = AudioDecoder(context, uri)
    }

    fun start() {
        if (isPlaying.get()) {
             // If resuming broadly, logic depends on context.
             // But start() is usually initial play.
             return
        }
        
        isPlaying.set(true)
        isPausedA.set(false)
        isPausedB.set(false)
        
        // Initialize AudioTrack
        val sampleRate = decoderA?.getSampleRate() ?: 44100
        Log.d("DualAudioEngine", "Initializing AudioTrack with Sample Rate: $sampleRate Hz")
        
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 4 // Increased buffer safety

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        
        audioTrack?.play()

        playbackJob = scope.launch {
            val bufferA = ShortArray(4096)
            val bufferB = ShortArray(4096)
            val mixedBuffer = ShortArray(8192) // Stereo = 2x size

            while (isPlaying.get()) {
                // If both are paused, we still loop but sleep to avoid CPU spinning
                if (isPausedA.get() && isPausedB.get()) {
                    delay(50)
                    continue
                }
                
                // Read A
                val readA = if (!isPausedA.get()) safeRead(decoderA, bufferA) else 0
                // Read B (Only in Split, or if needed)
                val readB = if (mode == Mode.SPLIT && !isPausedB.get()) safeRead(decoderB, bufferB) else 0
                
                if (readA == -1 && !isPausedA.get()) {
                    // Trigger completion A
                    // To prevent loop trigger, we can pause or mark done.
                    // For now, let's just trigger. The listener should handle logic (play next).
                    // We must fire this on main thread if possible, or handling code must use Main.
                    // But here we are in a coroutine.
                    // Let's invoke callback. 
                    // Crucial: Avoid continuous firing.
                     isPausedA.set(true) // Auto-pause to stop firing
                     scope.launch(Dispatchers.Main) { 
                         completionListener?.onCompletion(Source.A) 
                     }
                }
                
                if (mode == Mode.SPLIT && readB == -1 && !isPausedB.get()) {
                     isPausedB.set(true)
                     scope.launch(Dispatchers.Main) { 
                         completionListener?.onCompletion(Source.B) 
                     }
                }

                // If both are EOF or 0
                val maxRead = maxOf(readA, readB)
                if (maxRead <= 0) {
                     delay(50) 
                     continue
                }

                // Mixing
                // Stereo interleaving: L, R, L, R...
                for (i in 0 until maxRead) {
                    val rawA = if (i < readA) bufferA[i] else 0
                    val rawB = if (i < readB) bufferB[i] else 0
                    
                    val valA = (rawA * volumeA).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    val valB = (rawB * volumeB).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    
                    val sampleA = valA.toShort()
                    val sampleB = valB.toShort()

                    if (mode == Mode.SAME) {
                        // SAME Mode: Play primarySource on both channels.
                        val sample = if (primarySource == Source.A) sampleA else sampleB
                        mixedBuffer[2 * i] = sample
                        mixedBuffer[2 * i + 1] = sample
                    } else {
                        // SPLIT
                        if (!isSwapped) {
                             // A -> Left, B -> Right
                            mixedBuffer[2 * i] = sampleA
                            mixedBuffer[2 * i + 1] = sampleB
                        } else {
                            // A -> Right, B -> Left
                            mixedBuffer[2 * i] = sampleB
                            mixedBuffer[2 * i + 1] = sampleA
                        }
                    }
                }

                audioTrack?.write(mixedBuffer, 0, maxRead * 2)
            }
        }
    }

    private suspend fun safeRead(decoder: AudioDecoder?, buffer: ShortArray): Int {
        if (decoder == null) return 0
        var attempts = 0
        while (attempts < 5) {
            val read = decoder.readPCM(buffer)
            if (read > 0) return read // Got data
            if (read == -1) return -1 // EOF
            // read == 0, try again
            delay(2)
            attempts++
        }
        return 0
    }

    fun pauseA() { isPausedA.set(true) }
    fun pauseB() { isPausedB.set(true) }
    
    fun resumeA() { 
        isPausedA.set(false) 
        if (!isPlaying.get()) start()
    }
    
    fun resumeB() { 
        isPausedB.set(false) 
        if (!isPlaying.get()) start()
    }
    
    fun isPlayingA(): Boolean = !isPausedA.get()
    fun isPlayingB(): Boolean = !isPausedB.get()
    
    // Global pause/resume for simple logic compatibility if needed
    fun pause() {
        pauseA()
        pauseB()
    }
    
    fun isPlaying(): Boolean = isPlaying.get()
    
    fun resume() {
        resumeA()
        resumeB()
    }

    fun stop() {
        isPlaying.set(false)
        isPausedA.set(false)
        isPausedB.set(false)
        runBlocking { playbackJob?.join() }
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        decoderA?.release()
        decoderB?.release()
    }

    fun getProgressA(): Long = decoderA?.getSampleTime()?.div(1000) ?: 0
    fun getDurationA(): Long = decoderA?.getDuration() ?: 0
    
    fun getProgressB(): Long = decoderB?.getSampleTime()?.div(1000) ?: 0
    fun getDurationB(): Long = decoderB?.getDuration() ?: 0
    
    fun seekA(positionMs: Long) {
        decoderA?.seekTo(positionMs)
    }
    
    fun seekB(positionMs: Long) {
        decoderB?.seekTo(positionMs)
    }

    fun toggleSwap() {
        isSwapped = !isSwapped
    }
}
