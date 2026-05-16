package com.catguard.network

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class StreamClient(
    serverUri: URI,
    val cameraId: String,
    private val onStatus:     (cameraId: String, status: String) -> Unit,
    private val onAudio:      (pcm: ByteArray) -> Unit = {},
    private val onConnect:    (cameraId: String) -> Unit,
    private val onDisconnect: (cameraId: String) -> Unit
) : WebSocketClient(serverUri) {

    private val disconnected = AtomicBoolean(false)

    override fun onOpen(handshake: ServerHandshake) {
        disconnected.set(false)
        Log.i("StreamClient", "Підключено до $cameraId")
        onConnect(cameraId)
    }

    override fun onMessage(message: String) {
        if (message.startsWith("STATUS:")) {
            val status = message.removePrefix("STATUS:")
            Log.d("StreamClient", "$cameraId → $status")
            onStatus(cameraId, status)
        }
    }

    /** Отримання аудіо (binary frame) */
    override fun onMessage(bytes: ByteBuffer) {
        val arr = ByteArray(bytes.remaining())
        bytes.get(arr)
        onAudio(arr)
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        Log.w("StreamClient", "$cameraId закрито (code=$code)")
        if (disconnected.compareAndSet(false, true)) {
            onDisconnect(cameraId)
        }
    }

    override fun onError(ex: Exception) {
        Log.e("StreamClient", "$cameraId помилка: ${ex.message}")
    }

    /** Надіслати команду на камеру */
    fun sendCommand(command: String) {
        if (isOpen) send(command)
    }
}
