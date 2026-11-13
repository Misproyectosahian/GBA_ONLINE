"""Pruebas del relay. Ejecutar:  .venv/Scripts/python test_relay.py"""
import json

from fastapi.testclient import TestClient

from main import app, rooms


def recv(ws):
    return json.loads(ws.receive_text())


def test_health():
    client = TestClient(app)
    r = client.get("/")
    assert r.status_code == 200 and r.json()["ok"] is True


def test_hi():
    client = TestClient(app)
    with client.websocket_connect("/ws") as ws:
        ws.send_text(json.dumps({"t": "hi"}))
        assert recv(ws)["t"] == "hi"


def test_room_flow():
    client = TestClient(app)
    with client.websocket_connect("/ws") as host:
        host.send_text(json.dumps({"t": "create", "name": "Ana"}))
        created = recv(host)
        assert created["t"] == "created" and len(created["code"]) == 4
        code = created["code"]

        with client.websocket_connect("/ws") as guest:
            guest.send_text(json.dumps({"t": "join", "code": code, "name": "Beto"}))
            joined = recv(guest)
            assert joined == {"t": "joined", "code": code, "peer": "Ana"}
            assert recv(host) == {"t": "peer_joined", "peer": "Beto"}

            # Reenvío en ambos sentidos, sin modificar el contenido.
            msg = json.dumps({"t": "in", "f": 7, "k": 1})
            host.send_text(msg)
            assert guest.receive_text() == msg
            chat = json.dumps({"t": "chat", "m": "¡hola! 💬"}, ensure_ascii=False)
            guest.send_text(chat)
            assert host.receive_text() == chat

            # Un tercero no puede entrar a una sala llena.
            with client.websocket_connect("/ws") as third:
                third.send_text(json.dumps({"t": "join", "code": code, "name": "X"}))
                assert recv(third)["t"] == "error"

        # Al irse el invitado, el anfitrión se entera.
        assert recv(host) == {"t": "peer_left"}
    assert code not in rooms


def test_bad_code():
    client = TestClient(app)
    with client.websocket_connect("/ws") as ws:
        ws.send_text(json.dumps({"t": "join", "code": "0000", "name": "X"}))
        assert recv(ws)["t"] == "error"


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("OK ", name)
    print("Todas las pruebas pasaron")
