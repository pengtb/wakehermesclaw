package com.openclaw.assistant.speech.asr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/** 录音 / 响度 / WAV 的公共小工具，批式与流式两个后端共用。 */
object AudioUtils {
    const val SAMPLE_RATE = 16000

    /** 16k / 单声道 / PCM16 的 AudioRecord；拿不到返回 null */
    fun openRecorder(): AudioRecord? {
        return try {
            val min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val size = if (min > 0) min else SAMPLE_RATE
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                size * 4
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) rec else { rec.release(); null }
        } catch (e: Exception) {
            null
        }
    }

    /** 一段 PCM16 的响度（dBFS：满量程 0，静音 -100） */
    fun rmsDb(buf: ByteArray, len: Int): Double {
        val n = len - (len % 2)
        if (n < 2) return -100.0
        var sum = 0.0
        var i = 0
        while (i < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s.toDouble()
            i += 2
        }
        val rms = sqrt(sum / (n / 2))
        return if (rms <= 1.0) -100.0 else 20 * log10(rms / 32768.0)
    }

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    private fun le16(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte()
    )

    /** PCM16 裸数据加 44 字节 WAV 头写文件（16k/mono/16bit） */
    fun writeWav(pcm: ByteArray, out: File) {
        val bytesPerSecond = SAMPLE_RATE * 2
        val bos = ByteArrayOutputStream(pcm.size + 44)
        bos.write("RIFF".toByteArray(Charsets.US_ASCII))
        bos.write(le32(36 + pcm.size))
        bos.write("WAVE".toByteArray(Charsets.US_ASCII))
        bos.write("fmt ".toByteArray(Charsets.US_ASCII))
        bos.write(le32(16))
        bos.write(le16(1))                 // PCM
        bos.write(le16(1))                 // mono
        bos.write(le32(SAMPLE_RATE))
        bos.write(le32(bytesPerSecond))
        bos.write(le16(2))                 // block align
        bos.write(le16(16))                // bits per sample
        bos.write("data".toByteArray(Charsets.US_ASCII))
        bos.write(le32(pcm.size))
        bos.write(pcm)
        out.writeBytes(bos.toByteArray())
    }
}
