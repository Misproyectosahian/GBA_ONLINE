/*
 * Implementación de la sesión: ver gbalink_session.h.
 *
 * Cómo se enlazan las dos GBA sin hilos:
 * mGBA trae un "coordinador" de cable link (GBASIOLockstepCoordinator). Cada consola
 * tiene un driver que, cuando tiene que esperar a la otra, llama a sleep(); cuando ya
 * puede seguir, la otra consola llama a wake(). En el emulador de PC cada consola corre
 * en su propio hilo y sleep() bloquea ese hilo. Aquí sleep()/wake() solo marcan una
 * bandera, y un bucle único va dando turnos a la consola que esté despierta. Como el
 * orden de los turnos depende solo del estado emulado (nunca del reloj del teléfono ni
 * de la red), dos teléfonos con los mismos botones llegan al mismo resultado.
 */
#include "gbalink_session.h"

#include <mgba/core/config.h>
#include <mgba/core/core.h>
#include <mgba/core/interface.h>
#include <mgba/core/lockstep.h>
#include <mgba/core/log.h>
#include <mgba/gba/core.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/memory.h>
#include <mgba/internal/gba/sio/lockstep.h>
#include <mgba-util/audio-buffer.h>
#include <mgba-util/audio-resampler.h>
#include <mgba-util/crc32.h>
#include <mgba-util/vfs.h>

#include <android/log.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "GBALink"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Tope de vueltas del planificador por cuadro; solo protege contra un cuelgue. */
#define GL_MAX_SPINS 4000000
#define GL_CORE_AUDIO_SAMPLES 2048
#define GL_OUT_AUDIO_FRAMES 8192

struct GLLockstepUser {
	struct mLockstepUser d;
	int preferredId;
	bool asleep;
};

struct GLPlayer {
	struct mCore* core;
	mColor* render;
	uint32_t* frame;
	bool frameDone;
	struct mCoreCallbacks callbacks;
	struct GBASIOLockstepDriver driver;
	struct GLLockstepUser user;
};

struct GLSession {
	int players;
	struct GLPlayer p[GL_MAX_PLAYERS];
	void* rom;
	size_t romSize;
	bool linked;
	struct GBASIOLockstepCoordinator coordinator;
	int audioPlayer;
	struct mAudioBuffer audioOut;
	struct mAudioResampler resampler;
	unsigned sampleRate;
	bool audioReady;
	unsigned linkStalls;
};

/* ---------- Log de mGBA hacia logcat ---------- */

static void _logToLogcat(struct mLogger* logger, int category, enum mLogLevel level, const char* format, va_list args) {
	(void) logger;
	if (!(level & (mLOG_FATAL | mLOG_ERROR | mLOG_WARN))) {
		return;
	}
	char message[512];
	vsnprintf(message, sizeof(message), format, args);
	int prio = ANDROID_LOG_WARN;
	if (level & (mLOG_FATAL | mLOG_ERROR)) {
		prio = ANDROID_LOG_ERROR;
	}
	__android_log_print(prio, "mGBA", "[%s] %s", mLogCategoryName(category), message);
}

static struct mLogger _logger = {
	.log = _logToLogcat,
	.filter = NULL,
};

/* ---------- Usuario del cable link (sleep/wake como banderas) ---------- */

static void _userSleep(struct mLockstepUser* user) {
	((struct GLLockstepUser*) user)->asleep = true;
}

static void _userWake(struct mLockstepUser* user) {
	((struct GLLockstepUser*) user)->asleep = false;
}

static int _userRequestedId(struct mLockstepUser* user) {
	return ((struct GLLockstepUser*) user)->preferredId;
}

static void _userPlayerIdChanged(struct mLockstepUser* user, int id) {
	LOGI("Consola %d recibió el ID de cable %d", ((struct GLLockstepUser*) user)->preferredId, id);
}

/* ---------- Video ---------- */

static void _frameEnded(void* context) {
	struct GLPlayer* player = context;
	memcpy(player->frame, player->render, GL_WIDTH * GL_HEIGHT * sizeof(uint32_t));
	player->frameDone = true;
}

/* ---------- Creación ---------- */

static bool _loadRom(struct GLSession* session, const char* path, char* error, size_t errorLen) {
	FILE* file = fopen(path, "rb");
	if (!file) {
		snprintf(error, errorLen, "No se pudo abrir la ROM");
		return false;
	}
	fseek(file, 0, SEEK_END);
	long size = ftell(file);
	fseek(file, 0, SEEK_SET);
	if (size <= 0xC0 || size > 64 * 1024 * 1024) {
		fclose(file);
		snprintf(error, errorLen, "La ROM tiene un tamaño inválido");
		return false;
	}
	session->rom = malloc((size_t) size);
	if (!session->rom) {
		fclose(file);
		snprintf(error, errorLen, "No hay memoria para la ROM");
		return false;
	}
	size_t read = fread(session->rom, 1, (size_t) size, file);
	fclose(file);
	if (read != (size_t) size) {
		snprintf(error, errorLen, "No se pudo leer la ROM completa");
		return false;
	}
	session->romSize = (size_t) size;
	return true;
}

static bool _createPlayer(struct GLSession* session, int index, const struct GLSessionConfig* config, char* error, size_t errorLen) {
	struct GLPlayer* player = &session->p[index];
	struct mCore* core = GBACoreCreate();
	if (!core) {
		snprintf(error, errorLen, "No se pudo crear el núcleo GBA");
		return false;
	}
	mCoreInitConfig(core, NULL);
	if (!core->init(core)) {
		snprintf(error, errorLen, "No se pudo iniciar el núcleo GBA");
		free(core);
		return false;
	}
	player->core = core;

	player->render = calloc(GL_WIDTH * GL_HEIGHT, sizeof(mColor));
	player->frame = calloc(GL_WIDTH * GL_HEIGHT, sizeof(uint32_t));
	if (!player->render || !player->frame) {
		snprintf(error, errorLen, "No hay memoria para el video");
		return false;
	}
	core->setVideoBuffer(core, player->render, GL_WIDTH);
	core->setAudioBufferSize(core, GL_CORE_AUDIO_SAMPLES);

	struct mCoreOptions opts = {
		.useBios = false,
		.skipBios = true,
		.volume = 0x100,
		.sampleRate = config->sampleRate,
	};
	/* "remove" solo usa la lista conocida de bucles ociosos: rápido y determinista. */
	mCoreConfigSetDefaultValue(&core->config, "idleOptimization", "remove");
	mCoreConfigLoadDefaults(&core->config, &opts);
	mCoreLoadConfig(core);

	player->callbacks = (struct mCoreCallbacks) {
		.context = player,
		.videoFrameEnded = _frameEnded,
	};
	core->addCoreCallbacks(core, &player->callbacks);

	struct VFile* rom = VFileFromConstMemory(session->rom, session->romSize);
	if (!rom || !core->loadROM(core, rom)) {
		snprintf(error, errorLen, "El archivo no es una ROM de GBA válida");
		if (rom) {
			rom->close(rom);
		}
		return false;
	}

	/* La partida guardada vive en memoria; Kotlin la escribe a disco cuando cambia. */
	struct VFile* save = VFileMemChunk(config->save[index], config->save[index] ? config->saveSize[index] : 0);
	if (!save || !core->loadSave(core, save)) {
		snprintf(error, errorLen, "No se pudo cargar la partida guardada");
		if (save) {
			save->close(save);
		}
		return false;
	}

	if (config->rtcEpochMs) {
		core->rtc.override = RTC_FAKE_EPOCH;
		core->rtc.value = config->rtcEpochMs;
	}

	core->reset(core);
	return true;
}

static void _linkPlayers(struct GLSession* session) {
	GBASIOLockstepCoordinatorInit(&session->coordinator);
	int i;
	for (i = 0; i < session->players; ++i) {
		struct GLPlayer* player = &session->p[i];
		memset(&player->user, 0, sizeof(player->user));
		player->user.d.sleep = _userSleep;
		player->user.d.wake = _userWake;
		player->user.d.requestedId = _userRequestedId;
		player->user.d.playerIdChanged = _userPlayerIdChanged;
		player->user.preferredId = i;
		GBASIOLockstepDriverCreate(&player->driver, &player->user.d);
		GBASIOLockstepCoordinatorAttach(&session->coordinator, &player->driver);
		player->core->setPeripheral(player->core, mPERIPH_GBA_LINK_PORT, &player->driver.d);
	}
	session->linked = true;
}

struct GLSession* GLSessionCreate(const struct GLSessionConfig* config, char* error, size_t errorLen) {
	static bool loggerInstalled = false;
	if (!loggerInstalled) {
		mLogSetDefaultLogger(&_logger);
		loggerInstalled = true;
	}

	if (config->players < 1 || config->players > GL_MAX_PLAYERS) {
		snprintf(error, errorLen, "Cantidad de jugadores inválida");
		return NULL;
	}
	struct GLSession* session = calloc(1, sizeof(*session));
	if (!session) {
		snprintf(error, errorLen, "No hay memoria");
		return NULL;
	}
	session->players = config->players;
	session->sampleRate = config->sampleRate ? config->sampleRate : 48000;

	if (!_loadRom(session, config->romPath, error, errorLen)) {
		GLSessionDestroy(session);
		return NULL;
	}

	int i;
	for (i = 0; i < session->players; ++i) {
		if (!_createPlayer(session, i, config, error, errorLen)) {
			GLSessionDestroy(session);
			return NULL;
		}
	}
	if (session->players > 1) {
		_linkPlayers(session);
	}

	mAudioBufferInit(&session->audioOut, GL_OUT_AUDIO_FRAMES, 2);
	mAudioResamplerInit(&session->resampler, mINTERPOLATOR_SINC);
	mAudioResamplerSetDestination(&session->resampler, &session->audioOut, session->sampleRate);
	session->audioReady = true;
	session->audioPlayer = 0;

	LOGI("Sesión creada: %d jugador(es), ROM de %zu bytes", session->players, session->romSize);
	return session;
}

void GLSessionDestroy(struct GLSession* session) {
	if (!session) {
		return;
	}
	int i;
	if (session->linked) {
		/* Desenchufar primero a los secundarios y al final a la consola 1. */
		for (i = session->players - 1; i >= 0; --i) {
			struct GLPlayer* player = &session->p[i];
			if (player->core) {
				player->core->setPeripheral(player->core, mPERIPH_GBA_LINK_PORT, NULL);
			}
		}
		for (i = session->players - 1; i >= 0; --i) {
			if (session->p[i].driver.coordinator) {
				GBASIOLockstepCoordinatorDetach(&session->coordinator, &session->p[i].driver);
			}
		}
		GBASIOLockstepCoordinatorDeinit(&session->coordinator);
	}
	for (i = 0; i < GL_MAX_PLAYERS; ++i) {
		struct GLPlayer* player = &session->p[i];
		if (player->core) {
			player->core->unloadROM(player->core);
			mCoreConfigDeinit(&player->core->config);
			player->core->deinit(player->core);
		}
		free(player->render);
		free(player->frame);
	}
	if (session->audioReady) {
		mAudioResamplerDeinit(&session->resampler);
		mAudioBufferDeinit(&session->audioOut);
	}
	free(session->rom);
	free(session);
}

int GLSessionPlayers(const struct GLSession* session) {
	return session->players;
}

/* ---------- Ejecución ---------- */

static void _runLinkedFrame(struct GLSession* session) {
	struct GLPlayer* primary = &session->p[0];
	primary->frameDone = false;
	unsigned spins = 0;
	while (true) {
		bool ran = false;
		if (!primary->frameDone && !primary->user.asleep) {
			primary->core->runLoop(primary->core);
			ran = true;
		}
		int i;
		for (i = 1; i < session->players; ++i) {
			struct GLPlayer* secondary = &session->p[i];
			if (!secondary->user.asleep) {
				secondary->core->runLoop(secondary->core);
				ran = true;
			}
		}

		if (primary->frameDone) {
			/* La consola 1 terminó su cuadro: dejamos que las demás la alcancen. */
			bool othersWaiting = true;
			for (i = 1; i < session->players; ++i) {
				othersWaiting = othersWaiting && session->p[i].user.asleep;
			}
			if (othersWaiting) {
				break;
			}
		}
		if (!ran) {
			if (primary->frameDone) {
				break;
			}
			/* Todas dormidas a mitad de cuadro: no debería pasar. Se despiertan todas
			 * (de forma determinista) para no colgar el juego. */
			++session->linkStalls;
			LOGW("Cable link bloqueado; despertando consolas (vez %u)", session->linkStalls);
			for (i = 0; i < session->players; ++i) {
				session->p[i].user.asleep = false;
			}
		}
		if (++spins > GL_MAX_SPINS) {
			++session->linkStalls;
			LOGE("Cable link: demasiadas vueltas en un cuadro");
			break;
		}
	}
}

void GLSessionRunFrame(struct GLSession* session, const uint16_t keys[GL_MAX_PLAYERS]) {
	int i;
	for (i = 0; i < session->players; ++i) {
		session->p[i].core->setKeys(session->p[i].core, keys[i] & 0x3FF);
	}
	if (session->linked) {
		_runLinkedFrame(session);
	} else {
		session->p[0].core->runFrame(session->p[0].core);
	}

	/* Audio: se remuestrea la consola que se escucha y se descarta el resto. */
	for (i = 0; i < session->players; ++i) {
		struct mCore* core = session->p[i].core;
		struct mAudioBuffer* buffer = core->getAudioBuffer(core);
		if (i == session->audioPlayer) {
			mAudioResamplerSetSource(&session->resampler, buffer, core->audioSampleRate(core), true);
			mAudioResamplerProcess(&session->resampler);
		} else {
			mAudioBufferClear(buffer);
		}
	}
}

uint32_t GLSessionFrameCount(const struct GLSession* session) {
	return session->p[0].core->frameCounter(session->p[0].core);
}

const uint32_t* GLSessionVideo(const struct GLSession* session, int player) {
	if (player < 0 || player >= session->players) {
		player = 0;
	}
	return session->p[player].frame;
}

void GLSessionSetAudioPlayer(struct GLSession* session, int player) {
	if (player < 0 || player >= session->players) {
		player = 0;
	}
	if (player != session->audioPlayer) {
		session->audioPlayer = player;
		mAudioBufferClear(&session->audioOut);
	}
}

size_t GLSessionReadAudio(struct GLSession* session, int16_t* out, size_t maxFrames) {
	return mAudioBufferRead(&session->audioOut, out, maxFrames);
}

uint32_t GLSessionHash(struct GLSession* session) {
	uint32_t hash = 0;
	int i;
	for (i = 0; i < session->players; ++i) {
		struct mCore* core = session->p[i].core;
		size_t size = 0;
		const void* ewram = core->getMemoryBlock(core, GBA_REGION_EWRAM, &size);
		if (ewram && size) {
			hash = crc32(hash, ewram, size);
		}
		const void* iwram = core->getMemoryBlock(core, GBA_REGION_IWRAM, &size);
		if (iwram && size) {
			hash = crc32(hash, iwram, size);
		}
		uint32_t frame = core->frameCounter(core);
		hash = crc32(hash, &frame, sizeof(frame));
	}
	return hash;
}

size_t GLSessionCloneSave(struct GLSession* session, int player, void** out) {
	*out = NULL;
	if (player < 0 || player >= session->players) {
		return 0;
	}
	struct mCore* core = session->p[player].core;
	return core->savedataClone(core, out);
}

size_t GLSessionStateSize(struct GLSession* session) {
	return session->p[0].core->stateSize(session->p[0].core);
}

bool GLSessionSaveState(struct GLSession* session, void* out) {
	if (session->linked) {
		return false;
	}
	return session->p[0].core->saveState(session->p[0].core, out);
}

bool GLSessionLoadState(struct GLSession* session, const void* in, size_t size) {
	if (session->linked || size != GLSessionStateSize(session)) {
		return false;
	}
	return session->p[0].core->loadState(session->p[0].core, in);
}

void GLSessionGameTitle(struct GLSession* session, char out[32]) {
	struct mGameInfo info;
	memset(&info, 0, sizeof(info));
	session->p[0].core->getGameInfo(session->p[0].core, &info);
	snprintf(out, 32, "%s", info.title);
	size_t len = strlen(out);
	while (len && out[len - 1] == ' ') {
		out[--len] = '\0';
	}
}

unsigned GLSessionLinkStalls(const struct GLSession* session) {
	return session->linkStalls;
}
