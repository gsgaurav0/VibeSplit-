package com.example.dualaudio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioDecoder(val context: Context, val uri: Uri) {
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var sampleRate = 44100
    private var channels = 1
    private var durationUs: Long = 0
    private var isEOS = false

    init {
        initCodec()
    }

    private fun initCodec() {
        try {
            extractor = MediaExtractor()
            extractor?.setDataSource(context, uri, null)
            
            var format: MediaFormat? = null
            var mime = "audio/mp4a-latm" // default fallback
            
            for (i in 0 until (extractor?.trackCount ?: 0)) {
                val trackFormat = extractor?.getTrackFormat(i)
                val trackMime = trackFormat?.getString(MediaFormat.KEY_MIME)
                if (trackMime?.startsWith("audio/") == true) {
                    extractor?.selectTrack(i)
                    format = trackFormat
                    mime = trackMime
                    break
                }
            }
            
            if (format == null) {
                Log.e("AudioDecoder", "No audio track found")
                return
            }

            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                durationUs = format.getLong(MediaFormat.KEY_DURATION)
            }
            codec = MediaCodec.createDecoderByType(mime)
            codec?.configure(format, null, null, 0)
            codec?.start()
        } catch (e: Exception) {
            Log.e("AudioDecoder", "Error initializing codec", e)
        }
    }

    fun getSampleRate(): Int = sampleRate

    // Returns a chunk of PCM data (16-bit PCM)
    // This is a simplified synchronous implementation. 
    // In production this should handle buffer info more robustly.
    fun readPCM(buffer: ShortArray): Int {
        if (codec == null || isEOS) return -1

        val info = MediaCodec.BufferInfo()
        val timeoutUs = 5000L
        
        // Feed input
        try {
            val inputIndex = codec?.dequeueInputBuffer(timeoutUs) ?: -1
            if (inputIndex >= 0) {
                val inputBuffer = codec?.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    val sampleSize = extractor?.readSampleData(inputBuffer, 0) ?: -1
                    if (sampleSize < 0) {
                        codec?.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        codec?.queueInputBuffer(inputIndex, 0, sampleSize, extractor?.sampleTime ?: 0, 0)
                        extractor?.advance()
                    }
                }
            }

            // Read output
            val outputIndex = codec?.dequeueOutputBuffer(info, timeoutUs) ?: -1
            if (outputIndex >= 0) {
                val outputBuffer = codec?.getOutputBuffer(outputIndex)
                var samplesRead = 0
                if (outputBuffer != null) {
                    // Convert ByteBuffer to ShortArray (PCM 16-bit)
                    // Note: Default decoder output is usually PCM 16-bit
                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)
                    outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    

                    
                    val shortBuffer = outputBuffer.asShortBuffer()
                    
                    if (channels == 2) {
                        // Stereo -> Mono Downmix
                        // 2 Input Shorts (L,R) -> 1 Output Short (Mixed)
                        val framesAvailable = shortBuffer.remaining() / 2
                        val framesToWrite = minOf(framesAvailable, buffer.size)
                        
                        for (i in 0 until framesToWrite) {
                            val l = shortBuffer.get().toInt()
                            val r = shortBuffer.get().toInt()
                            buffer[i] = ((l + r) / 2).toShort()
                        }
                        samplesRead = framesToWrite
                    } else {
                        // Mono -> Direct Copy
                        val count = minOf(shortBuffer.remaining(), buffer.size)
                        shortBuffer.get(buffer, 0, count)
                        samplesRead = count
                    }
                }
                codec?.releaseOutputBuffer(outputIndex, false)
                return samplesRead
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                 val newFormat = codec?.outputFormat
                 newFormat?.let {
                     if (it.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                     }
                     if (it.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                         channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                     }
                 }
                 return 0
            }
        } catch (e: Exception) {
            Log.e("AudioDecoder", "Error reading PCM", e)
            return -1
        }
        
        return 0 // Try again
    }



    fun getSampleTime(): Long {
        return extractor?.sampleTime ?: 0
    }

    fun getDuration(): Long {
        return durationUs / 1000 // Convert to ms
    }

    fun seekTo(positionMs: Long) {
        val positionUs = positionMs * 1000
        extractor?.seekTo(positionUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        codec?.flush()
    }

    fun release() {
        try {
            codec?.stop()
            codec?.release()
            extractor?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
