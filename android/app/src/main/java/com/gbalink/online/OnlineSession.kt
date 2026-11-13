package com.gbalink.online

import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Sala en línea: conexión, intercambio de datos antes de jugar y mensajes durante el juego.
 *
 * Protocolo (JSON por WebSocket; el servidor solo reenvía lo que no es de control):
 *   cliente → servidor   {"t":"create","name":..}   {"t":"join","code":"1234","name":..}
 *   servidor → cliente   {"t":"created","code":..}  {"t":"joined","peer":..}  {"t":"peer_joined","peer":..}
 *                        {"t":"peer_left"}          {"t":"error","msg":..}
 *   entre jugadores      {"t":"hello","v":1,"rom":"CRC","save":"base64"}
 *                        {"t":"ping","id":n,"ts":ms} / {"t":"pong","id":n,"ts":ms}
 *                        {"t":"start","delay":D,"epoch":ms}         (solo el anfitrión)
 *                        {"t":"in","f":cuadro,"k":botones}           (60 por segundo)
 *                        {"t":"hash","f":cuadro,"h":huella}          (1 por segundo)
 *                        {"t":"chat","m":texto}   {"t":"bye"}
 */
class OnlineSession(
    private val isHost: Boolean,
    private val joinCode: String?,
    private val myName: String,
    private val romCrc: String,
    private val mySave: ByteArray?,
    private val forcedDelay: Int,
    serverUrl: String,
    private val ui: Ui,
) : NetClient.Listener {

    interface Ui {
        fun onRoomCreated(code: String)
        fun onLobbyStatus(text: String)
        fun onPeerJoined(name: String)
        fun onStart(start: StartInfo)
        fun onChat(from: String, text: String)
        fun onPing(ms: Int)
        fun onPeerLeft(reason: String)
        fun onFatal(message: String)
    }

    class StartInfo(
        val lockstep: Lockstep,
        val saves: Array<ByteArray?>,
        val localPlayer: Int,
        val epochMs: Long,
        val peerName: String,
    )

    private val main = Handler(Looper.getMainLooper())
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile var peerName = "Tu pareja"
        private set
    @Volatile private var started = false
    @Volatile private var closed = false
    private var sentHello = false
    private var peerSave: ByteArray? = null
    private var gotPeerHello = false
    private val handshakeRtts = mutableListOf<Long>()
    @Volatile private var lockstep: Lockstep? = null

    // Se crea al final para que todos los campos estén listos antes de que llegue algún mensaje.
    private val net = NetClient(serverUrl, this)

    // ---------- Enviar ----------

    private fun send(vararg pairs: Pair<String, Any?>) {
        val obj = JSONObject()
        for ((k, v) in pairs) obj.put(k, v)
        net.send(obj)
    }

    fun sendChat(text: String) = send("t" to "chat", "m" to text)

    fun close() {
        if (closed) return
        closed = true
        runCatching { send("t" to "bye") }
        timer.shutdownNow()
        net.close()
    }

    // ---------- Recibir (hilo de red) ----------

    override fun onOpen() {
        if (isHost) {
            send("t" to "create", "name" to myName, "v" to PROTOCOL)
        } else {
            send("t" to "join", "code" to joinCode, "name" to myName, "v" to PROTOCOL)
        }
        main.post { ui.onLobbyStatus(if (isHost) "Creando sala…" else "Entrando a la sala…") }
    }

    override fun onClosed(reason: String) {
        if (closed) return
        closed = true
        timer.shutdownNow()
        main.post {
            if (started) {
                ui.onPeerLeft("Se perdió la conexión con el servidor ($reason).")
            } else {
                ui.onFatal("No se pudo conectar con el servidor.\n\n$reason\n\nRevisá la dirección del servidor en la pantalla principal.")
            }
        }
    }

    override fun onMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "in" -> lockstep?.onRemoteInput(msg.getInt("f"), msg.getInt("k"))
            "hash" -> lockstep?.onRemoteHash(msg.getInt("f"), msg.getInt("h"))
            "ping" -> send("t" to "pong", "id" to msg.optInt("id"), "ts" to msg.optLong("ts"))
            "pong" -> onPong(msg)
            "chat" -> {
                val text = msg.optString("m").take(500)
                if (text.isNotBlank()) main.post { ui.onChat(peerName, text) }
            }
            "created" -> {
                val code = msg.optString("code")
                main.post { ui.onRoomCreated(code) }
            }
            "joined", "peer_joined" -> {
                peerName = msg.optString("peer").ifBlank { "Tu pareja" }.take(24)
                val name = peerName
                main.post { ui.onPeerJoined(name) }
                sendHello()
            }
            "hello" -> onHello(msg)
            "start" -> if (!isHost && !started) {
                begin(msg.getInt("delay"), msg.getLong("epoch"))
            }
            "peer_left", "bye" -> if (!closed) {
                closed = true
                timer.shutdownNow()
                net.close()
                main.post { ui.onPeerLeft("$peerName salió de la partida.") }
            }
            "error" -> {
                val text = msg.optString("msg", "Error del servidor")
                closed = true
                timer.shutdownNow()
                net.close()
                main.post { ui.onFatal(text) }
            }
        }
    }

    private fun sendHello() {
        if (sentHello) return
        sentHello = true
        val save = mySave?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: ""
        send("t" to "hello", "v" to PROTOCOL, "rom" to romCrc, "save" to save, "name" to myName)
        main.post { ui.onLobbyStatus("Intercambiando partidas guardadas…") }
        maybeStartHandshake()
    }

    private fun onHello(msg: JSONObject) {
        if (msg.optInt("v") != PROTOCOL) {
            fatal("Tu pareja tiene otra versión de la app. Instalen los dos el mismo APK.")
            return
        }
        if (!msg.optString("rom").equals(romCrc, ignoreCase = true)) {
            fatal("Están usando ROMs diferentes. Tienen que tener exactamente el mismo archivo .gba (el mismo juego, región y versión).")
            return
        }
        val save = msg.optString("save")
        peerSave = if (save.isEmpty()) null else runCatching { Base64.decode(save, Base64.DEFAULT) }.getOrNull()
        gotPeerHello = true
        maybeStartHandshake()
    }

    private fun fatal(text: String) {
        closed = true
        timer.shutdownNow()
        runCatching { send("t" to "bye") }
        net.close()
        main.post { ui.onFatal(text) }
    }

    /** El anfitrión mide el ping (5 veces) para elegir el retraso de entrada y da la largada. */
    @Synchronized
    private fun maybeStartHandshake() {
        if (!isHost || !sentHello || !gotPeerHello || started) return
        main.post { ui.onLobbyStatus("Midiendo la conexión…") }
        for (i in 0 until HANDSHAKE_PINGS) {
            timer.schedule({ send("t" to "ping", "id" to -1 - i, "ts" to System.currentTimeMillis()) }, i * 120L, TimeUnit.MILLISECONDS)
        }
        // Si algún pong no vuelve, arrancar igual con lo que haya.
        timer.schedule({ hostStart() }, 3000, TimeUnit.MILLISECONDS)
    }

    private fun onPong(msg: JSONObject) {
        val rtt = System.currentTimeMillis() - msg.optLong("ts")
        if (msg.optInt("id") < 0 && isHost && !started) {
            synchronized(handshakeRtts) {
                handshakeRtts.add(rtt)
                if (handshakeRtts.size >= HANDSHAKE_PINGS) timer.execute { hostStart() }
            }
        } else {
            main.post { ui.onPing(rtt.toInt()) }
        }
    }

    @Synchronized
    private fun hostStart() {
        if (started || closed) return
        val rtts = synchronized(handshakeRtts) { handshakeRtts.sorted() }
        val median = if (rtts.isEmpty()) 150L else rtts[rtts.size / 2]
        val delay = if (forcedDelay > 0) {
            forcedDelay
        } else {
            // Un viaje de ida (medio ping) en cuadros, más uno de margen.
            (ceil(median / 2.0 / FRAME_MS).toInt() + 1).coerceIn(2, 12)
        }
        val epoch = System.currentTimeMillis()
        // Primero se crea el lockstep (por si llegan botones) y después se avisa al invitado.
        begin(delay, epoch, announce = true)
        main.post { ui.onPing(median.toInt()) }
    }

    @Synchronized
    private fun begin(delay: Int, epochMs: Long, announce: Boolean = false) {
        if (started) return
        started = true
        val localPlayer = if (isHost) 0 else 1
        val ls = Lockstep(
            delay = delay,
            localPlayer = localPlayer,
            sendInput = { f, k -> send("t" to "in", "f" to f, "k" to k) },
            sendHash = { f, h -> send("t" to "hash", "f" to f, "h" to h) },
        )
        lockstep = ls
        if (announce) send("t" to "start", "delay" to delay, "epoch" to epochMs)

        val hostSave = if (isHost) mySave else peerSave
        val guestSave = if (isHost) peerSave else mySave
        val info = StartInfo(ls, arrayOf(hostSave, guestSave), localPlayer, epochMs, peerName)
        main.post { ui.onStart(info) }

        // Ping periódico para mostrarlo en pantalla.
        timer.scheduleWithFixedDelay(
            { if (!closed) send("t" to "ping", "id" to 1, "ts" to System.currentTimeMillis()) },
            2, 3, TimeUnit.SECONDS,
        )
    }

    companion object {
        const val PROTOCOL = 1
        private const val HANDSHAKE_PINGS = 5
        private const val FRAME_MS = 1000.0 / 59.7275
    }
}
