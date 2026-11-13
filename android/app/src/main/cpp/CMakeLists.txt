cmake_minimum_required(VERSION 3.22.1)
project(gbalink C)

# El emulador necesita optimizaciones incluso en builds de debug; con -O0 va lentísimo.
# NDEBUG también en debug: las aserciones internas de mGBA abortarían la app.
set(CMAKE_C_FLAGS_DEBUG "-O2 -g -DNDEBUG")
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_C_FLAGS_RELWITHDEBINFO "-O3 -g -DNDEBUG")

# mGBA como librería estática: solo el núcleo de GBA, sin frontends ni dependencias.
set(LIBMGBA_ONLY ON)
set(M_CORE_GBA ON)
set(M_CORE_GB OFF)
add_subdirectory(third_party/mgba EXCLUDE_FROM_ALL)

# Nuestro código incluye los headers internos de mGBA, así que debe compilarse con las
# mismas opciones (M_CORE_GBA, USE_PTHREADS, ...); si no, las estructuras no coinciden.
get_target_property(MGBA_TARGET_DEFINES mgba COMPILE_DEFINITIONS)
get_directory_property(MGBA_DIR_DEFINES DIRECTORY third_party/mgba COMPILE_DEFINITIONS)
set(MGBA_DEFINES ${MGBA_TARGET_DEFINES} ${MGBA_DIR_DEFINES})

add_library(gbalink SHARED
    gbalink_session.c
    gbalink_jni.c
)
target_compile_definitions(gbalink PRIVATE ${MGBA_DEFINES})
target_link_libraries(gbalink PRIVATE mgba android log)

# Herramienta de prueba de línea de comandos (determinismo + cable link).
# Solo se compila si se pide explícitamente: cmake --build ... --target gbalink_selftest
add_executable(gbalink_selftest EXCLUDE_FROM_ALL selftest.c gbalink_session.c)
target_compile_definitions(gbalink_selftest PRIVATE ${MGBA_DEFINES})
target_link_libraries(gbalink_selftest PRIVATE mgba log)
