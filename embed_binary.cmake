# Reads a binary file and writes a C source file with its contents as a byte array
# Usage: cmake -DINPUT=file.bin -DOUTPUT=output.c -DVARNAME=var_name -P embed_binary.cmake

file(READ "${INPUT}" binary HEX)
string(REGEX REPLACE "(..)" "0x\\1, " hex_array "${binary}")
file(WRITE "${OUTPUT}"
    "#include <stddef.h>\n"
    "const unsigned char ${VARNAME}[] = { ${hex_array}};\n"
    "const size_t ${VARNAME}_size = sizeof(${VARNAME});\n"
)
