// Spectra sensor firmware for the M5Stack Cardputer.
//
// Scans for Wi-Fi access points and nearby stations (clients), then sends the
// results to the Spectra iOS app over BLE. The wire format is described in
// docs/protocol.md.

#include <M5Cardputer.h>
#include <NimBLEDevice.h>
#include <Update.h>
#include <WiFi.h>
#include <esp_mac.h>
#include <esp_wifi.h>
#include <algorithm>
#include <cstring>
#include <vector>

static constexpr char kFirmwareVersion[] = "0.6.0";
static constexpr uint8_t kProtocolVersion = 5;

static constexpr char kServiceUuid[] = "53500001-8F2D-4B6A-9C1E-737065637472";
static constexpr char kInfoUuid[] = "53500002-8F2D-4B6A-9C1E-737065637472";
static constexpr char kControlUuid[] = "53500003-8F2D-4B6A-9C1E-737065637472";
static constexpr char kResultsUuid[] = "53500004-8F2D-4B6A-9C1E-737065637472";
static constexpr char kFirmwareUuid[] = "53500005-8F2D-4B6A-9C1E-737065637472";

static constexpr uint8_t kCmdScanOnce = 0x01;
static constexpr uint8_t kCmdSetInterval = 0x02;

static constexpr uint8_t kMsgScanStarted = 0x10;
static constexpr uint8_t kMsgAccessPoint = 0x11;
static constexpr uint8_t kMsgScanFinished = 0x12;
static constexpr uint8_t kMsgStation = 0x13;
static constexpr uint8_t kMsgProbeReq = 0x14;
static constexpr uint8_t kMsgDeauth = 0x15;
static constexpr uint8_t kMsgSSIDReveal = 0x16;
static constexpr uint8_t kMsgTraffic = 0x17;
static constexpr uint8_t kMsgUpdate = 0x30;

static constexpr uint8_t kOtaBegin = 0x01;
static constexpr uint8_t kOtaData = 0x02;
static constexpr uint8_t kOtaFinish = 0x03;
static constexpr uint8_t kOtaAbort = 0x04;

static constexpr uint8_t kOtaReady = 1;
static constexpr uint8_t kOtaProgress = 2;
static constexpr uint8_t kOtaDone = 3;
static constexpr uint8_t kOtaFailed = 4;

static constexpr uint8_t kOtaErrNone = 0;
static constexpr uint8_t kOtaErrBusy = 1;
static constexpr uint8_t kOtaErrTooLarge = 2;
static constexpr uint8_t kOtaErrBegin = 3;
static constexpr uint8_t kOtaErrOffset = 4;
static constexpr uint8_t kOtaErrWrite = 5;
static constexpr uint8_t kOtaErrCrc = 6;
static constexpr uint8_t kOtaErrFinish = 7;
static constexpr uint8_t kOtaErrAbort = 8;
static constexpr uint8_t kOtaErrImage = 9;

// Time spent on each channel during an active scan. 13 channels at 120 ms
// keeps a full sweep under two seconds.
static constexpr uint32_t kMsPerChannel = 120;

// How long promiscuous mode listens on each channel for clients.
static constexpr uint32_t kSniffMsPerChannel = 250;
static constexpr uint8_t kSniffChannels[] = {1, 6, 11, 2, 3, 4, 5, 7, 8, 9, 10, 12, 13};

struct ApSummary {
  String ssid;
  int8_t rssi;
  uint8_t channel;
  wifi_auth_mode_t auth;
};

// A station (client device) seen through promiscuous-mode frame capture.
struct StationEntry {
  uint8_t mac[6];
  uint8_t bssid[6];  // all zeros when the client is only probing
  int8_t rssi;       // strongest signal seen
  bool associated;   // true if seen in a data/association frame
};

// A probe request: a device looking for a specific SSID.
struct ProbeEntry {
  uint8_t mac[6];
  int8_t rssi;
  uint8_t channel;
  uint8_t ssidLen;
  char ssid[33];
};

// A deauthentication or disassociation frame — potential attack indicator.
struct DeauthEntry {
  uint8_t src[6];     // addr2: who sent it
  uint8_t dst[6];     // addr1: target (FF:FF:FF:FF:FF:FF = broadcast)
  uint8_t bssid[6];   // addr3: network
  uint16_t reason;    // 802.11 reason code
  int8_t rssi;
  uint8_t channel;
  uint16_t count;     // frames seen with this signature
};

// An SSID revealed from a probe response or association request for a hidden AP.
struct RevealEntry {
  uint8_t bssid[6];
  int8_t rssi;
  uint8_t ssidLen;
  char ssid[33];
};

// Estimated traffic volume per AP, accumulated from data frames in promiscuous mode.
struct TrafficEntry {
  uint8_t bssid[6];
  uint32_t bytes;
  uint16_t packets;
};

static NimBLEServer *server = nullptr;
static NimBLECharacteristic *infoChar = nullptr;
static NimBLECharacteristic *resultsChar = nullptr;
static char deviceName[16];

// Written from the NimBLE host task, read from loop().
static volatile bool scanRequested = false;
static volatile uint8_t scanIntervalSec = 0;
static volatile bool intervalChanged = false;
static volatile bool linkChanged = true;

static uint16_t scanId = 0;
static uint32_t lastScanMs = 0;
static uint32_t lastScanDurationMs = 0;
static std::vector<ApSummary> lastResults;
static uint16_t lastStationCount = 0;
static uint16_t lastProbeCount = 0;
static bool uiDirty = true;

// Scan and OTA both want the radio and the flash. The mux only covers the
// flag hand-off; the scan itself runs outside it.
static portMUX_TYPE radioMux = portMUX_INITIALIZER_UNLOCKED;
static volatile bool scanInProgress = false;
static volatile bool otaActive = false;
static volatile bool otaRebootPending = false;
static volatile int otaPercent = -1;
static bool otaCrcOk = false;
static uint32_t otaExpected = 0;
static uint32_t otaReceived = 0;
static uint32_t otaCrcExpected = 0;
static uint32_t otaCrc = 0;
static uint32_t otaProgressMark = 0;
static uint32_t otaRebootAt = 0;
static uint32_t crc32Table[256];
static bool crc32Ready = false;

// Collected during promiscuous-mode capture; accessed only from loop().
static std::vector<StationEntry> capturedStations;
static std::vector<ProbeEntry> capturedProbes;
static std::vector<DeauthEntry> capturedDeauths;
static std::vector<RevealEntry> capturedReveals;
static std::vector<TrafficEntry> capturedTraffic;
// BSSIDs of hidden APs from the latest scan, for filtering probe responses.
struct HiddenAP { uint8_t bssid[6]; };
static std::vector<HiddenAP> hiddenAPs;
static uint8_t ownMac[6];

static bool isConnected() { return server != nullptr && server->getConnectedCount() > 0; }

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

static bool macEqual(const uint8_t *a, const uint8_t *b) { return memcmp(a, b, 6) == 0; }

static bool isBroadcast(const uint8_t *mac) {
  static const uint8_t bcast[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
  static const uint8_t zero[6] = {0, 0, 0, 0, 0, 0};
  return macEqual(mac, bcast) || macEqual(mac, zero);
}

static bool isMulticast(const uint8_t *mac) { return (mac[0] & 0x01) != 0; }

/// Record a station, merging into an existing entry when we already know it.
static void recordStation(const uint8_t *mac, const uint8_t *bssid, int8_t rssi, bool assoc) {
  if (isBroadcast(mac) || isMulticast(mac) || macEqual(mac, ownMac)) return;
  for (auto &s : capturedStations) {
    if (macEqual(s.mac, mac)) {
      if (rssi > s.rssi) s.rssi = rssi;
      if (assoc && !s.associated) {
        s.associated = true;
        memcpy(s.bssid, bssid, 6);
      }
      return;
    }
  }
  // New entry, cap at 128 to stay within RAM.
  if (capturedStations.size() >= 128) return;
  StationEntry entry;
  memcpy(entry.mac, mac, 6);
  memcpy(entry.bssid, bssid, 6);
  entry.rssi = rssi;
  entry.associated = assoc;
  capturedStations.push_back(entry);
}

/// Record a probe request, deduplicating by MAC+SSID.
static void recordProbe(const uint8_t *mac, int8_t rssi, uint8_t channel,
                        const char *ssid, uint8_t ssidLen) {
  if (isBroadcast(mac) || isMulticast(mac) || macEqual(mac, ownMac)) return;
  if (ssidLen > 32) ssidLen = 32;
  for (auto &p : capturedProbes) {
    if (macEqual(p.mac, mac) && p.ssidLen == ssidLen &&
        memcmp(p.ssid, ssid, ssidLen) == 0) {
      if (rssi > p.rssi) p.rssi = rssi;
      return;
    }
  }
  if (capturedProbes.size() >= 256) return;
  ProbeEntry entry;
  memcpy(entry.mac, mac, 6);
  entry.rssi = rssi;
  entry.channel = channel;
  entry.ssidLen = ssidLen;
  memcpy(entry.ssid, ssid, ssidLen);
  entry.ssid[ssidLen] = '\0';
  capturedProbes.push_back(entry);
}

static void recordDeauth(const uint8_t *src, const uint8_t *dst,
                         const uint8_t *bssid, uint16_t reason,
                         int8_t rssi, uint8_t channel) {
  for (auto &d : capturedDeauths) {
    if (macEqual(d.src, src) && macEqual(d.dst, dst) &&
        macEqual(d.bssid, bssid) && d.reason == reason) {
      d.count++;
      if (rssi > d.rssi) d.rssi = rssi;
      return;
    }
  }
  if (capturedDeauths.size() >= 64) return;
  DeauthEntry entry;
  memcpy(entry.src, src, 6);
  memcpy(entry.dst, dst, 6);
  memcpy(entry.bssid, bssid, 6);
  entry.reason = reason;
  entry.rssi = rssi;
  entry.channel = channel;
  entry.count = 1;
  capturedDeauths.push_back(entry);
}

/// Extract SSID (tag 0) from 802.11 tagged parameters.
static bool extractSSID(const uint8_t *frame, uint16_t frameLen,
                        uint16_t taggedStart, char *ssid, uint8_t &ssidLen) {
  if (frameLen <= taggedStart) return false;
  const uint8_t *body = frame + taggedStart;
  const uint16_t bodyLen = frameLen - taggedStart;
  uint16_t pos = 0;
  while (pos + 2 <= bodyLen) {
    const uint8_t tag = body[pos];
    const uint8_t tlen = body[pos + 1];
    if (pos + 2 + tlen > bodyLen) break;
    if (tag == 0) {
      if (tlen == 0 || tlen > 32) return false;
      ssidLen = tlen;
      memcpy(ssid, body + pos + 2, tlen);
      ssid[tlen] = '\0';
      return true;
    }
    pos += 2 + tlen;
  }
  return false;
}

/// Record a revealed SSID for a hidden AP, deduplicating by BSSID.
static void recordReveal(const uint8_t *bssid, int8_t rssi,
                         const char *ssid, uint8_t ssidLen) {
  if (ssidLen == 0 || ssidLen > 32) return;
  bool isHidden = false;
  for (const auto &h : hiddenAPs) {
    if (macEqual(bssid, h.bssid)) { isHidden = true; break; }
  }
  if (!isHidden) return;
  for (auto &r : capturedReveals) {
    if (macEqual(r.bssid, bssid)) {
      if (rssi > r.rssi) r.rssi = rssi;
      return;
    }
  }
  if (capturedReveals.size() >= 32) return;
  RevealEntry entry;
  memcpy(entry.bssid, bssid, 6);
  entry.rssi = rssi;
  entry.ssidLen = ssidLen;
  memcpy(entry.ssid, ssid, ssidLen);
  entry.ssid[ssidLen] = '\0';
  capturedReveals.push_back(entry);
}

/// Accumulate traffic volume for a BSSID.
static void recordTraffic(const uint8_t *bssid, uint16_t frameLen) {
  for (auto &t : capturedTraffic) {
    if (macEqual(t.bssid, bssid)) {
      t.bytes += frameLen;
      t.packets++;
      return;
    }
  }
  if (capturedTraffic.size() >= 64) return;
  TrafficEntry entry;
  memcpy(entry.bssid, bssid, 6);
  entry.bytes = frameLen;
  entry.packets = 1;
  capturedTraffic.push_back(entry);
}

// ---------------------------------------------------------------------------
// Promiscuous-mode callback
// ---------------------------------------------------------------------------

// 802.11 MAC header, simplified: first 24 bytes are always present.
// FC (2) + Duration (2) + Addr1 (6) + Addr2 (6) + Addr3 (6) + SeqCtrl (2).
//
// Frame types we care about:
//   Management (type 0): probe request (subtype 4), probe response (5),
//     association request (0), association response (1), reassociation (2/3).
//   Data (type 2): any data frame, including QoS data.

static void IRAM_ATTR promiscuousCallback(void *buf, wifi_promiscuous_pkt_type_t type) {
  if (type != WIFI_PKT_MGMT && type != WIFI_PKT_DATA) return;

  const auto *pkt = static_cast<const wifi_promiscuous_pkt_t *>(buf);
  if (pkt->rx_ctrl.sig_len < 24) return;

  const uint8_t *frame = pkt->payload;
  const uint8_t fc0 = frame[0];
  const uint8_t frameType = (fc0 >> 2) & 0x03;
  const uint8_t subtype = (fc0 >> 4) & 0x0F;
  const int8_t rssi = pkt->rx_ctrl.rssi;

  const uint8_t *addr1 = frame + 4;   // receiver / destination
  const uint8_t *addr2 = frame + 10;  // transmitter / source
  const uint8_t *addr3 = frame + 16;  // BSSID (in most cases)

  static const uint8_t zeroBssid[6] = {0, 0, 0, 0, 0, 0};

  if (frameType == 0) {
    // Management frame.
    if (subtype == 4) {
      // Probe request: addr2 is the client, no associated AP.
      recordStation(addr2, zeroBssid, rssi, false);
      char ssid[33];
      uint8_t ssidLen = 0;
      if (extractSSID(frame, pkt->rx_ctrl.sig_len, 24, ssid, ssidLen)) {
        uint8_t ch = 0;
        esp_wifi_get_channel(&ch, nullptr);
        recordProbe(addr2, rssi, ch, ssid, ssidLen);
      } else {
        uint8_t ch = 0;
        esp_wifi_get_channel(&ch, nullptr);
        recordProbe(addr2, rssi, ch, "", 0);
      }
    } else if (subtype == 5) {
      // Probe response: addr2 is the AP, addr3 is BSSID.
      // Tagged params at offset 36 (24 header + 12 fixed fields).
      char ssid[33];
      uint8_t ssidLen = 0;
      if (extractSSID(frame, pkt->rx_ctrl.sig_len, 36, ssid, ssidLen)) {
        recordReveal(addr3, rssi, ssid, ssidLen);
      }
    } else if (subtype == 10 || subtype == 12) {
      // Disassociation (10) or deauthentication (12).
      // Reason code is the first 2 bytes of the frame body.
      uint16_t reason = 0;
      if (pkt->rx_ctrl.sig_len >= 26) {
        reason = frame[24] | (static_cast<uint16_t>(frame[25]) << 8);
      }
      uint8_t ch = 0;
      esp_wifi_get_channel(&ch, nullptr);
      recordDeauth(addr2, addr1, addr3, reason, rssi, ch);
    } else if (subtype == 0 || subtype == 2) {
      // Association / reassociation request: addr2 is the client, addr1 is the AP.
      recordStation(addr2, addr1, rssi, true);
      // Try to reveal hidden SSID: tagged params at 28 (assoc) or 34 (reassoc).
      const uint16_t taggedStart = (subtype == 0) ? 28 : 34;
      char ssid[33];
      uint8_t ssidLen = 0;
      if (extractSSID(frame, pkt->rx_ctrl.sig_len, taggedStart, ssid, ssidLen)) {
        recordReveal(addr1, rssi, ssid, ssidLen);
      }
    } else if (subtype == 1 || subtype == 3) {
      // Association / reassociation response: addr1 is the client, addr2 is the AP.
      recordStation(addr1, addr2, rssi, true);
    }
  } else if (frameType == 2) {
    // Data frame.
    const uint8_t toDS = (fc0 >> 0) & 0x01;   // actually fc1 bit 0
    const uint8_t fromDS = (fc0 >> 1) & 0x01;  // actually fc1 bit 1
    // Re-read from fc1 which is frame[1].
    const uint8_t ds = frame[1] & 0x03;

    if (ds == 0x01) {
      // ToDS=1, FromDS=0: client → AP.
      // Addr1 = BSSID, Addr2 = source (client), Addr3 = destination.
      recordStation(addr2, addr1, rssi, true);
      recordTraffic(addr1, pkt->rx_ctrl.sig_len);
    } else if (ds == 0x02) {
      // ToDS=0, FromDS=1: AP → client.
      // Addr1 = destination (client), Addr2 = BSSID, Addr3 = source.
      recordStation(addr1, addr2, rssi, true);
      recordTraffic(addr2, pkt->rx_ctrl.sig_len);
    }
  }
}

// ---------------------------------------------------------------------------
// BLE protocol
// ---------------------------------------------------------------------------

static void updateInfo() {
  char json[160];
  snprintf(json, sizeof(json),
           "{\"proto\":%u,\"fw\":\"%s\",\"chip\":\"ESP32-S3\",\"bands\":[\"2.4\"],\"interval\":%u%s}",
           kProtocolVersion, kFirmwareVersion, scanIntervalSec, otaCrcOk ? ",\"ota\":true" : "");
  infoChar->setValue(json);
}

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer *, NimBLEConnInfo &) override { linkChanged = true; }

  void onDisconnect(NimBLEServer *, NimBLEConnInfo &, int) override {
    // Automatic scans are for the app; stop them when it goes away.
    scanIntervalSec = 0;
    intervalChanged = true;
    linkChanged = true;
    if (otaActive) {
      if (Update.isRunning()) {
        Update.abort();
      }
      otaActive = false;
      otaPercent = -1;
      otaRebootPending = false;
      WiFi.mode(WIFI_STA);
      WiFi.disconnect();
    }
  }
};

class ControlCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &) override {
    const NimBLEAttValue value = c->getValue();
    if (value.size() < 1 || otaActive) {
      return;
    }
    switch (value[0]) {
      case kCmdScanOnce:
        scanRequested = true;
        break;
      case kCmdSetInterval:
        if (value.size() >= 2) {
          scanIntervalSec = value[1];
          intervalChanged = true;
          if (scanIntervalSec > 0) {
            scanRequested = true;
          }
        }
        break;
      default:
        break;
    }
  }
};

// Sends one Results notification, waiting for buffers when the stack is busy.
static void sendMessage(const uint8_t *data, size_t length) {
  if (!isConnected()) {
    return;
  }
  for (int attempt = 0; attempt < 25; ++attempt) {
    if (resultsChar->notify(data, length)) {
      return;
    }
    delay(10);
  }
}

static void putU16(uint8_t *p, uint16_t v) {
  p[0] = v & 0xFF;
  p[1] = v >> 8;
}

static void sendScanStarted() {
  uint8_t msg[3] = {kMsgScanStarted};
  putU16(msg + 1, scanId);
  sendMessage(msg, sizeof(msg));
}

static void sendAccessPoint(const wifi_ap_record_t &ap) {
  uint8_t msg[19 + 32];
  msg[0] = kMsgAccessPoint;
  putU16(msg + 1, scanId);
  memcpy(msg + 3, ap.bssid, 6);
  msg[9] = static_cast<uint8_t>(ap.rssi);
  msg[10] = ap.primary;
  msg[11] = static_cast<uint8_t>(ap.second);
  msg[12] = static_cast<uint8_t>(ap.authmode);
  msg[13] = static_cast<uint8_t>(ap.pairwise_cipher);
  msg[14] = static_cast<uint8_t>(ap.group_cipher);
  msg[15] = (ap.phy_11b ? 0x01 : 0) | (ap.phy_11g ? 0x02 : 0) | (ap.phy_11n ? 0x04 : 0) |
            (ap.phy_lr ? 0x08 : 0) | (ap.phy_11ax ? 0x10 : 0) | (ap.wps ? 0x20 : 0) |
            (ap.ftm_responder ? 0x40 : 0);
  const bool hasCountry = isalpha(ap.country.cc[0]) && isalpha(ap.country.cc[1]);
  msg[16] = hasCountry ? ap.country.cc[0] : 0;
  msg[17] = hasCountry ? ap.country.cc[1] : 0;
  const size_t ssidLength = strnlen(reinterpret_cast<const char *>(ap.ssid), 32);
  msg[18] = static_cast<uint8_t>(ssidLength);
  memcpy(msg + 19, ap.ssid, ssidLength);
  sendMessage(msg, 19 + ssidLength);
}

static void sendStation(const StationEntry &sta) {
  // 17 bytes: type(1) + scanId(2) + clientMAC(6) + apBSSID(6) + rssi(1) + flags(1).
  uint8_t msg[17];
  msg[0] = kMsgStation;
  putU16(msg + 1, scanId);
  memcpy(msg + 3, sta.mac, 6);
  memcpy(msg + 9, sta.bssid, 6);
  msg[15] = static_cast<uint8_t>(sta.rssi);
  msg[16] = sta.associated ? 0x01 : 0x00;
  sendMessage(msg, sizeof(msg));
}

static void sendProbeReq(const ProbeEntry &probe) {
  uint8_t msg[12 + 32];
  msg[0] = kMsgProbeReq;
  putU16(msg + 1, scanId);
  memcpy(msg + 3, probe.mac, 6);
  msg[9] = static_cast<uint8_t>(probe.rssi);
  msg[10] = probe.channel;
  msg[11] = probe.ssidLen;
  memcpy(msg + 12, probe.ssid, probe.ssidLen);
  sendMessage(msg, 12 + probe.ssidLen);
}

static void sendDeauth(const DeauthEntry &d) {
  // 27 bytes: type(1) + scanId(2) + src(6) + dst(6) + bssid(6) + reason(2) + rssi(1) + channel(1) + count(2).
  uint8_t msg[27];
  msg[0] = kMsgDeauth;
  putU16(msg + 1, scanId);
  memcpy(msg + 3, d.src, 6);
  memcpy(msg + 9, d.dst, 6);
  memcpy(msg + 15, d.bssid, 6);
  putU16(msg + 21, d.reason);
  msg[23] = static_cast<uint8_t>(d.rssi);
  msg[24] = d.channel;
  putU16(msg + 25, d.count);
  sendMessage(msg, sizeof(msg));
}

static void sendReveal(const RevealEntry &r) {
  uint8_t msg[11 + 32];
  msg[0] = kMsgSSIDReveal;
  putU16(msg + 1, scanId);
  memcpy(msg + 3, r.bssid, 6);
  msg[9] = static_cast<uint8_t>(r.rssi);
  msg[10] = r.ssidLen;
  memcpy(msg + 11, r.ssid, r.ssidLen);
  sendMessage(msg, 11 + r.ssidLen);
}

static void sendTraffic(const TrafficEntry &t) {
  // 15 bytes: type(1) + scanId(2) + bssid(6) + bytes(4) + packets(2).
  uint8_t msg[15];
  msg[0] = kMsgTraffic;
  putU16(msg + 1, scanId);
  memcpy(msg + 3, t.bssid, 6);
  msg[9] = t.bytes & 0xFF;
  msg[10] = (t.bytes >> 8) & 0xFF;
  msg[11] = (t.bytes >> 16) & 0xFF;
  msg[12] = (t.bytes >> 24) & 0xFF;
  putU16(msg + 13, t.packets);
  sendMessage(msg, sizeof(msg));
}

static void sendScanFinished(uint16_t apCount, uint16_t staCount,
                             uint16_t probeCount, uint16_t durationMs) {
  uint8_t msg[11] = {kMsgScanFinished};
  putU16(msg + 1, scanId);
  putU16(msg + 3, apCount);
  putU16(msg + 5, durationMs);
  putU16(msg + 7, staCount);
  putU16(msg + 9, probeCount);
  sendMessage(msg, sizeof(msg));
}

// ---------------------------------------------------------------------------
// Firmware update
// ---------------------------------------------------------------------------

static void crc32Ensure() {
  if (crc32Ready) {
    return;
  }
  for (uint32_t i = 0; i < 256; ++i) {
    uint32_t crc = i;
    for (int bit = 0; bit < 8; ++bit) {
      crc = (crc & 1) ? (0xEDB88320u ^ (crc >> 1)) : (crc >> 1);
    }
    crc32Table[i] = crc;
  }
  crc32Ready = true;
}

static uint32_t crc32Update(uint32_t crc, const uint8_t *data, size_t length) {
  crc32Ensure();
  crc = ~crc;
  for (size_t i = 0; i < length; ++i) {
    crc = crc32Table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
  }
  return ~crc;
}

static bool otaCrcSelfCheck() {
  const uint8_t sample[] = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};
  return crc32Update(0, sample, sizeof(sample)) == 0xCBF43926u;
}

static void putU32(uint8_t *p, uint32_t value) {
  p[0] = value & 0xFF;
  p[1] = (value >> 8) & 0xFF;
  p[2] = (value >> 16) & 0xFF;
  p[3] = (value >> 24) & 0xFF;
}

static uint32_t getU32(const uint8_t *p) {
  return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
         (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

static void notifyOta(uint8_t phase, uint8_t error) {
  uint8_t msg[7];
  msg[0] = kMsgUpdate;
  msg[1] = phase;
  putU32(msg + 2, otaReceived);
  msg[6] = error;
  if (isConnected()) {
    resultsChar->notify(msg, sizeof(msg));
  }
}

static void otaCleanup(bool restoreWifi) {
  if (Update.isRunning()) {
    Update.abort();
  }
  otaActive = false;
  otaPercent = -1;
  otaRebootPending = false;
  if (restoreWifi) {
    WiFi.mode(WIFI_STA);
    WiFi.disconnect();
  }
  uiDirty = true;
}

static void otaFail(uint8_t error) {
  notifyOta(kOtaFailed, error);
  otaCleanup(true);
}

static void otaNoteProgress() {
  const int percent =
      otaExpected == 0 ? 0 : static_cast<int>((static_cast<uint64_t>(otaReceived) * 100) / otaExpected);
  if (percent != otaPercent) {
    otaPercent = percent;
    uiDirty = true;
  }
  const uint32_t mark = otaReceived / 16384;
  if (mark != otaProgressMark || otaReceived == otaExpected) {
    otaProgressMark = mark;
    notifyOta(kOtaProgress, kOtaErrNone);
  }
}

static bool otaTryClaim() {
  bool claimed = false;
  taskENTER_CRITICAL(&radioMux);
  if (!scanInProgress && !otaActive) {
    otaActive = true;
    claimed = true;
  }
  taskEXIT_CRITICAL(&radioMux);
  return claimed;
}

static void otaBegin(uint32_t size, uint32_t crc) {
  if (!otaCrcOk) {
    notifyOta(kOtaFailed, kOtaErrBegin);
    return;
  }
  if (!otaTryClaim()) {
    notifyOta(kOtaFailed, kOtaErrBusy);
    return;
  }
  const size_t slot = ESP.getFreeSketchSpace();
  if (size < 32 || slot == 0 || size > slot) {
    otaActive = false;
    notifyOta(kOtaFailed, slot != 0 && size > slot ? kOtaErrTooLarge : kOtaErrBegin);
    return;
  }
  WiFi.mode(WIFI_OFF);
  if (!Update.begin(size)) {
    otaCleanup(true);
    notifyOta(kOtaFailed, kOtaErrBegin);
    return;
  }
  otaExpected = size;
  otaCrcExpected = crc;
  otaCrc = 0;
  otaReceived = 0;
  otaProgressMark = 0;
  otaPercent = 0;
  uiDirty = true;
  notifyOta(kOtaReady, kOtaErrNone);
}

static void otaData(const uint8_t *data, size_t length, uint32_t offset) {
  if (!otaActive || !Update.isRunning()) {
    notifyOta(kOtaFailed, kOtaErrOffset);
    return;
  }
  if (length == 0 || offset != otaReceived || offset + length > otaExpected) {
    otaFail(kOtaErrOffset);
    return;
  }
  if (offset == 0 && data[0] != 0xE9) {
    otaFail(kOtaErrImage);
    return;
  }
  uint8_t buffer[512];
  if (length > sizeof(buffer)) {
    otaFail(kOtaErrWrite);
    return;
  }
  memcpy(buffer, data, length);
  if (Update.write(buffer, length) != length) {
    otaFail(kOtaErrWrite);
    return;
  }
  otaCrc = crc32Update(otaCrc, buffer, length);
  otaReceived += static_cast<uint32_t>(length);
  otaNoteProgress();
}

static void otaFinish() {
  if (!otaActive || !Update.isRunning()) {
    notifyOta(kOtaFailed, kOtaErrFinish);
    return;
  }
  if (otaReceived != otaExpected || otaCrc != otaCrcExpected) {
    otaFail(otaReceived != otaExpected ? kOtaErrOffset : kOtaErrCrc);
    return;
  }
  if (!Update.end(true)) {
    otaCleanup(true);
    notifyOta(kOtaFailed, kOtaErrFinish);
    return;
  }
  notifyOta(kOtaDone, kOtaErrNone);
  otaPercent = 100;
  uiDirty = true;
  otaRebootAt = millis() + 600;
  otaRebootPending = true;
}

static void otaAbortCommand() {
  if (!otaActive && !Update.isRunning()) {
    notifyOta(kOtaFailed, kOtaErrAbort);
    return;
  }
  otaFail(kOtaErrAbort);
}

class FirmwareCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &) override {
    const NimBLEAttValue value = c->getValue();
    if (value.size() < 1 || value.data() == nullptr) {
      return;
    }
    const uint8_t *bytes = value.data();
    const size_t length = value.size();
    switch (bytes[0]) {
      case kOtaBegin:
        if (length >= 9) {
          otaBegin(getU32(bytes + 1), getU32(bytes + 5));
        } else {
          notifyOta(kOtaFailed, kOtaErrBegin);
        }
        break;
      case kOtaData:
        if (length >= 5) {
          otaData(bytes + 5, length - 5, getU32(bytes + 1));
        } else {
          otaFail(kOtaErrOffset);
        }
        break;
      case kOtaFinish:
        otaFinish();
        break;
      case kOtaAbort:
        otaAbortCommand();
        break;
      default:
        break;
    }
  }
};

// ---------------------------------------------------------------------------
// Display
// ---------------------------------------------------------------------------

static void drawStatus(const char *line) {
  auto &d = M5Cardputer.Display;
  d.fillRect(0, 0, d.width(), 18, 0x10A2);
  d.setTextColor(TFT_WHITE, 0x10A2);
  d.setCursor(6, 5);
  d.print(line);
}

static void drawUpdating() {
  auto &d = M5Cardputer.Display;
  d.setTextSize(1);
  d.fillScreen(TFT_BLACK);
  drawStatus(otaRebootPending ? "Rebooting" : "Updating firmware");
  d.setTextColor(TFT_WHITE, TFT_BLACK);
  d.setTextSize(3);
  d.setCursor(8, 48);
  d.printf("%3d%%", otaPercent < 0 ? 0 : otaPercent);
  d.setTextSize(1);
  d.setTextColor(TFT_DARKGREY, TFT_BLACK);
  d.setCursor(8, 108);
  d.print("Keep powered, stay close");
}

static void drawUi() {
  if (otaPercent >= 0) {
    drawUpdating();
    return;
  }
  auto &d = M5Cardputer.Display;
  d.setTextSize(1);
  d.fillScreen(TFT_BLACK);

  char header[48];
  if (isConnected()) {
    snprintf(header, sizeof(header), "%s  linked  %us", deviceName, scanIntervalSec);
  } else {
    snprintf(header, sizeof(header), "%s  waiting for app", deviceName);
  }
  drawStatus(header);

  d.setTextColor(TFT_DARKGREY, TFT_BLACK);
  d.setCursor(6, 24);
  if (lastScanMs == 0) {
    d.print("S or Enter: scan");
  } else {
    d.printf("%u AP %u STA %u PRB", static_cast<unsigned>(lastResults.size()),
             static_cast<unsigned>(lastStationCount),
             static_cast<unsigned>(lastProbeCount));
  }

  int y = 38;
  for (const ApSummary &ap : lastResults) {
    if (y > d.height() - 10) {
      break;
    }
    const uint16_t color = ap.rssi >= -60 ? TFT_GREEN : ap.rssi >= -75 ? TFT_YELLOW : TFT_ORANGE;
    d.setTextColor(color, TFT_BLACK);
    d.setCursor(6, y);
    d.printf("%4d", ap.rssi);
    d.setTextColor(TFT_LIGHTGREY, TFT_BLACK);
    d.setCursor(36, y);
    d.printf("%2u", ap.channel);
    d.setTextColor(ap.auth == WIFI_AUTH_OPEN ? TFT_CYAN : TFT_WHITE, TFT_BLACK);
    d.setCursor(54, y);
    String name = ap.ssid.length() > 0 ? ap.ssid : String("(hidden)");
    if (name.length() > 30) {
      name = name.substring(0, 29) + "~";
    }
    d.print(name);
    y += 12;
  }
}

// ---------------------------------------------------------------------------
// Scan
// ---------------------------------------------------------------------------

/// Sniff Wi-Fi frames in promiscuous mode to discover client stations.
static void sniffStations() {
  capturedStations.clear();
  capturedProbes.clear();
  capturedDeauths.clear();
  capturedReveals.clear();
  capturedTraffic.clear();

  // Remember our own STA MAC so we filter it out.
  esp_read_mac(ownMac, ESP_MAC_WIFI_STA);

  wifi_promiscuous_filter_t filt = {
      .filter_mask = WIFI_PROMIS_FILTER_MASK_MGMT | WIFI_PROMIS_FILTER_MASK_DATA};
  esp_wifi_set_promiscuous_filter(&filt);
  esp_wifi_set_promiscuous_rx_cb(promiscuousCallback);
  esp_wifi_set_promiscuous(true);

  for (uint8_t ch : kSniffChannels) {
    esp_wifi_set_channel(ch, WIFI_SECOND_CHAN_NONE);
    delay(kSniffMsPerChannel);
  }

  esp_wifi_set_promiscuous(false);
}

static void runScan() {
  if (otaActive) {
    return;
  }
  ++scanId;
  drawStatus("Scanning APs...");
  sendScanStarted();

  const uint32_t started = millis();

  // Phase 1: standard AP scan.
  const int16_t found = WiFi.scanNetworks(false, true, false, kMsPerChannel);

  lastResults.clear();
  uint16_t apSent = 0;
  for (int16_t i = 0; i < found; ++i) {
    const auto *ap = static_cast<const wifi_ap_record_t *>(WiFi.getScanInfoByIndex(i));
    if (ap == nullptr) {
      continue;
    }
    sendAccessPoint(*ap);
    ++apSent;
    lastResults.push_back({String(reinterpret_cast<const char *>(ap->ssid)), ap->rssi, ap->primary,
                           ap->authmode});
  }

  // Build hidden-AP list before promiscuous sniffing so the callback can
  // match probe responses and association requests against it.
  hiddenAPs.clear();
  for (int16_t i = 0; i < found; ++i) {
    const auto *ap = static_cast<const wifi_ap_record_t *>(WiFi.getScanInfoByIndex(i));
    if (ap && strnlen(reinterpret_cast<const char *>(ap->ssid), 32) == 0) {
      HiddenAP h;
      memcpy(h.bssid, ap->bssid, 6);
      hiddenAPs.push_back(h);
    }
  }
  WiFi.scanDelete();

  std::sort(lastResults.begin(), lastResults.end(),
            [](const ApSummary &a, const ApSummary &b) { return a.rssi > b.rssi; });

  // Phase 2: promiscuous-mode station sniffing.
  drawStatus("Sniffing clients...");
  sniffStations();

  uint16_t staSent = 0;
  for (const auto &sta : capturedStations) {
    sendStation(sta);
    ++staSent;
  }
  lastStationCount = staSent;

  uint16_t probeSent = 0;
  for (const auto &probe : capturedProbes) {
    sendProbeReq(probe);
    ++probeSent;
  }
  lastProbeCount = probeSent;

  for (const auto &d : capturedDeauths) {
    sendDeauth(d);
  }

  for (const auto &r : capturedReveals) {
    sendReveal(r);
  }

  for (const auto &t : capturedTraffic) {
    sendTraffic(t);
  }

  lastScanDurationMs = millis() - started;
  lastScanMs = millis();

  sendScanFinished(apSent, staSent, probeSent,
                   static_cast<uint16_t>(std::min<uint32_t>(lastScanDurationMs, 0xFFFF)));
  uiDirty = true;
}

// ---------------------------------------------------------------------------
// BLE setup
// ---------------------------------------------------------------------------

static void setupBle() {
  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_BT);
  snprintf(deviceName, sizeof(deviceName), "Spectra-%02X%02X", mac[4], mac[5]);

  NimBLEDevice::init(deviceName);
  NimBLEDevice::setMTU(517);

  server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  server->advertiseOnDisconnect(true);

  NimBLEService *service = server->createService(kServiceUuid);
  infoChar = service->createCharacteristic(kInfoUuid, NIMBLE_PROPERTY::READ);
  NimBLECharacteristic *control = service->createCharacteristic(
      kControlUuid, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  control->setCallbacks(new ControlCallbacks());
  resultsChar = service->createCharacteristic(kResultsUuid, NIMBLE_PROPERTY::NOTIFY);
  NimBLECharacteristic *firmware = service->createCharacteristic(
      kFirmwareUuid, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  firmware->setCallbacks(new FirmwareCallbacks());
  updateInfo();

  NimBLEAdvertising *advertising = NimBLEDevice::getAdvertising();
  advertising->addServiceUUID(kServiceUuid);
  advertising->setName(deviceName);
  advertising->enableScanResponse(true);
  advertising->start();
}

static void handleKeyboard() {
  if (!M5Cardputer.Keyboard.isChange() || !M5Cardputer.Keyboard.isPressed()) {
    return;
  }
  const Keyboard_Class::KeysState keys = M5Cardputer.Keyboard.keysState();
  bool scan = keys.enter;
  for (const char c : keys.word) {
    scan = scan || c == 's' || c == 'S';
  }
  if (scan) {
    scanRequested = true;
  }
}

void setup() {
  auto cfg = M5.config();
  M5Cardputer.begin(cfg, true);
  M5Cardputer.Display.setRotation(1);
  M5Cardputer.Display.setTextSize(1);
  M5Cardputer.Display.fillScreen(TFT_BLACK);

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();

  otaCrcOk = otaCrcSelfCheck();
  setupBle();
  uiDirty = true;
}

void loop() {
  M5Cardputer.update();
  if (otaRebootPending && static_cast<int32_t>(millis() - otaRebootAt) >= 0) {
    ESP.restart();
  }
  if (!otaActive) {
    handleKeyboard();
  }

  if (intervalChanged) {
    intervalChanged = false;
    updateInfo();
    uiDirty = true;
  }
  if (linkChanged) {
    linkChanged = false;
    uiDirty = true;
  }

  const bool due = scanIntervalSec > 0 && isConnected() && !otaActive &&
                   millis() - lastScanMs >= static_cast<uint32_t>(scanIntervalSec) * 1000;
  bool startScan = false;
  taskENTER_CRITICAL(&radioMux);
  if ((scanRequested || due) && !otaActive) {
    scanRequested = false;
    scanInProgress = true;
    startScan = true;
  } else if (otaActive) {
    scanRequested = false;
  }
  taskEXIT_CRITICAL(&radioMux);
  if (startScan) {
    runScan();
    scanInProgress = false;
  }

  if (uiDirty) {
    uiDirty = false;
    drawUi();
  }
  delay(10);
}
