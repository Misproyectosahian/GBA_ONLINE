#!/bin/sh
# Compila la ROM de prueba con el clang del NDK de Android (no hace falta devkitARM).
set -e
cd "$(dirname "$0")"
NDK="${ANDROID_NDK:-$LOCALAPPDATA/Android/Sdk/ndk/28.2.13676358}"
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
FLAGS="--target=armv4t-none-eabi -mcpu=arm7tdmi -marm -O2 -ffreestanding -nostdlib -fno-builtin"
"$BIN/clang" $FLAGS -c crt0.s -o crt0.o
"$BIN/clang" $FLAGS -c main.c -o main.o
"$BIN/ld.lld" -T link.ld crt0.o main.o -o linktest.elf
"$BIN/llvm-objcopy" -O binary linktest.elf linktest.gba
python fix_header.py linktest.gba
