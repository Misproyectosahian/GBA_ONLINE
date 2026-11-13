package com.gbalink.online

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

enum class Mode { SOLO, LOCAL2P, ONLINE }

/**
 * Datos compartidos del modo en línea ("lockstep" con retraso de entrada).
 *
 * Cada teléfono corre las dos GBA. En el cuadro F cada uno manda los botones que se
 * van a aplicar en el cuadro F + delay. Un cuadro solo se ejecuta cuando están los
 * botones de los dos jugadores para ese cuadro; así ambos teléfonos calculan lo mismo.
 */
class Lockstep(
    val delay: Int,
    val localPlayer: Int,
    private val sendInput: (frame: Int, keys: Int) -> Unit,
    private val sendHash: (frame: Int, hash: Int) -> Unit,
) {
    val remoteInputs = ConcurrentHashMap<Int, Int>()
    val remoteHashes = ConcurrentHashMap<Int, Int>()
    @Volatile var remoteMax = -1
        private set

    init {
        // Los primeros `delay` cuadros nadie presiona nada (todavía no hay botones del otro).
        for (f in 0 until delay) remoteInputs[f] = 0
        remoteMax = delay - 1
    }

    /** Llamado desde el hilo de red. */
    fun onRemoteInput(frame: Int, keys: Int) {
        remoteInputs[frame] = keys
        if (frame > remoteMax) remoteMax = frame
    }

    /** Llamado desde el hilo de red. */
    fun onRemoteHash(frame: Int, hash: Int) {
        remoteHashes[frame] = hash
    }

    fun send(frame: Int, keys: Int) = sendInput(frame, keys)
    fun sendHash(frame: Int, hash: Int) = sendHash.invoke(frame, hash)
}

/**
 * Hilo del emulador: ejecuta los cuadros a 59,73 por segundo, dibuja, reproduce el
 * audio y guarda la partida cuando cambia.
 */
class GameLoop(
    private val romPath: String,
    private val mode: Mode,
    private val saves: Array<ByteArray?>,
    private val localPlayer: Int,
    private val rtcEpochMs: Long,
    private val lockstep: Lockstep?,
    private val listener: Listener,
) : Thread("emulador") {

    interface Listener {
        fun onStarted(title: String)
        fun onError(message: String)
        fun onWaiting(waiting: Boolean)
        fun onDesync(frame: Int)
        /** Partida del jugador local en memoria; hay que escribirla a disco. */
        fun onSaveChanged(data: ByteArray)
        fun onMessage(message: String)
        fun loadQuickState(): ByteArray?
        fun storeQuickState(data: ByteArray)
    }

    private enum class Command { SAVE_STATE, LOAD_STATE }

    val touchKeys = AtomicInteger(0)
    val padKeys = AtomicInteger(0)

    /** Pantalla que se ve y jugador que se controla (en la prueba local se puede cambiar). */
    @Volatile var viewPlayer = localPlayer
    @Volatile var paused = false
    @Volatile var muted = false
    @Volatile private var running = true

    private val commands = ConcurrentLinkedQueue<Command>()
    private var handle = 0L
    private val audioBuffer = ShortArray(8192)

    // Estado del modo en línea (solo lo toca este hilo).
    private var frame = 0
    private var localSent = -1
    private val localInputs = IntArray(256)
    private val localHashes = HashMap<Int, Int>()
    private var stallStartNs = 0L
    private var waitingShown = false
    private var desyncReported = false

    // Guardado.
    private val savePlayer = if (mode == Mode.ONLINE) localPlayer else 0
    private var lastSaveCrc = Storage.crc32(saves.getOrNull(savePlayer))
    private var framesSinceSaveCheck = 0

    fun requestSaveState() {
        commands.add(Command.SAVE_STATE)
        LockSupport.unpark(this)
    }

    fun requestLoadState() {
        commands.add(Command.LOAD_STATE)
        LockSupport.unpark(this)
    }

    fun shutdown() {
        running = false
        LockSupport.unpark(this)
        join(3000)
    }

    override fun run() {
        val players = if (mode == Mode.SOLO) 1 else 2
        val error = arrayOfNulls<String>(1)
        handle = NativeBridge.create(
            romPath, players, saves.getOrNull(0), saves.getOrNull(1), rtcEpochMs, SAMPLE_RATE, error,
        )
        if (handle == 0L) {
            listener.onError(error[0] ?: "No se pudo iniciar el emulador")
            return
        }
        val audio = AudioOut(SAMPLE_RATE)
        var shownPlayer = viewPlayer
        NativeBridge.setAudioPlayer(handle, shownPlayer)
        if (lockstep != null) localSent = lockstep.delay - 1
        listener.onStarted(NativeBridge.gameTitle(handle))

        var next = System.nanoTime()
        try {
            while (running) {
                processCommands()
                if (paused) {
                    audio.pause()
                    persistSave()
                    while (paused && running) {
                        LockSupport.parkNanos(20_000_000L)
                        processCommands()
                    }
                    audio.resume()
                    next = System.nanoTime()
                    continue
                }
                if (viewPlayer != shownPlayer) {
                    shownPlayer = viewPlayer
                    NativeBridge.setAudioPlayer(handle, shownPlayer)
                }

                val ran = if (mode == Mode.ONLINE) stepOnline() else stepLocal()
                if (ran > 0) {
                    NativeBridge.present(handle, shownPlayer)
                    val n = NativeBridge.readAudio(handle, audioBuffer)
                    if (!muted) audio.write(audioBuffer, n)
                    framesSinceSaveCheck += ran
                    if (framesSinceSaveCheck >= SAVE_CHECK_FRAMES) {
                        framesSinceSaveCheck = 0
                        persistSave()
                    }
                }

                next += FRAME_NS
                val wait = next - System.nanoTime()
                if (wait > 0) {
                    LockSupport.parkNanos(wait)
                } else if (-wait > FRAME_NS * 6) {
                    next = System.nanoTime() // íbamos muy atrasados: no intentar recuperar todo
                }
            }
        } catch (t: Throwable) {
            listener.onError("Error en el emulador: ${t.message}")
        } finally {
            persistSave()
            audio.release()
            NativeBridge.destroy(handle)
            handle = 0L
        }
    }

    private fun currentKeys() = touchKeys.get() or padKeys.get()

    private fun stepLocal(): Int {
        val keys = currentKeys()
        if (mode == Mode.LOCAL2P && viewPlayer == 1) {
            NativeBridge.runFrame(handle, 0, keys)
        } else {
            NativeBridge.runFrame(handle, keys, 0)
        }
        return 1
    }

    private fun stepOnline(): Int {
        val ls = lockstep ?: return 0
        var ran = 0
        for (attempt in 0 until 2) {
            // Segundo cuadro en el mismo tic solo si la otra persona va adelantada (ponerse al día).
            if (attempt == 1 && ls.remoteMax - frame <= ls.delay) break

            while (localSent < frame + ls.delay) {
                localSent++
                val keys = currentKeys()
                localInputs[localSent and 255] = keys
                ls.send(localSent, keys)
            }
            val remote = ls.remoteInputs.remove(frame)
            if (remote == null) {
                if (ran == 0) markStalled()
                break
            }
            val local = localInputs[frame and 255]
            if (ls.localPlayer == 0) {
                NativeBridge.runFrame(handle, local, remote)
            } else {
                NativeBridge.runFrame(handle, remote, local)
            }
            frame++
            ran++

            if (frame % HASH_INTERVAL == 0) {
                val hash = NativeBridge.stateHash(handle)
                localHashes[frame] = hash
                ls.sendHash(frame, hash)
            }
            checkHashes(ls)
        }
        if (ran > 0) markRunning()
        return ran
    }

    private fun markStalled() {
        val now = System.nanoTime()
        if (stallStartNs == 0L) stallStartNs = now
        if (!waitingShown && now - stallStartNs > 300_000_000L) {
            waitingShown = true
            listener.onWaiting(true)
        }
    }

    private fun markRunning() {
        stallStartNs = 0L
        if (waitingShown) {
            waitingShown = false
            listener.onWaiting(false)
        }
    }

    private fun checkHashes(ls: Lockstep) {
        if (ls.remoteHashes.isEmpty()) return
        val iterator = ls.remoteHashes.entries.iterator()
        while (iterator.hasNext()) {
            val (f, remoteHash) = iterator.next()
            val mine = localHashes[f] ?: continue
            iterator.remove()
            localHashes.remove(f)
            if (mine != remoteHash && !desyncReported) {
                desyncReported = true
                listener.onDesync(f)
            }
        }
        if (localHashes.size > 64) {
            val oldest = localHashes.keys.sorted().take(localHashes.size - 64)
            oldest.forEach { localHashes.remove(it) }
        }
    }

    private fun persistSave() {
        if (handle == 0L) return
        val data = NativeBridge.cloneSave(handle, savePlayer) ?: return
        val crc = Storage.crc32(data)
        if (crc != lastSaveCrc) {
            lastSaveCrc = crc
            listener.onSaveChanged(data)
        }
    }

    private fun processCommands() {
        while (true) {
            val cmd = commands.poll() ?: return
            when (cmd) {
                Command.SAVE_STATE -> {
                    val state = if (mode == Mode.SOLO) NativeBridge.saveState(handle) else null
                    if (state != null) {
                        listener.storeQuickState(state)
                        listener.onMessage("Estado guardado")
                    } else {
                        listener.onMessage("No se pudo guardar el estado")
                    }
                }
                Command.LOAD_STATE -> {
                    val state = if (mode == Mode.SOLO) listener.loadQuickState() else null
                    if (state != null && NativeBridge.loadState(handle, state)) {
                        listener.onMessage("Estado cargado")
                    } else {
                        listener.onMessage("No hay un estado guardado para cargar")
                    }
                }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 48000
        /** La GBA dibuja 59,7275 cuadros por segundo. */
        const val FRAME_NS = 16_742_706L
        const val HASH_INTERVAL = 60
        const val SAVE_CHECK_FRAMES = 120
    }
}
