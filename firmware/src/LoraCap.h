// Cap LoRa-1262 on a Cardputer-Adv: SX1262 on the EXT SPI bus and an
// ATGM336H GNSS receiver on the EXT UART. Absent hardware is a no-op.

#pragma once

#include "Board.h"

#include <stddef.h>
#include <stdint.h>

struct GpsFix {
  int32_t latE7;
  int32_t lonE7;
  uint8_t sats;
};

struct LoraDevice {
  uint32_t freqKhz;
  uint8_t sf;
  int16_t rssi;
  int8_t snr;
  uint8_t flags;  // bit0 LoRaWAN, bit1 private sync, bit2 join request
  uint32_t id;
};

// Call after M5Cardputer.begin(). Detects the cap, enables its antenna
// switch, and leaves the radio asleep so it does not drown the GNSS fix.
// Boards without the cap compile the empty inline versions.
#if SPECTRA_HAS_LORA
void loraCapBegin();

bool loraCapAttached();
bool loraCapReady();

void loraCapPoll();
bool loraCapFix(GpsFix &out);
// "55.75124,37.61842", or empty when there is no fresh fix.
void loraCapFormat(char *out, size_t outLen);

// Listens on EU868 and RU864 at SF7, SF9 and SF12. progress may be null.
// index is 1-based. heard is called for every packet, including repeats.
void loraCapScan(void (*progress)(uint32_t freqKhz, uint8_t sf, uint16_t index, uint16_t total),
                 void (*heard)(const LoraDevice &dev));
#else
inline void loraCapBegin() {}
inline bool loraCapAttached() { return false; }
inline bool loraCapReady() { return false; }
inline void loraCapPoll() {}
inline bool loraCapFix(GpsFix &) { return false; }
inline void loraCapFormat(char *out, size_t outLen) {
  if (outLen > 0) {
    out[0] = '\0';
  }
}
inline void loraCapScan(void (*)(uint32_t, uint8_t, uint16_t, uint16_t), void (*)(const LoraDevice &)) {}
#endif
