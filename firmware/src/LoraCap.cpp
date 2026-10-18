#include "LoraCap.h"

#include <M5Unified.h>
#include <RadioLib.h>
#include <SPI.h>

#include <cmath>
#include <cstdlib>
#include <cstring>

#include "utility/PI4IOE5V6408_Class.hpp"

// Cardputer-Adv EXT 2.54-14P, as wired by Cap LoRa-1262.
static constexpr int kNss = 5;
static constexpr int kIrq = 4;
static constexpr int kRst = 3;
static constexpr int kBusy = 6;
static constexpr int kSck = 40;
static constexpr int kMosi = 14;
static constexpr int kMiso = 39;
static constexpr int kGpsRx = 15;  // GNSS TX
static constexpr int kGpsTx = 13;  // GNSS RX

// EU868 and RU864 uplink channels, in kHz. The cap's SX1262 tunes the whole
// 864–870 span; the antenna is centred on 868–923.
static constexpr uint32_t kFreqKhz[] = {
    864100, 864300, 864500, 864700, 864900, 867100, 867300, 867500,
    867700, 867900, 868100, 868300, 868500, 868900, 869100, 869525,
};

// SX126x names are the opposite of the SX127x bytes: 0x34 is the LoRaWAN
// public word (0x3444) and 0x12 is a private network (0x1424).

// Created in loraCapBegin, after SPI is on the cap pins. A default Module
// would call SPI.begin() on the ESP32-S3 FSPI defaults, and MOSI there is
// GPIO 11 — the Cardputer-Adv keyboard interrupt. The keys then go silent.
static Module *radioModule = nullptr;
static SX1262 *radio = nullptr;
static m5::PI4IOE5V6408_Class ioe(0x43, 400000, &m5::In_I2C);
static HardwareSerial gpsSerial(1);

static bool attached = false;
static bool ready = false;
static bool fixValid = false;
static GpsFix fix{};
static uint32_t fixAt = 0;
static char nmea[96];
static uint8_t nmeaLen = 0;

bool loraCapAttached() { return attached; }
bool loraCapReady() { return ready; }

static int32_t nmeaAngle(const char *field, char hemisphere) {
  if (field == nullptr || field[0] == '\0') {
    return 0;
  }
  const double raw = atof(field);
  const int degrees = static_cast<int>(raw / 100.0);
  const double minutes = raw - degrees * 100.0;
  double decimal = degrees + minutes / 60.0;
  if (hemisphere == 'S' || hemisphere == 'W') {
    decimal = -decimal;
  }
  return static_cast<int32_t>(std::llround(decimal * 10000000.0));
}

static int splitFields(char *line, char **fields, int maxFields) {
  int count = 0;
  if (maxFields <= 0) {
    return 0;
  }
  fields[count++] = line;
  for (char *p = line; *p != '\0' && count < maxFields; ++p) {
    if (*p == ',') {
      *p = '\0';
      fields[count++] = p + 1;
    } else if (*p == '*') {
      *p = '\0';
      break;
    }
  }
  return count;
}

static void acceptFix(int32_t lat, int32_t lon, int sats) {
  if (lat == 0 && lon == 0) {
    return;
  }
  fix.latE7 = lat;
  fix.lonE7 = lon;
  if (sats >= 0) {
    fix.sats = static_cast<uint8_t>(sats > 255 ? 255 : sats);
  }
  fixValid = true;
  fixAt = millis();
}

static void parseNmea(char *line) {
  if (line[0] != '$' || std::strlen(line) < 6) {
    return;
  }
  const bool rmc = std::strncmp(line + 3, "RMC", 3) == 0;
  const bool gga = std::strncmp(line + 3, "GGA", 3) == 0;
  if (!rmc && !gga) {
    return;
  }
  char *fields[16];
  const int n = splitFields(line, fields, 16);
  if (rmc && n >= 7 && fields[2][0] == 'A') {
    acceptFix(nmeaAngle(fields[3], fields[4][0]), nmeaAngle(fields[5], fields[6][0]), -1);
  } else if (gga && n >= 8 && fields[6][0] != '\0' && fields[6][0] != '0') {
    acceptFix(nmeaAngle(fields[2], fields[3][0]), nmeaAngle(fields[4], fields[5][0]), atoi(fields[7]));
  }
}

void loraCapPoll() {
  if (!attached) {
    return;
  }
  while (gpsSerial.available() > 0) {
    const int raw = gpsSerial.read();
    if (raw < 0) {
      break;
    }
    const char c = static_cast<char>(raw);
    if (c == '\n') {
      nmea[nmeaLen] = '\0';
      if (nmeaLen > 0) {
        parseNmea(nmea);
      }
      nmeaLen = 0;
    } else if (c != '\r' && nmeaLen + 1 < sizeof(nmea)) {
      nmea[nmeaLen++] = c;
    }
  }
}

bool loraCapFix(GpsFix &out) {
  if (!fixValid || static_cast<int32_t>(millis() - fixAt) > 30000) {
    return false;
  }
  out = fix;
  return true;
}

static void formatE7(char *out, size_t outLen, int32_t e7) {
  const char sign = e7 < 0 ? '-' : '+';
  int32_t v = e7 < 0 ? -e7 : e7;
  // Five decimals is about a metre, which matches the receiver.
  snprintf(out, outLen, "%c%ld.%05ld", sign, static_cast<long>(v / 10000000),
           static_cast<long>((v % 10000000) / 100));
}

void loraCapFormat(char *out, size_t outLen) {
  if (outLen == 0) {
    return;
  }
  out[0] = '\0';
  GpsFix have;
  if (!loraCapFix(have)) {
    return;
  }
  char lat[16];
  char lon[16];
  formatE7(lat, sizeof(lat), have.latE7);
  formatE7(lon, sizeof(lon), have.lonE7);
  snprintf(out, outLen, "%s,%s", lat, lon);
}

static uint32_t readU32(const uint8_t *p) {
  return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
         (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

static uint32_t identify(const uint8_t *data, size_t len, uint8_t &flags) {
  if (len == 0) {
    return 0;
  }
  const uint8_t mtype = data[0] >> 5;
  if (len >= 5 && mtype >= 2 && mtype <= 5) {
    // MType: 2 unconfirmed up, 3 unconfirmed down, 4 confirmed up, 5 confirmed
    // down. Downlink and confirmed go in spare flag bits; older apps ignore them.
    flags = static_cast<uint8_t>(flags | 0x01);
    if (mtype == 3 || mtype == 5) flags = static_cast<uint8_t>(flags | 0x08);  // downlink
    if (mtype == 4 || mtype == 5) flags = static_cast<uint8_t>(flags | 0x10);  // confirmed
    return readU32(data + 1);
  }
  if (mtype == 0 && len >= 17) {
    flags = static_cast<uint8_t>(flags | 0x05);
    return readU32(data + 9);
  }
  uint32_t hash = 2166136261u;
  const size_t n = len < 8 ? len : 8;
  for (size_t i = 0; i < n; ++i) {
    hash ^= data[i];
    hash *= 16777619u;
  }
  return hash;
}

static bool waitPacket(uint32_t timeoutMs) {
  const uint32_t until = millis() + timeoutMs;
  while (static_cast<int32_t>(millis() - until) < 0) {
    if (digitalRead(kIrq) == HIGH) {
      return true;
    }
    loraCapPoll();
    delay(2);
  }
  return false;
}

void loraCapBegin() {
  attached = false;
  ready = false;
  // M5Cardputer.begin already started this bus. A second begin is harmless;
  // a missing expander means the cap is not fitted, and must not stall boot.
  m5::In_I2C.begin();
  if (!ioe.begin()) {
    return;
  }
  ioe.setDirection(0, true);
  ioe.setHighImpedance(0, false);
  ioe.digitalWrite(0, true);
  attached = true;

  gpsSerial.begin(115200, SERIAL_8N1, kGpsRx, kGpsTx);

  // ss stays -1: RadioLib drives NSS itself. Passing the already-started
  // SPI object keeps initInterface false, so begin() will not rebind the bus.
  SPI.begin(kSck, kMiso, kMosi, -1);
  if (radio == nullptr) {
    radioModule = new Module(kNss, kIrq, kRst, kBusy, SPI);
    radio = new SX1262(radioModule);
  }
  const int16_t state = radio->begin(868.1, 125.0, 7, 5, RADIOLIB_SX126X_SYNC_WORD_PUBLIC, 14, 8, 3.0, true);
  if (state != RADIOLIB_ERR_NONE) {
    return;
  }
  radio->setCurrentLimit(140);
  radio->sleep();
  ready = true;
}

void loraCapScan(void (*progress)(uint32_t freqKhz, uint8_t sf, uint16_t index, uint16_t total),
                void (*heard)(const LoraDevice &dev)) {
  if (!ready || radio == nullptr) {
    return;
  }
  if (radio->standby() != RADIOLIB_ERR_NONE) {
    radio->sleep();
    return;
  }
  radio->setBandwidth(125.0);
  radio->setCodingRate(5);
  radio->setPreambleLength(8);
  radio->setCRC(2);

  // Stay in receive. A CAD hit arrives after the preamble, too late to read
  // that packet, and LoRaWAN devices do not repeat it. SF7, SF9 and SF12
  // cover the usual data rates; private networks are checked on the three
  // EU868 defaults at SF7.
  struct Step {
    uint32_t khz;
    uint8_t sf;
    uint8_t sync;
    uint32_t dwellMs;
  };
  Step steps[16 * 3 + 3];
  size_t count = 0;
  const uint8_t rates[] = {7, 9, 12};
  const uint32_t dwell[] = {300, 600, 1400};
  for (uint32_t freq : kFreqKhz) {
    for (int rate = 0; rate < 3 && count < sizeof(steps) / sizeof(steps[0]); ++rate) {
      steps[count++] = {freq, rates[rate], RADIOLIB_SX126X_SYNC_WORD_PUBLIC, dwell[rate]};
    }
  }
  const uint32_t privateFreqs[] = {868100, 868300, 868500};
  for (uint32_t freq : privateFreqs) {
    if (count >= sizeof(steps) / sizeof(steps[0])) break;
    steps[count++] = {freq, 7, RADIOLIB_SX126X_SYNC_WORD_PRIVATE, 300};
  }

  uint8_t raw[64];
  for (size_t i = 0; i < count; ++i) {
    const Step &step = steps[i];
    if (progress != nullptr) {
      progress(step.khz, step.sf, static_cast<uint16_t>(i + 1), static_cast<uint16_t>(count));
    }
    loraCapPoll();
    if (radio->setFrequency(step.khz / 1000.0f) != RADIOLIB_ERR_NONE) continue;
    if (radio->setSpreadingFactor(step.sf) != RADIOLIB_ERR_NONE) continue;
    if (radio->setSyncWord(step.sync) != RADIOLIB_ERR_NONE) continue;
    radio->standby();
    radio->clearIrqFlags(RADIOLIB_SX126X_IRQ_ALL);
    if (radio->startReceive() != RADIOLIB_ERR_NONE) continue;
    if (!waitPacket(step.dwellMs) || heard == nullptr) {
      radio->standby();
      continue;
    }
    const size_t len = radio->getPacketLength();
    const size_t take = len < sizeof(raw) ? len : sizeof(raw);
    const int16_t state = take == 0 ? RADIOLIB_ERR_UNKNOWN : radio->readData(raw, take);
    if ((state == RADIOLIB_ERR_NONE || state == RADIOLIB_ERR_CRC_MISMATCH) && take > 0) {
      LoraDevice dev{};
      dev.freqKhz = step.khz;
      dev.sf = step.sf;
      dev.rssi = static_cast<int16_t>(std::lround(radio->getRSSI()));
      const long snr = std::lround(radio->getSNR());
      dev.snr = static_cast<int8_t>(snr < -128 ? -128 : snr > 127 ? 127 : snr);
      dev.flags = step.sync == RADIOLIB_SX126X_SYNC_WORD_PRIVATE ? 0x02 : 0;
      dev.id = identify(raw, take, dev.flags);
      heard(dev);
    }
    radio->standby();
  }
  radio->sleep();
}
