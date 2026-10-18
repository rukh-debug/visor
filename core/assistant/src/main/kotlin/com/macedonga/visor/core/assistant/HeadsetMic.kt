package com.macedonga.visor.core.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ms] of the glasses' microphone as a Bluetooth headset (SCO or LE audio), 16 kHz 16-bit mono PCM; null when there is
 * no such route or it stays silent.
 */
@SuppressLint("MissingPermission")
internal suspend fun headsetTake(context: Context, ms: Long, say: (String) -> Unit): ByteArray? = withContext(Dispatchers.IO) {
    val am = context.getSystemService(AudioManager::class.java)
    val glasses = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
    if (glasses == null) {
        say("Custom wake word: no Bluetooth headset route to the glasses (${am.availableCommunicationDevices.joinToString { "${it.type}:${it.productName}" }})")
        return@withContext null
    }
    val mode = am.mode
    am.mode = AudioManager.MODE_IN_COMMUNICATION
    if (!am.setCommunicationDevice(glasses)) say("Custom wake word: could not route the call mic to ${glasses.productName}")
    try {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2))
        }.getOrElse { say("Custom wake word: no headset recorder: ${it.message}"); return@withContext null }
        try {
            rec.setPreferredDevice(glasses)
            rec.startRecording()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(3_200)
            // The headset link takes a second or two to come up and reads exact zeros until then: time the take from
            // the first sound, or give up.
            val opened = System.currentTimeMillis()
            var waited = -1L
            while (System.currentTimeMillis() - opened < LIVE_WAIT_MS) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if ((0 until n).any { buf[it].toInt() != 0 }) {
                    waited = System.currentTimeMillis() - opened
                    out.write(buf, 0, n)
                    break
                }
            }
            if (waited < 0) {
                say("Custom wake word: headset from ${glasses.productName} stayed silent for $LIVE_WAIT_MS ms")
                return@withContext null
            }
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) out.write(buf, 0, n) else if (n < 0) break
            }
            val pcm = out.toByteArray()
            var peak = 0
            for (k in 0 until pcm.size / 2) peak = maxOf(peak, kotlin.math.abs((pcm[k * 2].toInt() and 0xFF) or (pcm[k * 2 + 1].toInt() shl 8)))
            say("Custom wake word: headset take from ${glasses.productName} (type ${glasses.type}, routed to ${rec.routedDevice?.productName}): ${pcm.size / 32} ms, peak $peak, live after $waited ms")
            pcm
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    } finally {
        am.clearCommunicationDevice()
        am.mode = mode
    }
}

private const val RATE = 16_000
private const val LIVE_WAIT_MS = 4_000L
