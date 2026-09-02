cmake_minimum_required(VERSION 3.10)
project(native C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_executable(memhelper memhelper.c)
target_link_libraries(memhelper log)

set(MEMHELPER_C "${CMAKE_CURRENT_BINARY_DIR}/memhelper_data.c")
add_custom_command(
    OUTPUT ${MEMHELPER_C}
    COMMAND ${CMAKE_COMMAND}
        -DINPUT="${CMAKE_CURRENT_BINARY_DIR}/memhelper"
        -DOUTPUT="${MEMHELPER_C}"
        -DVARNAME=memhelper_binary
        -P "${CMAKE_CURRENT_SOURCE_DIR}/embed_binary.cmake"
    DEPENDS memhelper
    COMMENT "Generating embedded memhelper data..."
)

add_library(native SHARED
    bridge.c
    memops.c
    hacks.c
    symsolve.c
    ${MEMHELPER_C}
)
target_include_directories(native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(native log m)
