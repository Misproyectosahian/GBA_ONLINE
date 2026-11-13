# GBA Link Online

App Android para jugar juegos de GBA de a dos **por Internet, como si estuvieran conectados con el cable link**. La hicimos para Kirby & the Amazing Mirror, pero sirve para cualquier juego que use el cable en modo multijugador.

## Cómo funciona

Mandar por Internet los datos del cable no sirve: el juego espera la respuesta del otro en casi cada cuadro, y con 50–100 ms de ping corre a un tercio de la velocidad.

Por eso **cada teléfono emula las dos GBA**, conectadas con un cable virtual en su propia memoria, y por Internet solo viajan **los botones** de cada uno (unos 60 mensajes cortos por segundo). Como los dos teléfonos reciben exactamente los mismos botones en el mismo cuadro, calculan exactamente el mismo juego. Cada uno ve la pantalla de su propia GBA.

```
 Tu teléfono                    Servidor relay                 Teléfono de ella
┌──────────────────┐                                       ┌──────────────────┐
│ GBA 1 ◄─cable─► GBA 2 │ ◄── botones ──► (Render) ◄── botones ──► │ GBA 1 ◄─cable─► GBA 2 │
│ (ves la 1)       │                                       │ (ve la 2)        │
└──────────────────┘                                       └──────────────────┘
```

- **Retraso de entrada:** lo que presionás se aplica unos cuadros después (se calcula solo con el ping) para que los botones del otro alcancen a llegar.
- **Detección de desincronización:** una vez por segundo los teléfonos comparan una huella del estado; si alguna vez no coincide, la app avisa.
- **Partidas guardadas:** al crear la sala cada uno manda su `.sav` al otro (cada GBA usa la partida de su dueño), y cada teléfono guarda solo la suya. Antes de cada partida en línea se hace una copia `.sav.bak`.

## Estructura

| Carpeta | Qué hay |
|---|---|
| `android/` | La app (Kotlin + C). Proyecto de Android Studio. |
| `android/app/src/main/cpp/` | Motor: `gbalink_session.c` (las dos GBA enlazadas), `gbalink_jni.c` (puente con Kotlin), `selftest.c` (prueba de línea de comandos). |
| `android/app/src/main/cpp/third_party/mgba/` | Núcleo de emulación [mGBA](https://mgba.io) (MPL 2.0), commit `d58ea8a`. No está modificado. |
| `server/` | Servidor relay en FastAPI: salas con código de 4 números que reenvían mensajes. |
| `tools/linktest-rom/` | ROM de prueba propia (1 KB) para verificar el cable: la pantalla cambia de color según lo que presiona la otra GBA. |
| `tools/fake_peer.py` | "Jugador falso" que se une a una sala, para probar sin un segundo teléfono. |
| `render.yaml` | Configuración para publicar el servidor gratis en Render. |

## 1. Compilar e instalar la app

1. Abrí la carpeta `android/` en Android Studio (necesita el NDK 28.2 y CMake 3.22.1; Android Studio los ofrece instalar si faltan).
2. Conectá el teléfono y tocá **Run**. La primera compilación tarda unos minutos porque compila mGBA.
3. Para pasarle la app a tu novia: **Build → Build APK(s)** y mandale `android/app/build/outputs/apk/debug/app-debug.apk`. Los dos tienen que tener **el mismo APK**.

> En teléfonos Xiaomi/POCO, para instalar desde la PC hay que activar **Opciones de desarrollador → Instalar vía USB**.

## 2. Publicar el servidor en Render (gratis)

1. Subí esta carpeta a un repositorio de GitHub (puede ser privado). La carpeta ya es un repositorio git con mGBA registrado como submódulo. Con GitHub Desktop: *File → Add local repository* → elegí la carpeta `GBA Online` → escribí un mensaje → *Commit to main* → *Publish repository*.
   - Para clonarlo en otra PC: `git clone --recursive <url>` (así baja también mGBA).
2. Entrá a [render.com](https://render.com), creá una cuenta y conectá tu GitHub.
3. **New → Blueprint** → elegí el repositorio. Render lee `render.yaml` y crea el servicio `gba-link-relay`.
4. Cuando termine, Render te da una dirección tipo `https://gba-link-relay-xxxx.onrender.com`. Abrila en el navegador: tiene que decir `{"ok":true,...}`.
5. En la app, en **Servidor**, escribí la misma dirección cambiando `https://` por `wss://` y agregando `/ws` al final:
   `wss://gba-link-relay-xxxx.onrender.com/ws` → **Probar conexión**.

**Ojo con el plan gratis:** si nadie lo usa durante 15 minutos, Render lo duerme. La primera conexión después de eso tarda hasta un minuto (la app espera sola). Un truco: tocá *Probar conexión* un minuto antes de jugar.

### Alternativa: servidor en tu PC

```
cd server
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
.venv\Scripts\python -m uvicorn main:app --host 0.0.0.0 --port 8765
```

- En la misma red WiFi: `ws://IP-DE-TU-PC:8765/ws` (puede que tengas que permitir el puerto en el firewall de Windows).
- Desde otra casa necesitás exponerlo, por ejemplo con `cloudflared tunnel --url http://localhost:8765` (te da una dirección `https://...trycloudflare.com` → en la app `wss://...trycloudflare.com/ws`).

## 3. Jugar

1. Los dos: **Elegir ROM** → el mismo archivo `.gba` (sacado de tu propio cartucho). La app muestra el CRC; tiene que ser idéntico en los dos teléfonos.
2. Si ya tenés una partida en otro emulador (por ejemplo My Boy!), usá **Importar .sav**.
3. Escriban sus nombres. Uno toca **Crear sala** y le pasa el código de 4 números (botón *Compartir código*); el otro lo escribe y toca **Unirse**.
4. Quien crea la sala es el **jugador 1**; quien se une, el **jugador 2**.
5. **Chat:** botón 💬 arriba a la derecha. Cuando llega un mensaje con el chat cerrado, se muestra un globito unos segundos y un contador.
6. La línea de estado debajo de la pantalla muestra el ping y el retraso. Si dice *Esperando a…*, el otro teléfono se trabó o perdió señal.

Si uno sale de la app (por ejemplo para contestar WhatsApp), el juego sigue sin sonido para no congelar al otro. Si se corta la conexión, cada uno conserva su partida guardada.

### Ajustes

- **Retraso de entrada:** *Automático* casi siempre está bien. Si ven pausas seguidas de *Esperando a…*, súbanlo 1 o 2 cuadros (en el teléfono de quien crea la sala, que es el que lo decide).
- **Controles físicos:** se puede usar un control Bluetooth (A, B, L1, R1, Start, Select y la cruceta).

## Modos sin conexión

- **Jugar solo:** el juego normal, con estado rápido (menú ☰ → guardar/cargar estado).
- **Probar 2 jugadores en este teléfono:** las dos GBA enlazadas en tu teléfono. Con ☰ → *Ver y controlar la GBA 2* cambiás de consola. Sirve para comprobar que un juego detecta el multijugador antes de probarlo en línea.

## Pruebas

**Servidor:**
```
cd server
.venv\Scripts\pip install -r requirements-dev.txt
.venv\Scripts\python test_relay.py
```

**Cable link y determinismo (en un teléfono por USB):** compila `gbalink_selftest`, lo copia al teléfono y simula dos teléfonos jugando con los mismos botones; verifica que el estado sea idéntico en cada cuadro.
```
# desde android/app/.cxx/<variante>/<hash>/arm64-v8a, o con cmake + el NDK:
ninja gbalink_selftest
adb push gbalink_selftest tools/linktest-rom/linktest.gba /data/local/tmp/
adb shell "cd /data/local/tmp && chmod 755 gbalink_selftest && ./gbalink_selftest linktest.gba 700 '' linktest"
# con tu ROM: ./gbalink_selftest kirby.gba 5000      (botones aleatorios)
```

**Sala en línea sin segundo teléfono:** creá la sala en la app y ejecutá
```
server\.venv\Scripts\python tools\fake_peer.py wss://tu-servidor/ws CODIGO CRC-DE-LA-ROM
```

## Licencias

- mGBA © Jeffrey Pfau y colaboradores, licencia [MPL 2.0](android/app/src/main/cpp/third_party/mgba/LICENSE). Si alguna vez distribuís la app públicamente, tenés que ofrecer también el código fuente de mGBA (este repositorio ya lo incluye).
- Usá solo ROMs sacadas de tus propios cartuchos. Este repositorio no incluye ningún juego comercial.
