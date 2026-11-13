"""
Servidor relay para GBA Link Online.

Hace solo dos cosas:
  1. Salas con código de 4 números ("create" / "join").
  2. Reenviar al otro jugador todo lo que llegue (botones, chat, partidas, etc.).

No emula nada ni guarda nada: si se reinicia, solo se pierden las salas abiertas.

Ejecutar en tu PC:   uvicorn main:app --host 0.0.0.0 --port 8000
Dirección para la app:   ws://IP-DE-TU-PC:8000/ws   (o wss://tu-dominio/ws con HTTPS)
"""

import asyncio
import json
import logging
import secrets
import time

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

PROTOCOL = 1
MAX_PLAYERS = 2
MAX_ROOMS = 500
MAX_TEXT = 2 * 1024 * 1024  # una partida guardada en base64 entra de sobra

log = logging.getLogger("relay")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

app = FastAPI(title="GBA Link Online relay")


class Peer:
    def __init__(self, ws: WebSocket, name: str):
        self.ws = ws
        self.name = name
        self._lock = asyncio.Lock()

    async def send_text(self, text: str) -> None:
        async with self._lock:
            await self.ws.send_text(text)

    async def send(self, **msg) -> None:
        await self.send_text(json.dumps(msg, ensure_ascii=False))


class Room:
    def __init__(self, code: str):
        self.code = code
        self.peers: list[Peer | None] = [None] * MAX_PLAYERS
        self.created = time.time()

    def other(self, slot: int) -> Peer | None:
        return self.peers[1 - slot]

    def empty(self) -> bool:
        return all(p is None for p in self.peers)


rooms: dict[str, Room] = {}


def new_code() -> str:
    for _ in range(200):
        code = f"{secrets.randbelow(10000):04d}"
        if code not in rooms:
            return code
    raise RuntimeError("No hay códigos libres")


@app.get("/")
async def health():
    return {"ok": True, "service": "gba-link-relay", "protocol": PROTOCOL, "rooms": len(rooms)}


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    await ws.accept()
    room: Room | None = None
    slot = -1
    me: Peer | None = None
    try:
        while True:
            text = await ws.receive_text()
            if len(text) > MAX_TEXT:
                continue

            if room is not None:
                # Camino rápido: ya estamos en una sala, reenviar sin interpretar.
                other = room.other(slot)
                if other is not None:
                    try:
                        await other.send_text(text)
                    except Exception:
                        pass
                continue

            try:
                msg = json.loads(text)
            except ValueError:
                continue
            kind = msg.get("t")
            name = str(msg.get("name") or "Jugador")[:24]

            if kind == "hi":
                await ws.send_text(json.dumps({"t": "hi", "rooms": len(rooms), "protocol": PROTOCOL}))

            elif kind == "create":
                if len(rooms) >= MAX_ROOMS:
                    await ws.send_text(json.dumps({"t": "error", "msg": "El servidor está lleno, probá en un rato."}))
                    continue
                code = new_code()
                room = Room(code)
                rooms[code] = room
                slot = 0
                me = Peer(ws, name)
                room.peers[0] = me
                log.info("Sala %s creada por %s", code, name)
                await me.send(t="created", code=code)

            elif kind == "join":
                code = str(msg.get("code") or "").strip()
                target = rooms.get(code)
                if target is None:
                    await ws.send_text(json.dumps({"t": "error", "msg": f"No existe la sala {code}. Revisá el código."}, ensure_ascii=False))
                    continue
                if target.peers[1] is not None or target.peers[0] is None:
                    await ws.send_text(json.dumps({"t": "error", "msg": f"La sala {code} ya está llena."}, ensure_ascii=False))
                    continue
                room = target
                slot = 1
                me = Peer(ws, name)
                room.peers[1] = me
                host = room.peers[0]
                log.info("%s se unió a la sala %s", name, code)
                # Primero se avisa al que entra y después al anfitrión, así el orden de
                # los mensajes "hello" queda bien en los dos lados.
                await me.send(t="joined", code=code, peer=host.name)
                await host.send(t="peer_joined", peer=name)

            else:
                await ws.send_text(json.dumps({"t": "error", "msg": "Primero hay que crear o unirse a una sala."}, ensure_ascii=False))

    except WebSocketDisconnect:
        pass
    except Exception as exc:  # noqa: BLE001
        log.warning("Conexión cerrada con error: %s", exc)
    finally:
        if room is not None and slot >= 0:
            room.peers[slot] = None
            other = room.other(slot)
            if other is not None:
                try:
                    await other.send(t="peer_left")
                except Exception:
                    pass
            if room.empty():
                rooms.pop(room.code, None)
                log.info("Sala %s cerrada", room.code)
