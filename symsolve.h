#pragma once
#include <stdint.h>
#include <stdbool.h>

bool sym_init(uint64_t base);
uint64_t sym_find(const char *name);
uint64_t sym_base(void);
uint64_t sym_bss_vaddr(void);
uint64_t sym_bss_size(void);
void sym_shutdown(void);
