package com.gbalink.online

import android.view.Surface

/**
 * Funciones del motor en C (gbalink_jni.c). Todas las que reciben `handle` deben
 * llamarse desde el hilo del emulador.
 */
object NativeBridge {
    init {
        System.loadLibrary("gbalink")
    }

    /** Devuelve 0 si falla; en ese caso error[0] trae el motivo. */
    @JvmStatic external fun create(
        romPath: String,
        players: Int,
        save0: ByteArray?,
        save1: ByteArray?,
        rtcEpochMs: Long,
        sampleRate: Int,
        error: Array<String?>,
    ): Long

    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun runFrame(handle: Long, keys0: Int, keys1: Int)
    @JvmStatic external fun frameCount(handle: Long): Int
    @JvmStatic external fun setAudioPlayer(handle: Long, player: Int)

    /** Llena `out` con audio estéreo intercalado; devuelve cuántos `short` escribió. */
    @JvmStatic external fun readAudio(handle: Long, out: ShortArray): Int
    @JvmStatic external fun stateHash(handle: Long): Int
    @JvmStatic external fun cloneSave(handle: Long, player: Int): ByteArray?
    @JvmStatic external fun saveState(handle: Long): ByteArray?
    @JvmStatic external fun loadState(handle: Long, state: ByteArray): Boolean
    @JvmStatic external fun gameTitle(handle: Long): String
    @JvmStatic external fun linkStalls(handle: Long): Int

    /** Se puede llamar desde cualquier hilo. `scale` = ampliación entera previa (nitidez). */
    @JvmStatic external fun setSurface(surface: Surface?, scale: Int)
    @JvmStatic external fun present(handle: Long, player: Int)
}

/** Bits de botones (mismo orden que el registro KEYINPUT de la GBA). */
object Keys {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val RIGHT = 1 shl 4
    const val LEFT = 1 shl 5
    const val UP = 1 shl 6
    const val DOWN = 1 shl 7
    const val R = 1 shl 8
    const val L = 1 shl 9
}
