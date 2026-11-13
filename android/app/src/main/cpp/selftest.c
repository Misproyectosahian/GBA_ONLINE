/*
 * Prueba de línea de comandos (se ejecuta en un teléfono o emulador con adb shell).
 *
 * Simula dos teléfonos: crea dos sesiones idénticas de dos GBA enlazadas, les da los
 * mismos botones pseudoaleatorios y verifica que el estado de ambas sea idéntico en
 * cada cuadro. También mide la velocidad y guarda capturas en formato PPM.
 *
 * Uso: gbalink_selftest <rom.gba> [cuadros] [carpeta_capturas] [guion]
 *   guion: "random" (botones aleatorios) o "linktest" (guion para la ROM de prueba).
 */
#include "gbalink_session.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static uint32_t _rng = 12345;
static uint16_t _randomKeys(void) {
	_rng = _rng * 1103515245u + 12345u;
	uint16_t keys = (_rng >> 16) & 0x3FF;
	/* Pocas veces a la vez y sin Start/Select constantes para no quedarse en el menú. */
	if ((_rng >> 8) & 3) {
		keys &= GL_KEY_A | GL_KEY_B | GL_KEY_RIGHT | GL_KEY_LEFT | GL_KEY_UP | GL_KEY_DOWN;
	}
	return keys;
}

static void _writePpm(const char* path, const uint32_t* pixels) {
	FILE* f = fopen(path, "wb");
	if (!f) {
		return;
	}
	fprintf(f, "P6\n%d %d\n255\n", GL_WIDTH, GL_HEIGHT);
	int i;
	for (i = 0; i < GL_WIDTH * GL_HEIGHT; ++i) {
		uint32_t c = pixels[i];
		unsigned char rgb[3] = { c & 0xFF, (c >> 8) & 0xFF, (c >> 16) & 0xFF };
		fwrite(rgb, 1, 3, f);
	}
	fclose(f);
}

static const char* _colorName(uint32_t c) {
	unsigned r = c & 0xFF, g = (c >> 8) & 0xFF, b = (c >> 16) & 0xFF;
	if (r > 200 && g < 50 && b < 50) return "rojo";
	if (g > 200 && r < 50 && b < 50) return "verde";
	if (b > 200 && r < 50 && g < 50) return "azul";
	if (r > 100 && r < 160 && r == g && g == b) return "gris";
	return "otro";
}

static int _checkColor(struct GLSession* s, int player, int frame, const char* expected) {
	const char* got = _colorName(GLSessionVideo(s, player)[80 * GL_WIDTH + 120]);
	int ok = strcmp(got, expected) == 0;
	printf("  cuadro %4d, pantalla de la consola %d: %-6s (esperado %-6s) %s\n", frame, player + 1, got, expected, ok ? "OK" : "FALLO");
	return ok ? 0 : 1;
}

static double _now(void) {
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec + ts.tv_nsec / 1e9;
}

int main(int argc, char** argv) {
	if (argc < 2) {
		fprintf(stderr, "uso: %s rom.gba [cuadros] [carpeta] [random|linktest]\n", argv[0]);
		return 2;
	}
	int frames = argc > 2 ? atoi(argv[2]) : 3000;
	const char* outDir = argc > 3 ? argv[3] : NULL;
	const char* script = argc > 4 ? argv[4] : "random";
	bool linkTest = strcmp(script, "linktest") == 0;

	struct GLSessionConfig config;
	memset(&config, 0, sizeof(config));
	config.romPath = argv[1];
	config.players = 2;
	config.rtcEpochMs = 1700000000000LL;
	config.sampleRate = 48000;

	char error[256];
	struct GLSession* a = GLSessionCreate(&config, error, sizeof(error));
	if (!a) {
		fprintf(stderr, "Error creando sesión A: %s\n", error);
		return 1;
	}
	struct GLSession* b = GLSessionCreate(&config, error, sizeof(error));
	if (!b) {
		fprintf(stderr, "Error creando sesión B: %s\n", error);
		return 1;
	}
	char title[32];
	GLSessionGameTitle(a, title);
	printf("Juego: '%s'  cuadros: %d  guion: %s\n", title, frames, script);

	int16_t audio[4096];
	double start = _now();
	double timeA = 0;
	int i;
	int mismatches = 0;
	for (i = 0; i < frames; ++i) {
		uint16_t keys[2];
		if (linkTest) {
			/* El jugador 2 presiona A en los cuadros 200-299 y B en 400-499; el 1 presiona A en 550-649. */
			keys[0] = 0;
			keys[1] = 0;
			if (i >= 200 && i < 300) {
				keys[1] = GL_KEY_A;
			} else if (i >= 400 && i < 500) {
				keys[1] = GL_KEY_B;
			} else if (i >= 550 && i < 650) {
				keys[0] = GL_KEY_A;
			}
		} else {
			keys[0] = _randomKeys();
			keys[1] = _randomKeys();
		}
		double t0 = _now();
		GLSessionRunFrame(a, keys);
		timeA += _now() - t0;
		GLSessionRunFrame(b, keys);
		GLSessionReadAudio(a, audio, 2048);
		GLSessionReadAudio(b, audio, 2048);

		uint32_t ha = GLSessionHash(a);
		uint32_t hb = GLSessionHash(b);
		if (ha != hb) {
			if (mismatches < 10) {
				printf("DESINCRONIZADO en cuadro %d: %08X != %08X\n", i, ha, hb);
			}
			++mismatches;
		}
		if (linkTest) {
			/* La pantalla de cada consola muestra lo que presiona la otra. */
			if (i == 150) {
				mismatches += _checkColor(a, 0, i, "azul");
				mismatches += _checkColor(a, 1, i, "azul");
			} else if (i == 299) {
				mismatches += _checkColor(a, 0, i, "verde");
				mismatches += _checkColor(a, 1, i, "azul");
			} else if (i == 499) {
				mismatches += _checkColor(a, 0, i, "rojo");
				mismatches += _checkColor(a, 1, i, "azul");
			} else if (i == 649) {
				mismatches += _checkColor(a, 0, i, "azul");
				mismatches += _checkColor(a, 1, i, "verde");
			}
		}
		if (outDir && (i == 299 || i == 499 || i == frames - 1)) {
			char path[512];
			snprintf(path, sizeof(path), "%s/f%05d_p1.ppm", outDir, i);
			_writePpm(path, GLSessionVideo(a, 0));
			snprintf(path, sizeof(path), "%s/f%05d_p2.ppm", outDir, i);
			_writePpm(path, GLSessionVideo(a, 1));
		}
	}
	double elapsed = _now() - start;
	printf("Cuadros: %d  tiempo total: %.2fs  velocidad de UNA sesión (2 GBA): %.0f%% de tiempo real\n",
	       frames, elapsed, 100.0 * frames / 59.7275 / timeA);
	printf("Cuadro final consola 1: %u   bloqueos del cable: A=%u B=%u\n",
	       GLSessionFrameCount(a), GLSessionLinkStalls(a), GLSessionLinkStalls(b));
	printf("Hash final A=%08X B=%08X\n", GLSessionHash(a), GLSessionHash(b));
	printf(mismatches ? "RESULTADO: FALLO (%d errores)\n" : "RESULTADO: OK, ambas sesiones idénticas\n", mismatches);

	GLSessionDestroy(a);
	GLSessionDestroy(b);
	return mismatches ? 1 : 0;
}
