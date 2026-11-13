"""
Jugador falso para probar una sala sin un segundo teléfono.

Se une a una sala como invitado (jugador 2), sigue el mismo protocolo que la app y
"presiona" botones con un guion fijo:
    cuadros  300-1200: A      -> con la ROM de prueba linktest.gba, la pantalla de la GBA 1 se pone verde
    cuadros 1500-2400: B      -> se pone roja
No emula nada: solo intercambia botones a 60 por segundo como lo haría la app.

Uso (con el venv del servidor):
    python tools/fake_peer.py ws://127.0.0.1:8000/ws 1234 01B226F5 [cuadros]
                              ^servidor               ^sala ^CRC de la ROM (se ve en la app)
"""
import asyncio
import json
import sys
import time

import websockets

FRAME = 1 / 59.7275
KEY_A, KEY_B = 1, 2


def keys_for(frame: int) -> int:
    if 300 <= frame < 1200:
        return KEY_A
    if 1500 <= frame < 2400:
        return KEY_B
    return 0


async def main(url: str, code: str, rom_crc: str, total: int) -> None:
    async with websockets.connect(url, max_size=8 * 1024 * 1024) as ws:
        await ws.send(json.dumps({"t": "join", "code": code, "name": "Bot", "v": 1}))
        remote: dict[int, int] = {}
        start = asyncio.get_running_loop().create_future()
        chats: list[str] = []

        async def reader():
            async for raw in ws:
                msg = json.loads(raw)
                t = msg.get("t")
                if t == "in":
                    remote[msg["f"]] = msg["k"]
                elif t == "ping":
                    await ws.send(json.dumps({"t": "pong", "id": msg.get("id"), "ts": msg.get("ts")}))
                elif t == "joined":
                    print(f"Unido a la sala {code} con {msg.get('peer')}")
                    await ws.send(json.dumps({"t": "hello", "v": 1, "rom": rom_crc, "save": "", "name": "Bot"}))
                elif t == "hello":
                    print(f"hello del anfitrión: rom={msg.get('rom')} save={len(msg.get('save', ''))} bytes b64")
                elif t == "start":
                    print(f"start: retraso={msg['delay']} cuadros")
                    if not start.done():
                        start.set_result(msg["delay"])
                elif t == "chat":
                    chats.append(msg.get("m", ""))
                    print(f"chat recibido: {msg.get('m')}")
                elif t in ("peer_left", "bye", "error"):
                    print(f"fin: {msg}")
                    if not start.done():
                        start.set_exception(RuntimeError(str(msg)))
                    return

        reader_task = asyncio.create_task(reader())
        delay = await asyncio.wait_for(start, timeout=60)
        for f in range(delay):
            remote.setdefault(f, 0)
        sent_up_to = delay - 1
        frame = 0
        stalls = 0
        next_tick = time.perf_counter()
        await ws.send(json.dumps({"t": "chat", "m": "Hola desde el jugador falso 🤖"}))
        while frame < total and not reader_task.done():
            while sent_up_to < frame + delay:
                sent_up_to += 1
                await ws.send(json.dumps({"t": "in", "f": sent_up_to, "k": keys_for(sent_up_to)}))
            if frame in remote:
                remote.pop(frame)
                frame += 1
                if frame % 300 == 0:
                    print(f"cuadro {frame}  (esperas: {stalls})", flush=True)
            else:
                stalls += 1
            next_tick += FRAME
            await asyncio.sleep(max(0.0, next_tick - time.perf_counter()))
            if time.perf_counter() - next_tick > 0.2:
                next_tick = time.perf_counter()
        print(f"Terminado: {frame} cuadros, {stalls} tics esperando al otro, chats recibidos: {chats}")
        await ws.send(json.dumps({"t": "bye"}))
        reader_task.cancel()


if __name__ == "__main__":
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(2)
    asyncio.run(main(sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 3000))
