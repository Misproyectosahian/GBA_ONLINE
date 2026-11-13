/*
 * Sesión de juego: una o dos GBA emuladas con mGBA.
 *
 * Con dos jugadores, ambas consolas viven en el mismo proceso y se conectan con el
 * cable link virtual de mGBA (GBASIOLockstep). Las dos se ejecutan en un único hilo,
 * alternándose en un orden fijo, así que dos teléfonos que reciben los mismos botones
 * producen exactamente el mismo resultado. Por la red solo viajan los botones.
 */
#ifndef GBALINK_SESSION_H
#define GBALINK_SESSION_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define GL_MAX_PLAYERS 2
#define GL_WIDTH 240
#define GL_HEIGHT 160

/* Bits de los botones, en el mismo orden que el registro KEYINPUT de la GBA. */
enum {
	GL_KEY_A = 1 << 0,
	GL_KEY_B = 1 << 1,
	GL_KEY_SELECT = 1 << 2,
	GL_KEY_START = 1 << 3,
	GL_KEY_RIGHT = 1 << 4,
	GL_KEY_LEFT = 1 << 5,
	GL_KEY_UP = 1 << 6,
	GL_KEY_DOWN = 1 << 7,
	GL_KEY_R = 1 << 8,
	GL_KEY_L = 1 << 9,
};

struct GLSession;

struct GLSessionConfig {
	const char* romPath;
	/* 1 = un jugador; 2 = dos GBA enlazadas por cable. */
	int players;
	/* Partida guardada (.sav) de cada consola; NULL/0 = partida nueva. */
	const void* save[GL_MAX_PLAYERS];
	size_t saveSize[GL_MAX_PLAYERS];
	/* 0 = reloj real. Distinto de 0 = reloj falso que arranca en esa fecha (ms) y avanza
	 * con el tiempo emulado; así el reloj del cartucho es idéntico en ambos teléfonos. */
	int64_t rtcEpochMs;
	/* Frecuencia de salida de audio (p. ej. 48000). */
	unsigned sampleRate;
};

struct GLSession* GLSessionCreate(const struct GLSessionConfig* config, char* error, size_t errorLen);
void GLSessionDestroy(struct GLSession*);

int GLSessionPlayers(const struct GLSession*);

/* Ejecuta un cuadro (de la consola 1) con los botones de cada jugador. */
void GLSessionRunFrame(struct GLSession*, const uint16_t keys[GL_MAX_PLAYERS]);
uint32_t GLSessionFrameCount(const struct GLSession*);

/* Último cuadro completo de la consola indicada: 240x160 píxeles, formato RGBX8888. */
const uint32_t* GLSessionVideo(const struct GLSession*, int player);

/* Qué consola se escucha. El audio de la otra se descarta. */
void GLSessionSetAudioPlayer(struct GLSession*, int player);
/* Copia hasta maxFrames cuadros de audio estéreo (2 int16 por cuadro). Devuelve cuántos copió. */
size_t GLSessionReadAudio(struct GLSession*, int16_t* out, size_t maxFrames);

/* Huella del estado de ambas consolas, para detectar desincronización entre teléfonos. */
uint32_t GLSessionHash(struct GLSession*);

/* Copia de la partida guardada de una consola. El llamador debe liberar *out con free(). */
size_t GLSessionCloneSave(struct GLSession*, int player, void** out);

/* Estados rápidos (solo modo de un jugador). */
size_t GLSessionStateSize(struct GLSession*);
bool GLSessionSaveState(struct GLSession*, void* out);
bool GLSessionLoadState(struct GLSession*, const void* in, size_t size);

/* Título interno del cartucho (hasta 12 caracteres + código de 4). */
void GLSessionGameTitle(struct GLSession*, char out[32]);

/* Diagnóstico: cuántas veces hubo que romper un bloqueo del cable (debería ser 0). */
unsigned GLSessionLinkStalls(const struct GLSession*);

#endif
