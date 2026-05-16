package com.catguard.network

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * WebSocket сервер на камері (порт 8765).
 * Протокол:
 *   Камера → глядач (text):   "STATUS:OBJECT_DETECTED" / "STATUS:OBJECT_LOST"
 *   Камера → глядач (binary): PCM аудіо 44100 Hz mono 16bit
 *   Глядач → камера (text):   "PING", "MIC_ON", "MIC_OFF", "TORCH_ON", "TORCH_OFF"
 */
class StreamServer(port: Int = 8765) : WebSocketServer(InetSocketAddress(port)) {

    var onClientCount: ((Int) -> Unit)? = null
    var onCommand: ((String) -> Unit)? = null  // зворотній зв'язок від глядача
    private val clients = mutableSetOf<WebSocket>()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        clients.add(conn)
        Log.i("StreamServer", "Підключився глядач: ${conn.remoteSocketAddress}")
        onClientCount?.invoke(clients.size)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        clients.remove(conn)
        Log.i("StreamServer", "Відключився глядач")
        onClientCount?.invoke(clients.size)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        Log.d("StreamServer", "Команда від глядача: $message")
        onCommand?.invoke(message)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e("StreamServer", "Помилка: ${ex.message}")
    }

    override fun onStart() {
        Log.i("StreamServer", "Сервер запущено на порту $port")
    }

    override fun broadcast(status: String) {
        val msg = "STATUS:$status"
        clients.toList().forEach { if (it.isOpen) try { it.send(msg) } catch (_: Exception) {} }
    }

    /** Передача аудіо PCM як binary frame */
    fun broadcastAudio(pcmBytes: ByteArray) {
        val buf = ByteBuffer.wrap(pcmBytes)
        clients.toList().forEach { if (it.isOpen) try { it.send(buf) } catch (_: Exception) {} }
    }

    fun clientCount() = clients.size
}
