package com.gbalink.online

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

/**
 * Conexión WebSocket con el servidor relay. Los mensajes son JSON de una línea.
 * Las devoluciones de llamada llegan en un hilo de OkHttp (no en el de la interfaz).
 */
class NetClient(url: String, private val listener: Listener) {

    interface Listener {
        fun onOpen()
        fun onMessage(msg: JSONObject)
        fun onClosed(reason: String)
    }

    private val closed = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .socketFactory(NoDelaySocketFactory())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val ws: WebSocket = client.newWebSocket(
        Request.Builder().url(url).build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                listener.onMessage(msg)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                notifyClosed("La conexión se cerró")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                notifyClosed(t.message ?: "Error de red")
            }
        },
    )

    private fun notifyClosed(reason: String) {
        if (closed.compareAndSet(false, true)) listener.onClosed(reason)
    }

    fun send(msg: JSONObject): Boolean = ws.send(msg.toString())

    fun close() {
        closed.set(true)
        ws.close(1000, "bye")
        client.dispatcher.executorService.shutdown()
    }
}

/**
 * Desactiva el algoritmo de Nagle: sin esto, los mensajes pequeños de botones pueden
 * quedarse esperando decenas de milisegundos antes de salir.
 */
private class NoDelaySocketFactory : SocketFactory() {
    private val base = getDefault()
    private fun Socket.noDelay() = apply { tcpNoDelay = true }

    override fun createSocket(): Socket = base.createSocket().noDelay()
    override fun createSocket(host: String?, port: Int): Socket = base.createSocket(host, port).noDelay()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        base.createSocket(host, port, localHost, localPort).noDelay()
    override fun createSocket(host: InetAddress?, port: Int): Socket = base.createSocket(host, port).noDelay()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        base.createSocket(address, port, localAddress, localPort).noDelay()
}
