package com.gbalink.online

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/**
 * Archivos de la app (todo en almacenamiento interno):
 *   rom/current.gba          la ROM elegida (copiada, para no depender del permiso del archivo original)
 *   saves/<crc>.sav           partida guardada de esa ROM (la misma para solo y en línea)
 *   saves/<crc>.sav.bak       copia de seguridad antes de cada partida en línea o importación
 *   states/<crc>.state        estado rápido (solo modo un jugador)
 */
object Storage {
    private const val PREFS = "gbalink"
    const val MAX_ROM_BYTES = 64 * 1024 * 1024
    const val MAX_SAVE_BYTES = 512 * 1024

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun romFile(ctx: Context) = File(ctx.filesDir, "rom/current.gba")
    fun romName(ctx: Context): String? = prefs(ctx).getString("romName", null)
    fun romTitle(ctx: Context): String? = prefs(ctx).getString("romTitle", null)
    fun romCrc(ctx: Context): String? = prefs(ctx).getString("romCrc", null)
    fun hasRom(ctx: Context) = romCrc(ctx) != null && romFile(ctx).isFile

    var Context.playerName: String
        get() = prefs(this).getString("playerName", "") ?: ""
        set(value) = prefs(this).edit().putString("playerName", value.trim()).apply()

    var Context.serverUrl: String
        get() = prefs(this).getString("serverUrl", "") ?: ""
        set(value) = prefs(this).edit().putString("serverUrl", value.trim()).apply()

    /** 0 = automático según el ping; 2..12 = cuadros fijos. */
    var Context.inputDelay: Int
        get() = prefs(this).getInt("inputDelay", 0)
        set(value) = prefs(this).edit().putInt("inputDelay", value).apply()

    /** 0 = automática (sigue la rotación del teléfono), 1 = horizontal, 2 = vertical. */
    var Context.screenOrientation: Int
        get() = prefs(this).getInt("screenOrientation", 0)
        set(value) = prefs(this).edit().putInt("screenOrientation", value).apply()

    /** Disposición de los controles ("port" o "land"), como JSON de ControlsView. */
    fun controlsLayout(ctx: Context, key: String): String? = prefs(ctx).getString("controls_$key", null)

    fun setControlsLayout(ctx: Context, key: String, json: String?) {
        prefs(ctx).edit().apply { if (json == null) remove("controls_$key") else putString("controls_$key", json) }.apply()
    }

    var Context.controlsOpacity: Float
        get() = prefs(this).getFloat("controlsOpacity", 0.6f)
        set(value) = prefs(this).edit().putFloat("controlsOpacity", value).apply()

    fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment ?: "juego.gba"
    }

    /** Copia la ROM elegida (acepta .gba o .zip con un .gba adentro). Devuelve el nombre mostrado. */
    fun importRom(ctx: Context, uri: Uri): String {
        val name = displayName(ctx, uri)
        val tmp = File(ctx.filesDir, "rom/import.tmp")
        tmp.parentFile?.mkdirs()
        var shownName = name
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            val input = BufferedInputStream(raw)
            input.mark(4)
            val magic = ByteArray(4)
            val read = input.read(magic)
            input.reset()
            val isZip = read == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() &&
                magic[2] == 3.toByte() && magic[3] == 4.toByte()
            if (isZip) {
                val zip = ZipInputStream(input)
                var entry = zip.nextEntry
                var found = false
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".gba")) {
                        copyLimited(zip, tmp, MAX_ROM_BYTES)
                        shownName = entry.name.substringAfterLast('/')
                        found = true
                        break
                    }
                    entry = zip.nextEntry
                }
                if (!found) throw IllegalArgumentException("El .zip no contiene ningún archivo .gba")
            } else {
                copyLimited(input, tmp, MAX_ROM_BYTES)
            }
        } ?: throw IllegalArgumentException("No se pudo abrir el archivo")

        val bytes = tmp.readBytes()
        if (bytes.size < 0xC0 || (bytes[0xB2].toInt() and 0xFF) != 0x96 || (bytes[3].toInt() and 0xFF) != 0xEA) {
            tmp.delete()
            throw IllegalArgumentException("Ese archivo no parece una ROM de Game Boy Advance")
        }
        val crc = CRC32().apply { update(bytes) }.value
        val title = String(bytes, 0xA0, 12, Charsets.US_ASCII).trim { it <= ' ' } +
            " (" + String(bytes, 0xAC, 4, Charsets.US_ASCII).trim { it <= ' ' } + ")"
        val dest = romFile(ctx)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        prefs(ctx).edit()
            .putString("romName", shownName)
            .putString("romTitle", title)
            .putString("romCrc", "%08X".format(crc))
            .apply()
        return shownName
    }

    private fun copyLimited(input: InputStream, dest: File, limit: Int) {
        dest.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw IllegalArgumentException("El archivo es demasiado grande")
                out.write(buf, 0, n)
            }
        }
    }

    fun saveFile(ctx: Context): File = File(ctx.filesDir, "saves/${romCrc(ctx) ?: "none"}.sav")
    fun stateFile(ctx: Context): File = File(ctx.filesDir, "states/${romCrc(ctx) ?: "none"}.state")

    fun readSave(ctx: Context): ByteArray? {
        val f = saveFile(ctx)
        return if (f.isFile && f.length() in 1..MAX_SAVE_BYTES) f.readBytes() else null
    }

    /** Escritura atómica: primero a un temporal y luego se renombra. */
    fun writeSave(ctx: Context, data: ByteArray) = writeAtomic(saveFile(ctx), data)

    fun backupSave(ctx: Context) {
        val f = saveFile(ctx)
        if (f.isFile) f.copyTo(File(f.path + ".bak"), overwrite = true)
    }

    fun importSave(ctx: Context, uri: Uri) {
        val data = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("No se pudo abrir el archivo")
        if (data.isEmpty() || data.size > MAX_SAVE_BYTES) {
            throw IllegalArgumentException("Ese archivo no parece una partida guardada de GBA")
        }
        backupSave(ctx)
        writeSave(ctx, data)
    }

    fun exportSave(ctx: Context, uri: Uri) {
        val data = readSave(ctx) ?: throw IllegalStateException("Todavía no hay partida guardada")
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
            ?: throw IllegalArgumentException("No se pudo escribir el archivo")
    }

    fun writeState(ctx: Context, data: ByteArray) = writeAtomic(stateFile(ctx), data)
    fun readState(ctx: Context): ByteArray? = stateFile(ctx).takeIf { it.isFile }?.readBytes()

    private fun writeAtomic(dest: File, data: ByteArray) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".tmp")
        tmp.outputStream().use {
            it.write(data)
            it.fd.sync()
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    fun crc32(data: ByteArray?): Long = if (data == null) 0 else CRC32().apply { update(data) }.value
}
