"""Rellena la cabecera de cartucho GBA: título, código, byte fijo 0x96 y checksum."""
import sys

path = sys.argv[1]
rom = bytearray(open(path, "rb").read())
rom[0xA0:0xAC] = b"LINKTEST".ljust(12, b"\0")
rom[0xAC:0xB0] = b"LTST"
rom[0xB0:0xB2] = b"00"
rom[0xB2] = 0x96
chk = 0
for b in rom[0xA0:0xBD]:
    chk = (chk - b) & 0xFF
rom[0xBD] = (chk - 0x19) & 0xFF
while len(rom) % 1024:
    rom.append(0)
open(path, "wb").write(rom)
print(f"{path}: {len(rom)} bytes")
