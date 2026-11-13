package com.gbalink.online

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.gbalink.online.Storage.controlsOpacity
import com.gbalink.online.Storage.inputDelay
import com.gbalink.online.Storage.playerName
import com.gbalink.online.Storage.screenOrientation
import com.gbalink.online.Storage.serverUrl

class GameActivity : Activity(), GameLoop.Listener, OnlineSession.Ui {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_CODE = "code"
        const val MODE_SOLO = "solo"
        const val MODE_LOCAL2P = "local2p"
        const val MODE_HOST = "host"
        const val MODE_JOIN = "join"
        private const val MAX_CHAT_LINES = 200
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var modeExtra: String
    private var loop: GameLoop? = null
    private var online: OnlineSession? = null

    private lateinit var screenFrame: View
    private lateinit var surface: SurfaceView
    private lateinit var controls: ControlsView
    private lateinit var statusText: TextView
    private lateinit var waitingText: TextView
    private lateinit var chatPanel: View
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatInput: EditText
    private lateinit var chatBadge: TextView
    private lateinit var chatToast: TextView
    private lateinit var chatTitle: TextView
    private lateinit var lobbyPanel: View
    private lateinit var lobbyTitle: TextView
    private lateinit var lobbyCode: TextView
    private lateinit var lobbyStatus: TextView
    private lateinit var lobbyShare: TextView
    private lateinit var editBar: View
    private lateinit var editHint: TextView
    private lateinit var gameRoot: GameLayout

    private var unread = 0
    private var gameTitle = ""
    private var peerName = "Tu pareja"
    private var localPlayer = 0
    private var delay = 0
    private var pingMs = -1
    private var desynced = false
    private var finishing = false
    private var roomCode = ""
    private val hideToast = Runnable { chatToast.visibility = View.GONE }
    private var pausedForEdit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyOrientation()
        setContentView(R.layout.activity_game)
        modeExtra = intent.getStringExtra(EXTRA_MODE) ?: MODE_SOLO

        screenFrame = findViewById(R.id.screenFrame)
        surface = findViewById(R.id.gameSurface)
        controls = findViewById(R.id.controls)
        statusText = findViewById(R.id.statusText)
        waitingText = findViewById(R.id.waitingText)
        chatPanel = findViewById(R.id.chatPanel)
        chatList = findViewById(R.id.chatList)
        chatScroll = findViewById(R.id.chatScroll)
        chatInput = findViewById(R.id.chatInput)
        chatBadge = findViewById(R.id.chatBadge)
        chatToast = findViewById(R.id.chatToast)
        chatTitle = findViewById(R.id.chatTitle)
        lobbyPanel = findViewById(R.id.lobbyPanel)
        lobbyTitle = findViewById(R.id.lobbyTitle)
        lobbyCode = findViewById(R.id.lobbyCode)
        lobbyStatus = findViewById(R.id.lobbyStatus)
        lobbyShare = findViewById(R.id.lobbyShare)
        editBar = findViewById(R.id.editBar)
        editHint = findViewById(R.id.editHint)
        gameRoot = findViewById(R.id.gameRoot)

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Ampliación entera (píxeles nítidos); el compositor hace el ajuste final.
                val scale = Math.round(width / 240f).coerceIn(1, 8)
                NativeBridge.setSurface(holder.surface, scale)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                NativeBridge.setSurface(null, 1)
            }
        })

        controls.onKeysChanged = { keys -> loop?.touchKeys?.set(keys) }
        controls.store = object : ControlsView.Store {
            override fun loadLayout(key: String) = Storage.controlsLayout(this@GameActivity, key)
            override fun saveLayout(key: String, json: String?) = Storage.setControlsLayout(this@GameActivity, key, json)
            override var opacity: Float
                get() = controlsOpacity
                set(value) { controlsOpacity = value }
        }
        setupEditBar()
        setupChat()
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.lobbyCancel).setOnClickListener { leave() }
        lobbyShare.setOnClickListener { shareCode() }

        if (!Storage.hasRom(this)) {
            fatalDialog("Primero elegí la ROM del juego en la pantalla principal.")
            return
        }
        when (modeExtra) {
            MODE_SOLO -> startLocal(Mode.SOLO)
            MODE_LOCAL2P -> startLocal(Mode.LOCAL2P)
            MODE_HOST, MODE_JOIN -> startOnline(modeExtra == MODE_HOST)
        }
        updateStatus()
    }

    // ---------- Inicio de los modos ----------

    private fun startLocal(mode: Mode) {
        val save = Storage.readSave(this)
        val saves: Array<ByteArray?> = if (mode == Mode.SOLO) arrayOf(save) else arrayOf(save, save?.copyOf())
        chatTitle.text = "Chat (sin conexión: solo se ve en este teléfono)"
        addSystemLine(if (mode == Mode.SOLO) "Modo un jugador. El chat funciona cuando juegan en línea." else "Prueba de dos jugadores en este teléfono. Usá ☰ para cambiar entre la GBA 1 y la 2.")
        launchLoop(mode, saves, 0, 0L, null)
    }

    private fun startOnline(host: Boolean) {
        val url = serverUrl
        if (!(url.startsWith("ws://") || url.startsWith("wss://"))) {
            fatalDialog("Configurá la dirección del servidor en la pantalla principal (empieza con ws:// o wss://).")
            return
        }
        val code = intent.getStringExtra(EXTRA_CODE)
        lobbyPanel.visibility = View.VISIBLE
        controls.visibility = View.GONE
        lobbyTitle.text = if (host) "Nueva sala" else "Uniéndote a la sala $code"
        lobbyStatus.text = "Conectando con el servidor…\nSi estaba dormido puede tardar hasta un minuto en despertar."
        chatTitle.text = "Chat"
        Storage.backupSave(this)
        try {
            online = OnlineSession(
                isHost = host,
                joinCode = code,
                myName = playerName.ifBlank { if (host) "Jugador 1" else "Jugador 2" },
                romCrc = Storage.romCrc(this) ?: "",
                mySave = Storage.readSave(this),
                forcedDelay = inputDelay,
                serverUrl = url,
                ui = this,
            )
        } catch (e: IllegalArgumentException) {
            fatalDialog("La dirección del servidor no es válida: ${e.message}")
        }
    }

    private fun launchLoop(mode: Mode, saves: Array<ByteArray?>, player: Int, epoch: Long, lockstep: Lockstep?) {
        val newLoop = GameLoop(
            romPath = Storage.romFile(this).absolutePath,
            mode = mode,
            saves = saves,
            localPlayer = player,
            rtcEpochMs = epoch,
            lockstep = lockstep,
            listener = this,
        )
        loop = newLoop
        newLoop.start()
    }

    // ---------- OnlineSession.Ui (hilo principal) ----------

    override fun onRoomCreated(code: String) {
        roomCode = code
        lobbyTitle.text = "Código de la sala"
        lobbyCode.text = code
        lobbyCode.visibility = View.VISIBLE
        lobbyShare.visibility = View.VISIBLE
        lobbyStatus.text = "Pasale este código a tu pareja.\nEsperando a que se una…"
    }

    override fun onLobbyStatus(text: String) {
        if (lobbyPanel.visibility == View.VISIBLE) lobbyStatus.text = text
    }

    override fun onPeerJoined(name: String) {
        peerName = name
        chatTitle.text = "Chat con $name"
        lobbyStatus.text = "¡$name se unió! Preparando la partida…"
        addSystemLine("$name se unió a la sala.")
    }

    override fun onStart(start: OnlineSession.StartInfo) {
        if (finishing) return
        localPlayer = start.localPlayer
        delay = start.lockstep.delay
        peerName = start.peerName
        lobbyPanel.visibility = View.GONE
        if (chatPanel.visibility != View.VISIBLE) controls.visibility = View.VISIBLE
        addSystemLine("¡A jugar! Vos sos el jugador ${localPlayer + 1}.")
        launchLoop(Mode.ONLINE, start.saves, start.localPlayer, start.epochMs, start.lockstep)
        updateStatus()
    }

    override fun onChat(from: String, text: String) {
        addChatLine(from, text, mine = false)
        if (chatPanel.visibility != View.VISIBLE) {
            unread++
            chatBadge.text = if (unread > 9) "9+" else unread.toString()
            chatBadge.visibility = View.VISIBLE
            chatToast.text = "$from: $text"
            chatToast.visibility = View.VISIBLE
            main.removeCallbacks(hideToast)
            main.postDelayed(hideToast, 4500)
        }
    }

    override fun onPing(ms: Int) {
        pingMs = ms
        updateStatus()
    }

    override fun onPeerLeft(reason: String) {
        if (finishing) return
        addSystemLine(reason)
        loop?.paused = true
        AlertDialog.Builder(this)
            .setTitle("Fin de la partida")
            .setMessage("$reason\n\nTu partida guardada está a salvo.")
            .setCancelable(false)
            .setPositiveButton("Salir") { _, _ -> leave() }
            .show()
    }

    override fun onFatal(message: String) = fatalDialog(message)

    // ---------- GameLoop.Listener (hilo del emulador) ----------

    override fun onStarted(title: String) = main.post {
        gameTitle = title
        updateStatus()
    }.let { }

    override fun onError(message: String) = main.post { fatalDialog(message) }.let { }

    override fun onWaiting(waiting: Boolean) = main.post {
        waitingText.text = "Esperando a $peerName…"
        waitingText.visibility = if (waiting) View.VISIBLE else View.GONE
    }.let { }

    override fun onDesync(frame: Int) = main.post {
        desynced = true
        updateStatus()
        addSystemLine("⚠ Los juegos se desincronizaron (cuadro $frame). Pueden seguir, pero quizás vean cosas distintas. Si pasa, salgan y creen otra sala.")
        Toast.makeText(this, "⚠ Se desincronizaron los juegos", Toast.LENGTH_LONG).show()
    }.let { }

    override fun onSaveChanged(data: ByteArray) {
        runCatching { Storage.writeSave(this, data) }
    }

    override fun onMessage(message: String) = main.post {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }.let { }

    override fun loadQuickState(): ByteArray? = Storage.readState(this)

    override fun storeQuickState(data: ByteArray) {
        runCatching { Storage.writeState(this, data) }
    }

    // ---------- Chat ----------

    private fun setupChat() {
        findViewById<View>(R.id.btnChat).setOnClickListener { openChat() }
        findViewById<View>(R.id.chatClose).setOnClickListener { closeChat() }
        findViewById<View>(R.id.chatSend).setOnClickListener { sendChat() }
        chatInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                sendChat()
                true
            } else {
                false
            }
        }
    }

    private fun openChat() {
        if (controls.editMode) finishEdit()
        chatPanel.visibility = View.VISIBLE
        controls.clear()
        controls.visibility = View.GONE
        findViewById<View>(R.id.btnMenu).visibility = View.INVISIBLE
        findViewById<View>(R.id.btnChat).visibility = View.INVISIBLE
        chatToast.visibility = View.GONE
        unread = 0
        chatBadge.visibility = View.GONE
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun closeChat() {
        chatPanel.visibility = View.GONE
        if (lobbyPanel.visibility != View.VISIBLE) controls.visibility = View.VISIBLE
        findViewById<View>(R.id.btnMenu).visibility = View.VISIBLE
        findViewById<View>(R.id.btnChat).visibility = View.VISIBLE
        chatInput.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(chatInput.windowToken, 0)
        hideSystemBars()
    }

    private fun sendChat() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty()) return
        chatInput.setText("")
        online?.sendChat(text)
        addChatLine("Vos", text, mine = true)
    }

    private fun addChatLine(from: String, text: String, mine: Boolean) {
        val line = SpannableStringBuilder()
        line.append("$from: ")
        val color = if (mine) Color.rgb(255, 170, 210) else Color.rgb(140, 210, 255)
        line.setSpan(ForegroundColorSpan(color), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        line.setSpan(StyleSpan(Typeface.BOLD), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        line.append(text)
        appendLine(line, Color.WHITE)
    }

    private fun addSystemLine(text: String) {
        val line = SpannableStringBuilder(text)
        line.setSpan(StyleSpan(Typeface.ITALIC), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        appendLine(line, Color.argb(170, 255, 255, 255))
    }

    private fun appendLine(text: CharSequence, color: Int) {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 15f
            setPadding(0, 4, 0, 4)
        }
        chatList.addView(view)
        while (chatList.childCount > MAX_CHAT_LINES) chatList.removeViewAt(0)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- Menú ----------

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val m = popup.menu
        val l = loop
        when (modeExtra) {
            MODE_SOLO -> {
                m.add(0, 1, 0, if (l?.paused == true) "Continuar" else "Pausar")
                m.add(0, 2, 0, "Guardar estado rápido")
                m.add(0, 3, 0, "Cargar estado rápido")
            }
            MODE_LOCAL2P -> {
                val other = if ((l?.viewPlayer ?: 0) == 0) 2 else 1
                m.add(0, 4, 0, "Ver y controlar la GBA $other")
                m.add(0, 1, 0, if (l?.paused == true) "Continuar" else "Pausar")
            }
        }
        m.add(0, 5, 0, "Mover controles")
        val orientationMenu = m.addSubMenu(0, 6, 0, "Orientación de pantalla")
        val current = screenOrientation
        orientationMenu.add(1, 60, 0, "Automática").setCheckable(true).isChecked = current == 0
        orientationMenu.add(1, 61, 0, "Horizontal").setCheckable(true).isChecked = current == 1
        orientationMenu.add(1, 62, 0, "Vertical").setCheckable(true).isChecked = current == 2
        orientationMenu.setGroupCheckable(1, true, true)
        m.add(0, 9, 0, if (online != null) "Salir de la sala" else "Salir")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> l?.let { it.paused = !it.paused }
                2 -> l?.requestSaveState()
                3 -> l?.requestLoadState()
                4 -> l?.let {
                    it.viewPlayer = 1 - it.viewPlayer
                    controls.clear()
                    updateStatus()
                }
                5 -> startEdit()
                60, 61, 62 -> {
                    screenOrientation = item.itemId - 60
                    applyOrientation()
                }
                9 -> confirmLeave()
            }
            true
        }
        popup.setOnDismissListener { hideSystemBars() }
        popup.show()
    }

    private fun shareCode() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "¡Vamos a jugar! Abrí GBA Link Online y uníte a la sala $roomCode")
        }
        startActivity(Intent.createChooser(send, "Compartir código"))
    }

    private fun updateStatus() {
        statusText.animate().cancel()
        statusText.alpha = 1f
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            statusText.animate().alpha(0f).setStartDelay(4000).setDuration(600).start()
        }
        val title = gameTitle.ifBlank { Storage.romName(this) ?: "" }
        statusText.text = when (modeExtra) {
            MODE_SOLO -> "Un jugador · $title"
            MODE_LOCAL2P -> "Prueba 2 jugadores · estás viendo la GBA ${(loop?.viewPlayer ?: 0) + 1} · $title"
            else -> buildString {
                append("En línea · Vos = J${localPlayer + 1}")
                append(" · $peerName = J${2 - localPlayer}")
                if (pingMs >= 0) append(" · ping $pingMs ms")
                if (delay > 0) append(" · retraso $delay")
                if (desynced) append(" · ⚠ desincronizados")
            }
        }
    }

    // ---------- Mover controles ----------

    private fun setupEditBar() {
        controls.onSelectionChanged = { name ->
            editHint.text = if (name == null) {
                "Arrastrá los controles adonde quieras.\nTocá uno para cambiarle el tamaño."
            } else {
                "Elegido: $name. Arrastralo o cambiale el tamaño."
            }
        }
        findViewById<View>(R.id.editSmaller).setOnClickListener { resizeSelected(-0.1f) }
        findViewById<View>(R.id.editBigger).setOnClickListener { resizeSelected(0.1f) }
        findViewById<View>(R.id.editFainter).setOnClickListener { controls.changeOpacity(-0.1f) }
        findViewById<View>(R.id.editStronger).setOnClickListener { controls.changeOpacity(0.1f) }
        findViewById<View>(R.id.editReset).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("¿Volver a poner los controles de esta orientación en su lugar original?")
                .setPositiveButton("Restablecer") { _, _ -> controls.resetLayout() }
                .setNegativeButton("Cancelar", null)
                .setOnDismissListener { hideSystemBars() }
                .show()
        }
        findViewById<View>(R.id.editDone).setOnClickListener { finishEdit() }
    }

    private fun resizeSelected(delta: Float) {
        if (!controls.resizeSelected(delta)) {
            Toast.makeText(this, "Tocá primero el control que querés cambiar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startEdit() {
        if (chatPanel.visibility == View.VISIBLE) closeChat()
        controls.visibility = View.VISIBLE
        controls.editMode = true
        editBar.visibility = View.VISIBLE
        // En línea no se pausa (tu pareja se quedaría congelada): solo se sueltan los botones.
        val l = loop
        if (online == null && l != null && !l.paused) {
            l.paused = true
            pausedForEdit = true
        }
    }

    private fun finishEdit() {
        controls.editMode = false
        editBar.visibility = View.GONE
        if (lobbyPanel.visibility == View.VISIBLE) controls.visibility = View.GONE
        if (pausedForEdit) {
            pausedForEdit = false
            loop?.paused = false
        }
        hideSystemBars()
    }

    // ---------- Orientación ----------

    private fun applyOrientation() {
        requestedOrientation = when (screenOrientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // La actividad no se recrea al girar (la partida sigue); solo se reacomoda todo.
        controls.clear()
        updateStatus()
        hideSystemBars()
    }

    // ---------- Salir ----------

    private fun confirmLeave() {
        AlertDialog.Builder(this)
            .setMessage(if (online != null) "¿Salir de la sala? Tu partida guardada queda a salvo." else "¿Salir del juego? Lo guardado dentro del juego queda a salvo.")
            .setPositiveButton("Salir") { _, _ -> leave() }
            .setNegativeButton("Seguir jugando", null)
            .setOnDismissListener { hideSystemBars() }
            .show()
    }

    private fun leave() {
        if (finishing) return
        finishing = true
        finish()
    }

    private fun fatalDialog(message: String) {
        if (isFinishing || isDestroyed) return
        loop?.paused = true
        AlertDialog.Builder(this)
            .setTitle("No se puede continuar")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Volver") { _, _ -> leave() }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            controls.editMode -> finishEdit()
            chatPanel.visibility == View.VISIBLE -> closeChat()
            else -> confirmLeave()
        }
    }

    // ---------- Gamepad / teclado físico ----------

    private fun mapKey(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_X -> Keys.A
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_Z -> Keys.B
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_A -> Keys.L
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_S -> Keys.R
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> Keys.START
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_DEL -> Keys.SELECT
        KeyEvent.KEYCODE_DPAD_UP -> Keys.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Keys.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Keys.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Keys.RIGHT
        else -> 0
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (chatPanel.visibility != View.VISIBLE) {
            val key = mapKey(event.keyCode)
            val l = loop
            if (key != 0 && l != null) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> l.padKeys.updateAndGet { it or key }
                    KeyEvent.ACTION_UP -> l.padKeys.updateAndGet { it and key.inv() }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- Ciclo de vida ----------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && chatPanel.visibility != View.VISIBLE) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }

    private var pausedByLifecycle = false

    override fun onPause() {
        super.onPause()
        controls.clear()
        loop?.padKeys?.set(0)
        // En línea el juego sigue (si no, tu pareja se quedaría congelada) pero sin sonido;
        // el resto de los modos se pausa.
        loop?.muted = true
        if (online == null) {
            loop?.let {
                if (!it.paused) {
                    it.paused = true
                    pausedByLifecycle = true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loop?.muted = false
        if (pausedByLifecycle) {
            pausedByLifecycle = false
            loop?.paused = false
        }
        hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        online?.close()
        online = null
        loop?.shutdown()
        loop = null
    }
}
