package com.gbalink.online

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.gbalink.online.Storage.inputDelay
import com.gbalink.online.Storage.playerName
import com.gbalink.online.Storage.serverUrl
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var romInfo: TextView
    private lateinit var saveInfo: TextView
    private lateinit var nameInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var serverInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.mainRoot).setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }

        romInfo = findViewById(R.id.romInfo)
        saveInfo = findViewById(R.id.saveInfo)
        nameInput = findViewById(R.id.nameInput)
        codeInput = findViewById(R.id.codeInput)
        serverInput = findViewById(R.id.serverInput)

        nameInput.setText(playerName)
        serverInput.setText(serverUrl)
        nameInput.afterChange { playerName = it }
        serverInput.afterChange { serverUrl = it }
        setupDelaySpinner()

        findViewById<Button>(R.id.btnPickRom).setOnClickListener {
            pick(REQ_ROM, Intent.ACTION_OPEN_DOCUMENT)
        }
        findViewById<Button>(R.id.btnImportSave).setOnClickListener {
            if (requireRom()) pick(REQ_IMPORT_SAVE, Intent.ACTION_OPEN_DOCUMENT)
        }
        findViewById<Button>(R.id.btnExportSave).setOnClickListener {
            if (!requireRom()) return@setOnClickListener
            if (Storage.readSave(this) == null) {
                toast("Todavía no hay partida guardada")
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, (Storage.romName(this@MainActivity) ?: "partida").substringBeforeLast('.') + ".sav")
            }
            startActivityForResult(intent, REQ_EXPORT_SAVE)
        }
        findViewById<Button>(R.id.btnSolo).setOnClickListener { play(GameActivity.MODE_SOLO) }
        findViewById<Button>(R.id.btnLocal2p).setOnClickListener { play(GameActivity.MODE_LOCAL2P) }
        findViewById<Button>(R.id.btnHost).setOnClickListener { play(GameActivity.MODE_HOST) }
        findViewById<Button>(R.id.btnJoin).setOnClickListener {
            val code = codeInput.text.toString().trim()
            if (code.length != 4 || !code.all { it.isDigit() }) {
                toast("Escribí el código de 4 números que te pasó tu pareja")
            } else {
                play(GameActivity.MODE_JOIN, code)
            }
        }
        findViewById<Button>(R.id.btnTestServer).setOnClickListener { testServer() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun refresh() {
        if (Storage.hasRom(this)) {
            romInfo.text = "${Storage.romName(this)}\n${Storage.romTitle(this)} · CRC ${Storage.romCrc(this)}"
            val save = Storage.saveFile(this)
            saveInfo.text = if (save.isFile && save.length() > 0) {
                "Hay partida guardada (${save.length() / 1024} KB). Se usa tanto solo como en línea."
            } else {
                "Sin partida guardada todavía. Podés importar la de otro emulador (por ejemplo My Boy!)."
            }
        } else {
            romInfo.text = "Ningún juego elegido. Elegí la ROM de tu cartucho."
            saveInfo.text = "Elegí primero el juego."
        }
    }

    private fun setupDelaySpinner() {
        val spinner = findViewById<Spinner>(R.id.delaySpinner)
        val options = listOf("Automático") + (2..12).map { "$it cuadros (${(it * 16.74).toInt()} ms)" }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, options) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).also { (it as TextView).setTextColor(0xFFFFFFFF.toInt()) }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also { (it as TextView).setTextColor(0xFFFFFFFF.toInt()) }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(if (inputDelay in 2..12) inputDelay - 1 else 0)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                inputDelay = if (position == 0) 0 else position + 1
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun requireRom(): Boolean {
        if (!Storage.hasRom(this)) {
            toast("Primero elegí la ROM del juego")
            return false
        }
        return true
    }

    private fun play(mode: String, code: String? = null) {
        if (!requireRom()) return
        if (mode == GameActivity.MODE_HOST || mode == GameActivity.MODE_JOIN) {
            val url = serverInput.text.toString().trim()
            if (!(url.startsWith("ws://") || url.startsWith("wss://"))) {
                AlertDialog.Builder(this)
                    .setTitle("Falta el servidor")
                    .setMessage("Para jugar en línea escribí abajo, en «Servidor», la dirección del servidor relay. Empieza con ws:// o wss:// y termina en /ws.\n\nEjemplo: wss://mi-relay.onrender.com/ws")
                    .setPositiveButton("Entendido", null)
                    .show()
                return
            }
            serverUrl = url
            if (playerName.isBlank()) {
                toast("Escribí tu nombre para que tu pareja te vea")
                nameInput.requestFocus()
                return
            }
        }
        startActivity(Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_MODE, mode)
            if (code != null) putExtra(GameActivity.EXTRA_CODE, code)
        })
    }

    private fun testServer() {
        val url = serverInput.text.toString().trim()
        if (!(url.startsWith("ws://") || url.startsWith("wss://"))) {
            toast("La dirección tiene que empezar con ws:// o wss://")
            return
        }
        serverUrl = url
        toast("Probando conexión… si el servidor estaba dormido puede tardar hasta un minuto")
        val start = System.currentTimeMillis()
        var client: NetClient? = null
        val listener = object : NetClient.Listener {
            override fun onOpen() {
                client?.send(JSONObject().put("t", "hi"))
            }
            override fun onMessage(msg: JSONObject) {
                if (msg.optString("t") == "hi") {
                    val ms = System.currentTimeMillis() - start
                    val rooms = msg.optInt("rooms")
                    main.post { alert("¡Conexión correcta!", "El servidor respondió en $ms ms.\nSalas abiertas ahora: $rooms") }
                    client?.close()
                }
            }
            override fun onClosed(reason: String) {
                main.post { alert("No se pudo conectar", "$reason\n\nRevisá la dirección, que el servidor esté encendido y que el teléfono tenga Internet.") }
            }
        }
        try {
            client = NetClient(url, listener)
        } catch (e: IllegalArgumentException) {
            alert("Dirección inválida", e.message ?: "")
        }
    }

    // ---------- Selector de archivos ----------

    private fun pick(request: Int, action: String) {
        val intent = Intent(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, request)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        io.execute {
            val result = runCatching {
                when (requestCode) {
                    REQ_ROM -> "Juego listo: " + Storage.importRom(this, uri)
                    REQ_IMPORT_SAVE -> {
                        Storage.importSave(this, uri)
                        "Partida importada (la anterior quedó como copia .bak)"
                    }
                    REQ_EXPORT_SAVE -> {
                        Storage.exportSave(this, uri)
                        "Partida exportada"
                    }
                    else -> ""
                }
            }
            main.post {
                result.onSuccess { if (it.isNotEmpty()) toast(it) }
                    .onFailure { alert("Error", it.message ?: it.toString()) }
                refresh()
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun alert(title: String, message: String) {
        if (isFinishing) return
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun EditText.afterChange(block: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = block(s?.toString() ?: "")
    })

    companion object {
        private const val REQ_ROM = 1
        private const val REQ_IMPORT_SAVE = 2
        private const val REQ_EXPORT_SAVE = 3
    }
}
