// CRC-32 (ISO-HDLC / zlib): polynomial 0xEDB88320, initial value 0. Used to
// check an incoming firmware image. The CRC of the ASCII bytes "123456789" is
// 0xCBF43926. Header-only; the lookup table is built once on first use.

#pragma once

#include <stddef.h>
#include <stdint.h>

namespace crc32detail {
inline const uint32_t *table() {
  static uint32_t table[256];
  static bool ready = false;
  if (!ready) {
    for (uint32_t i = 0; i < 256; ++i) {
      uint32_t crc = i;
      for (int bit = 0; bit < 8; ++bit) {
        crc = (crc & 1) ? (0xEDB88320u ^ (crc >> 1)) : (crc >> 1);
      }
      table[i] = crc;
    }
    ready = true;
  }
  return table;
}
}  // namespace crc32detail

// Continues a running CRC. Seed the first call with 0; feed the previous
// return value to continue over more bytes.
inline uint32_t crc32Update(uint32_t crc, const uint8_t *data, size_t length) {
  const uint32_t *table = crc32detail::table();
  crc = ~crc;
  for (size_t i = 0; i < length; ++i) {
    crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
  }
  return ~crc;
}

// Confirms the routine matches the known test vector before an update is offered.
inline bool otaCrcSelfCheck() {
  const uint8_t sample[] = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};
  return crc32Update(0, sample, sizeof(sample)) == 0xCBF43926u;
}
