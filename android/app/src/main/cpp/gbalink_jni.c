/*
 * Puente JNI entre Kotlin (com.gbalink.online.NativeBridge) y la sesión en C.
 * Todas las funciones con handle se llaman desde el hilo del emulador, salvo
 * setSurface, que llega desde el hilo de la interfaz (por eso el mutex).
 */
#include "gbalink_session.h"

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#define JNI_FN(name) Java_com_gbalink_online_NativeBridge_##name

static pthread_mutex_t gWindowLock = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow* gWindow = NULL;
static int gScale = 1;

static struct GLSession* _session(jlong handle) {
	return (struct GLSession*) (intptr_t) handle;
}

static void* _copyArray(JNIEnv* env, jbyteArray array, size_t* size) {
	*size = 0;
	if (!array) {
		return NULL;
	}
	jsize length = (*env)->GetArrayLength(env, array);
	if (length <= 0) {
		return NULL;
	}
	void* data = malloc((size_t) length);
	if (!data) {
		return NULL;
	}
	(*env)->GetByteArrayRegion(env, array, 0, length, data);
	*size = (size_t) length;
	return data;
}

static jbyteArray _toArray(JNIEnv* env, const void* data, size_t size) {
	if (!data || !size) {
		return NULL;
	}
	jbyteArray array = (*env)->NewByteArray(env, (jsize) size);
	if (array) {
		(*env)->SetByteArrayRegion(env, array, 0, (jsize) size, data);
	}
	return array;
}

JNIEXPORT jlong JNICALL JNI_FN(create)(JNIEnv* env, jclass clazz, jstring romPath, jint players,
                                       jbyteArray save0, jbyteArray save1, jlong rtcEpochMs,
                                       jint sampleRate, jobjectArray errorOut) {
	(void) clazz;
	struct GLSessionConfig config;
	memset(&config, 0, sizeof(config));
	const char* path = (*env)->GetStringUTFChars(env, romPath, NULL);
	config.romPath = path;
	config.players = players;
	void* saves[2];
	saves[0] = _copyArray(env, save0, &config.saveSize[0]);
	saves[1] = _copyArray(env, save1, &config.saveSize[1]);
	config.save[0] = saves[0];
	config.save[1] = saves[1];
	config.rtcEpochMs = rtcEpochMs;
	config.sampleRate = (unsigned) sampleRate;

	char error[256] = {0};
	struct GLSession* session = GLSessionCreate(&config, error, sizeof(error));
	(*env)->ReleaseStringUTFChars(env, romPath, path);
	free(saves[0]);
	free(saves[1]);

	if (!session && errorOut && (*env)->GetArrayLength(env, errorOut) > 0) {
		jstring message = (*env)->NewStringUTF(env, error[0] ? error : "Error desconocido");
		(*env)->SetObjectArrayElement(env, errorOut, 0, message);
	}
	return (jlong) (intptr_t) session;
}

JNIEXPORT void JNICALL JNI_FN(destroy)(JNIEnv* env, jclass clazz, jlong handle) {
	(void) env;
	(void) clazz;
	GLSessionDestroy(_session(handle));
}

JNIEXPORT void JNICALL JNI_FN(runFrame)(JNIEnv* env, jclass clazz, jlong handle, jint keys0, jint keys1) {
	(void) env;
	(void) clazz;
	uint16_t keys[GL_MAX_PLAYERS] = { (uint16_t) keys0, (uint16_t) keys1 };
	GLSessionRunFrame(_session(handle), keys);
}

JNIEXPORT jint JNICALL JNI_FN(frameCount)(JNIEnv* env, jclass clazz, jlong handle) {
	(void) env;
	(void) clazz;
	return (jint) GLSessionFrameCount(_session(handle));
}

JNIEXPORT void JNICALL JNI_FN(setAudioPlayer)(JNIEnv* env, jclass clazz, jlong handle, jint player) {
	(void) env;
	(void) clazz;
	GLSessionSetAudioPlayer(_session(handle), player);
}

JNIEXPORT jint JNICALL JNI_FN(readAudio)(JNIEnv* env, jclass clazz, jlong handle, jshortArray out) {
	(void) clazz;
	/* Copia a un búfer propio y después al arreglo de Java (sin "critical", que frena el GC). */
	static int16_t samples[16384];
	jsize length = (*env)->GetArrayLength(env, out);
	if (length > (jsize) (sizeof(samples) / sizeof(*samples))) {
		length = sizeof(samples) / sizeof(*samples);
	}
	size_t frames = GLSessionReadAudio(_session(handle), samples, (size_t) length / 2);
	if (frames) {
		(*env)->SetShortArrayRegion(env, out, 0, (jsize) (frames * 2), samples);
	}
	return (jint) (frames * 2);
}

JNIEXPORT jint JNICALL JNI_FN(stateHash)(JNIEnv* env, jclass clazz, jlong handle) {
	(void) env;
	(void) clazz;
	return (jint) GLSessionHash(_session(handle));
}

JNIEXPORT jbyteArray JNICALL JNI_FN(cloneSave)(JNIEnv* env, jclass clazz, jlong handle, jint player) {
	(void) clazz;
	void* data = NULL;
	size_t size = GLSessionCloneSave(_session(handle), player, &data);
	jbyteArray array = _toArray(env, data, size);
	free(data);
	return array;
}

JNIEXPORT jbyteArray JNICALL JNI_FN(saveState)(JNIEnv* env, jclass clazz, jlong handle) {
	(void) clazz;
	struct GLSession* session = _session(handle);
	size_t size = GLSessionStateSize(session);
	void* data = malloc(size);
	if (!data) {
		return NULL;
	}
	jbyteArray array = NULL;
	if (GLSessionSaveState(session, data)) {
		array = _toArray(env, data, size);
	}
	free(data);
	return array;
}

JNIEXPORT jboolean JNICALL JNI_FN(loadState)(JNIEnv* env, jclass clazz, jlong handle, jbyteArray state) {
	(void) clazz;
	size_t size = 0;
	void* data = _copyArray(env, state, &size);
	if (!data) {
		return JNI_FALSE;
	}
	bool ok = GLSessionLoadState(_session(handle), data, size);
	free(data);
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL JNI_FN(gameTitle)(JNIEnv* env, jclass clazz, jlong handle) {
	(void) clazz;
	char title[32];
	GLSessionGameTitle(_session(handle), title);
	return (*env)->NewStringUTF(env, title);
}

JNIEXPORT jint JNICALL JNI_FN(linkStalls)(JNIEnv* env, jclass clazz, jlong handle) {
	(void) env;
	(void) clazz;
	return (jint) GLSessionLinkStalls(_session(handle));
}

/* ---------- Pantalla ---------- */

JNIEXPORT void JNICALL JNI_FN(setSurface)(JNIEnv* env, jclass clazz, jobject surface, jint scale) {
	(void) clazz;
	pthread_mutex_lock(&gWindowLock);
	if (gWindow) {
		ANativeWindow_release(gWindow);
		gWindow = NULL;
	}
	if (surface) {
		gWindow = ANativeWindow_fromSurface(env, surface);
		if (scale < 1) {
			scale = 1;
		}
		if (scale > 6) {
			scale = 6;
		}
		gScale = scale;
		if (gWindow) {
			ANativeWindow_setBuffersGeometry(gWindow, GL_WIDTH * gScale, GL_HEIGHT * gScale, WINDOW_FORMAT_RGBX_8888);
		}
	}
	pthread_mutex_unlock(&gWindowLock);
}

/* Copia el cuadro al Surface ampliándolo con "vecino más cercano" para que los píxeles
 * se vean nítidos; el compositor de Android solo hace el ajuste final de tamaño. */
static void _blit(const uint32_t* src, ANativeWindow_Buffer* dst, int scale) {
	int y;
	uint32_t* out = dst->bits;
	size_t stride = (size_t) dst->stride;
	for (y = 0; y < GL_HEIGHT; ++y) {
		const uint32_t* line = &src[y * GL_WIDTH];
		uint32_t* row = &out[(size_t) y * scale * stride];
		if (scale == 1) {
			memcpy(row, line, GL_WIDTH * sizeof(uint32_t));
			continue;
		}
		int x;
		uint32_t* p = row;
		for (x = 0; x < GL_WIDTH; ++x) {
			uint32_t color = line[x] | 0xFF000000;
			int s;
			for (s = 0; s < scale; ++s) {
				*p++ = color;
			}
		}
		int r;
		for (r = 1; r < scale; ++r) {
			memcpy(&row[(size_t) r * stride], row, (size_t) GL_WIDTH * scale * sizeof(uint32_t));
		}
	}
}

JNIEXPORT void JNICALL JNI_FN(present)(JNIEnv* env, jclass clazz, jlong handle, jint player) {
	(void) env;
	(void) clazz;
	const uint32_t* frame = GLSessionVideo(_session(handle), player);
	/* Se toma una referencia propia y se suelta el mutex antes de dibujar: así el hilo de
	 * la interfaz (setSurface) nunca espera a que el compositor libere un búfer. */
	pthread_mutex_lock(&gWindowLock);
	ANativeWindow* window = gWindow;
	int scale = gScale;
	if (window) {
		ANativeWindow_acquire(window);
	}
	pthread_mutex_unlock(&gWindowLock);
	if (!window) {
		return;
	}
	ANativeWindow_Buffer buffer;
	if (ANativeWindow_lock(window, &buffer, NULL) == 0) {
		if (buffer.width >= GL_WIDTH * scale && buffer.height >= GL_HEIGHT * scale) {
			_blit(frame, &buffer, scale);
		}
		ANativeWindow_unlockAndPost(window);
	}
	ANativeWindow_release(window);
}
