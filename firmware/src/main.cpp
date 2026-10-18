// Spectra sensor firmware for the M5Stack Cardputer.
//
// Scans for Wi-Fi access points and nearby stations (clients), then sends the
// results to the Spectra iOS app over BLE. The wire format is described in
// docs/protocol.md.

#include "Bytes.h"
#include "Crc32.h"
#include "LoraCap.h"
#include "Protocol.h"

#include <M5Cardputer.h>
#include <ESPmDNS.h>
#include <HWCDC.h>
#include <NimBLEDevice.h>
#include <Preferences.h>
#include <Update.h>
#include <WiFi.h>
#include <time.h>
#include <esp_mac.h>
#include <esp_ota_ops.h>
#include <esp_wifi.h>
#include <algorithm>
#include <cstring>
#include <vector>

// The phone app finds this tag in a firmware.bin to show which version it
// carries. The version below points into it, so the linker keeps the tag.
static constexpr char kFirmwareTag[] = "SPECTRA-FW:0.9.1";
static constexpr const char* kFirmwareVersion = kFirmwareTag + 11;

// Wire protocol constants (UUIDs, command/message/update bytes) live in
// Protocol.h; the byte-packing helpers in Bytes.h.

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

// WPS device info extracted from vendor-specific IEs in beacons/probe responses.
struct VendorIEEntry {
  uint8_t bssid[6];
  char deviceName[33];
  char manufacturer[33];
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
// True once the phone has subscribed to the Results characteristic. A notify
// before that is dropped by the stack, so stored captures wait for it.
static volatile bool resultsSubscribed = false;
// Set when a connection needs its stored captures flushed once the phone is
// subscribed. Handled in loop(), not the callback, to keep BLE work off the
// host task.
static volatile bool captureFlushPending = false;
// When to tell a newly connected phone that the services may have changed.
static uint32_t serviceChangedAt = 0;

static uint16_t scanId = 0;
static uint32_t lastScanMs = 0;
static uint32_t lastScanDurationMs = 0;
static std::vector<ApSummary> lastResults;

struct KeptWifi {
  uint8_t bssid[6];
  int8_t rssi;
  uint8_t channel;
  uint8_t auth;
  char ssid[33];
};
struct KeptBle {
  uint8_t addr[6];
  uint8_t addrType;
  int8_t rssi;
  char name[21];
};
struct KeptLan {
  uint8_t ip[4];
  char name[25];
};
struct StoredCapture {
  char key[4];
  uint32_t id;
  bool queued;
  std::vector<uint8_t> blob;
};

static std::vector<KeptWifi> keptWifi;
static std::vector<KeptBle> keptBle;
static std::vector<KeptLan> keptLan;
static std::vector<LoraDevice> keptLora;
static char gpsLabel[24] = "";
static char lanNote[16] = "";
static int menuIndex = 0;
static int listOffset = 0;
static volatile uint8_t requestedKind = 0;
static char banner[24] = "";
static uint32_t bannerUntil = 0;
static std::vector<StoredCapture> stored;
static std::vector<std::vector<uint8_t>> outbox;
static uint32_t lastOutMs = 0;
static portMUX_TYPE ackMux = portMUX_INITIALIZER_UNLOCKED;
static uint32_t ackIds[8];
static uint8_t ackCount = 0;
static constexpr int kTabCount = 5;
static constexpr int kTabLink = 4;
static const char *kMenuNames[] = {"WiFi", "LAN", "BLE", "LoRa", "Join"};

// Wi-Fi join for LAN scans. The Cardputer stays unassociated for sniffing, but
// mDNS needs a real link, so the user can pick a network and type a password
// here; the credentials persist in NVS and the LAN scan reconnects as needed.
enum class WifiUi : uint8_t { None, Pick, Pass, Connecting };
static WifiUi wifiUi = WifiUi::None;
static std::vector<String> wifiList;
static int wifiSel = 0;
static int wifiListOffset = 0;
static char wifiSsid[33] = "";
static char wifiPass[65] = "";
static uint8_t wifiPassLen = 0;
static char wifiMsg[28] = "";
static uint32_t wifiConnectUntil = 0;
static bool wifiHaveCreds = false;
// Swallows the key that opened the setup so its release is not read as a press.
static bool wifiInputSeeded = false;

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
// True only when the CRC routine works and the device runs from an OTA slot
// with a second slot to write. Flashed over USB it is true; run from a
// launcher without an OTA layout it is false, so the app does not offer an
// update that the bootloader would never boot.
static bool otaUpdatable = false;
static uint32_t otaExpected = 0;
static uint32_t otaReceived = 0;
static uint32_t otaCrcExpected = 0;
static uint32_t otaCrc = 0;
static uint32_t otaProgressMark = 0;
static uint32_t otaRebootAt = 0;
// Begin erases the inactive slot before any byte is stored. That takes
// longer than a phone will wait on a GATT write, and doing it inside the
// NimBLE callback stalls the radio. The callback only queues the job.
static TaskHandle_t otaTask = nullptr;
static volatile uint8_t otaJob = 0;
static volatile bool otaJobBusy = false;
static uint32_t otaJobSize = 0;
static uint32_t otaJobCrc = 0;
// Collected during promiscuous-mode capture; accessed only from loop().
static std::vector<StationEntry> capturedStations;
static std::vector<ProbeEntry> capturedProbes;
static std::vector<DeauthEntry> capturedDeauths;
static std::vector<RevealEntry> capturedReveals;
static std::vector<TrafficEntry> capturedTraffic;
static std::vector<VendorIEEntry> capturedVendorIEs;
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

/// Record WPS vendor info for a BSSID, deduplicating.
static void recordVendorIE(const uint8_t *bssid,
                           const char *devName, const char *mfr) {
  for (const auto &v : capturedVendorIEs) {
    if (macEqual(v.bssid, bssid)) return;
  }
  if (capturedVendorIEs.size() >= 32) return;
  VendorIEEntry entry;
  memcpy(entry.bssid, bssid, 6);
  strncpy(entry.deviceName, devName, 32);
  entry.deviceName[32] = '\0';
  strncpy(entry.manufacturer, mfr, 32);
  entry.manufacturer[32] = '\0';
  capturedVendorIEs.push_back(entry);
}

/// Parse tagged parameters for a WPS vendor-specific IE (tag 0xDD, OUI 00:50:F2,
/// type 4) and extract Device Name (0x1011) and Manufacturer (0x1021).
static void extractWPSInfo(const uint8_t *frame, uint16_t frameLen,
                           uint16_t taggedStart, const uint8_t *bssid) {
  // Skip if we already have info for this BSSID.
  for (const auto &v : capturedVendorIEs) {
    if (macEqual(v.bssid, bssid)) return;
  }
  if (frameLen <= taggedStart) return;
  const uint8_t *body = frame + taggedStart;
  const uint16_t bodyLen = frameLen - taggedStart;

  char deviceName[33] = "";
  char manufacturer[33] = "";
  uint16_t pos = 0;
  while (pos + 2 <= bodyLen) {
    const uint8_t tag = body[pos];
    const uint8_t tlen = body[pos + 1];
    if (pos + 2 + tlen > bodyLen) break;
    if (tag == 0xDD && tlen >= 4 &&
        body[pos + 2] == 0x00 && body[pos + 3] == 0x50 &&
        body[pos + 4] == 0xF2 && body[pos + 5] == 0x04) {
      const uint8_t *wps = body + pos + 6;
      const uint16_t wpsLen = tlen - 4;
      uint16_t wpos = 0;
      while (wpos + 4 <= wpsLen) {
        const uint16_t attrType = (static_cast<uint16_t>(wps[wpos]) << 8) | wps[wpos + 1];
        const uint16_t attrLen = (static_cast<uint16_t>(wps[wpos + 2]) << 8) | wps[wpos + 3];
        if (wpos + 4 + attrLen > wpsLen) break;
        if (attrType == 0x1011 && attrLen > 0 && attrLen <= 32) {
          memcpy(deviceName, wps + wpos + 4, attrLen);
          deviceName[attrLen] = '\0';
        } else if (attrType == 0x1021 && attrLen > 0 && attrLen <= 32) {
          memcpy(manufacturer, wps + wpos + 4, attrLen);
          manufacturer[attrLen] = '\0';
        }
        wpos += 4 + attrLen;
      }
      break;
    }
    pos += 2 + tlen;
  }
  if (deviceName[0] != '\0' || manufacturer[0] != '\0') {
    recordVendorIE(bssid, deviceName, manufacturer);
  }
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
      extractWPSInfo(frame, pkt->rx_ctrl.sig_len, 36, addr3);
    } else if (subtype == 8) {
      // Beacon: addr3 is BSSID, tagged params at offset 36.
      extractWPSInfo(frame, pkt->rx_ctrl.sig_len, 36, addr3);
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

static int readBatteryPercent() {
  const int level = M5Cardputer.Power.getBatteryLevel();
  if (level < 0) {
    return -1;
  }
  return std::min(level, 100);
}

// The Cardputer charger status pins are not wired to the ESP32, so
// isCharging() stays "unknown" and must not be used as a boolean. A USB host
// still announces itself with SOF packets; a bare power bank does not.
static bool readBatteryCharging() {
  if (M5Cardputer.Power.isCharging() == m5::Power_Class::is_charging) {
    return true;
  }
  return HWCDC::isPlugged();
}

static void updateInfo() {
  char json[360];
  const int bat = readBatteryPercent();
  char batJson[40] = "";
  if (bat >= 0) {
    snprintf(batJson, sizeof(batJson), ",\"bat\":%d,\"chg\":%s", bat,
             readBatteryCharging() ? "true" : "false");
  }
  char capJson[96] = "";
  if (loraCapAttached()) {
    GpsFix fix;
    if (loraCapFix(fix)) {
      snprintf(capJson, sizeof(capJson), ",\"lora\":%s,\"lat\":%ld,\"lon\":%ld,\"sats\":%u",
               loraCapReady() ? "true" : "false", static_cast<long>(fix.latE7),
               static_cast<long>(fix.lonE7), fix.sats);
    } else {
      snprintf(capJson, sizeof(capJson), ",\"lora\":%s", loraCapReady() ? "true" : "false");
    }
  }
  snprintf(json, sizeof(json),
           "{\"proto\":%u,\"fw\":\"%s\",\"chip\":\"ESP32-S3\",\"bands\":[\"2.4\"],\"interval\":%u%s%s%s}",
           kProtocolVersion, kFirmwareVersion, scanIntervalSec,
           otaUpdatable ? ",\"ota\":true" : "", batJson, capJson);
  infoChar->setValue(json);
}

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer *, NimBLEConnInfo &) override { linkChanged = true; }

  void onDisconnect(NimBLEServer *, NimBLEConnInfo &, int) override {
    // Automatic scans are for the app; stop them when it goes away.
    scanIntervalSec = 0;
    intervalChanged = true;
    linkChanged = true;
    resultsSubscribed = false;
    captureFlushPending = false;
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

// The phone subscribes to Results after it discovers the service, which is a
// little after the link comes up. Captures queued before that are dropped, so
// the flush waits for this.
class ResultsCallbacks : public NimBLECharacteristicCallbacks {
  void onSubscribe(NimBLECharacteristic *, NimBLEConnInfo &, uint16_t subValue) override {
    resultsSubscribed = subValue != 0;
    if (resultsSubscribed) {
      captureFlushPending = true;
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
        taskENTER_CRITICAL(&radioMux);
        requestedKind = 0;
        scanRequested = true;
        taskEXIT_CRITICAL(&radioMux);
        break;
      case kCmdSetInterval:
        if (value.size() >= 2) {
          scanIntervalSec = value[1];
          intervalChanged = true;
          if (scanIntervalSec > 0) {
            taskENTER_CRITICAL(&radioMux);
            requestedKind = 0;
            scanRequested = true;
            taskEXIT_CRITICAL(&radioMux);
          }
        }
        break;
      case kCmdAckSaved:
        if (value.size() >= 5) {
          const uint32_t id = static_cast<uint32_t>(value[1]) |
                              (static_cast<uint32_t>(value[2]) << 8) |
                              (static_cast<uint32_t>(value[3]) << 16) |
                              (static_cast<uint32_t>(value[4]) << 24);
          taskENTER_CRITICAL(&ackMux);
          if (ackCount < 8) {
            ackIds[ackCount++] = id;
          }
          taskEXIT_CRITICAL(&ackMux);
        }
        break;
      case kCmdScanLora:
        taskENTER_CRITICAL(&radioMux);
        requestedKind = 3;
        scanRequested = true;
        taskEXIT_CRITICAL(&radioMux);
        break;
      default:
        break;
    }
  }
};

// Sends one Results notification, waiting for buffers when the stack is busy.
// Returns false when it could not be delivered, so the caller keeps it queued.
static bool sendMessage(const uint8_t *data, size_t length) {
  if (!isConnected()) {
    return false;
  }
  for (int attempt = 0; attempt < 25; ++attempt) {
    if (resultsChar->notify(data, length)) {
      return true;
    }
    delay(10);
  }
  return false;
}

// One try, then the outbox. A LoRa pass blocks the main loop, so a dropped
// notify would leave the phone spinning after the Cardputer has moved on.
static void flushOutboxNow() {
  while (!outbox.empty() && isConnected()) {
    const std::vector<uint8_t> &msg = outbox.front();
    if (!resultsChar->notify(msg.data(), msg.size())) {
      return;
    }
    outbox.erase(outbox.begin());
  }
}

static void sendLive(const uint8_t *data, size_t length) {
  if (!isConnected()) {
    return;
  }
  flushOutboxNow();
  if (resultsChar->notify(data, length)) {
    return;
  }
  if (outbox.size() < 64) {
    outbox.emplace_back(data, data + length);
  }
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

static void sendVendorIE(const VendorIEEntry &v) {
  const uint8_t dnLen = static_cast<uint8_t>(strnlen(v.deviceName, 32));
  const uint8_t mfLen = static_cast<uint8_t>(strnlen(v.manufacturer, 32));
  uint8_t msg[11 + 64];
  msg[0] = kMsgVendorIE;
  putU16(msg + 1, scanId);
  memcpy(msg + 3, v.bssid, 6);
  msg[9] = dnLen;
  memcpy(msg + 10, v.deviceName, dnLen);
  msg[10 + dnLen] = mfLen;
  memcpy(msg + 11 + dnLen, v.manufacturer, mfLen);
  sendMessage(msg, 11 + dnLen + mfLen);
}

static void sendBattery();

// Push a fresh level when USB is plugged or the percent moves, without waiting
// for the next Wi-Fi scan. The status strip is the only thing that changes.
static void watchBattery() {
  static uint32_t lastCheck = 0;
  static int lastLevel = -2;
  static bool lastCharging = false;
  if (otaActive || millis() - lastCheck < 1000) {
    return;
  }
  lastCheck = millis();
  const int level = readBatteryPercent();
  const bool charging = readBatteryCharging();
  const bool jumped = lastLevel < 0 || (level >= 0 && (level > lastLevel + 4 || level + 4 < lastLevel));
  if (level == lastLevel && charging == lastCharging) {
    return;
  }
  const bool announce = charging != lastCharging || jumped || lastLevel < 0;
  lastLevel = level;
  lastCharging = charging;
  if (!announce) {
    return;
  }
  updateInfo();
  if (isConnected()) {
    sendBattery();
  }
  uiDirty = true;
}

static void sendBattery() {
  const int level = readBatteryPercent();
  if (level < 0) {
    return;
  }
  uint8_t msg[5];
  msg[0] = kMsgBattery;
  putU16(msg + 1, scanId);
  msg[3] = static_cast<uint8_t>(level);
  msg[4] = readBatteryCharging() ? 0x01 : 0x00;
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

// Whether an update can actually be written and booted. The app must run from
// an OTA app partition (otherwise the bootloader ignores what we flash) and
// there must be a different slot with room for the image. With a single OTA
// slot, IDF returns that same partition as the "next" one; Update.begin then
// refuses to overwrite the running image.
static bool otaAvailable() {
  const esp_partition_t *running = esp_ota_get_running_partition();
  if (running == nullptr || running->type != ESP_PARTITION_TYPE_APP ||
      running->subtype < ESP_PARTITION_SUBTYPE_APP_OTA_0 ||
      running->subtype > ESP_PARTITION_SUBTYPE_APP_OTA_15) {
    return false;
  }
  const esp_partition_t *next = esp_ota_get_next_update_partition(nullptr);
  return next != nullptr && next != running && next->size >= 32;
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

static void otaFinish();

static void otaBegin(uint32_t size, uint32_t crc) {
  if (!otaUpdatable) {
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
  // Do not call WiFi.mode(WIFI_OFF) here. That deinits the Wi-Fi driver, and
  // on the ESP32-S3 the shared radio goes with it, so the BLE link carrying
  // this update drops before the first chunk. Scanning is already blocked
  // by otaActive; an idle STA interface does not get in the way.
  esp_wifi_set_promiscuous(false);
  esp_wifi_scan_stop();
  otaPercent = 0;
  uiDirty = true;
  // A dropped link can leave the Arduino updater open. Begin then fails
  // forever with "already running" until something aborts it.
  if (Update.isRunning()) {
    Update.abort();
  }
  if (!Update.begin(size)) {
    const uint8_t error = Update.getError() == UPDATE_ERROR_SIZE ? kOtaErrTooLarge : kOtaErrBegin;
    otaCleanup(true);
    notifyOta(kOtaFailed, error);
    return;
  }
  otaExpected = size;
  otaCrcExpected = crc;
  otaCrc = 0;
  otaReceived = 0;
  otaProgressMark = 0;
  otaPercent = 0;
  uiDirty = true;
  // The phone sends the first chunk as soon as it sees Ready. Drop the
  // busy flag first, under the same lock the queue uses, so that chunk
  // is accepted instead of rejected as busy.
  taskENTER_CRITICAL(&radioMux);
  otaJobBusy = false;
  taskEXIT_CRITICAL(&radioMux);
  notifyOta(kOtaReady, kOtaErrNone);
}

static void otaTaskMain(void *) {
  for (;;) {
    ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
    const uint8_t job = otaJob;
    if (job == kOtaBegin) {
      otaBegin(otaJobSize, otaJobCrc);
    } else if (job == kOtaFinish) {
      otaFinish();
    }
    taskENTER_CRITICAL(&radioMux);
    if (otaJob == job) {
      otaJob = 0;
      otaJobBusy = false;
    }
    taskEXIT_CRITICAL(&radioMux);
  }
}

// Copies the command and returns. False when a begin or finish is already running.
static bool otaEnqueue(uint8_t job, uint32_t size, uint32_t crc) {
  bool accepted = false;
  taskENTER_CRITICAL(&radioMux);
  if (otaTask != nullptr && !otaJobBusy && otaJob == 0) {
    otaJobSize = size;
    otaJobCrc = crc;
    otaJob = job;
    otaJobBusy = true;
    accepted = true;
  }
  taskEXIT_CRITICAL(&radioMux);
  if (accepted) {
    xTaskNotifyGive(otaTask);
  }
  return accepted;
}

static void otaData(const uint8_t *data, size_t length, uint32_t offset) {
  if (otaJobBusy) {
    notifyOta(kOtaFailed, kOtaErrBusy);
    return;
  }
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
          if (!otaEnqueue(kOtaBegin, getU32(bytes + 1), getU32(bytes + 5))) {
            notifyOta(kOtaFailed, kOtaErrBusy);
          }
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
        if (!otaEnqueue(kOtaFinish, 0, 0)) {
          notifyOta(kOtaFailed, kOtaErrBusy);
        }
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
// Standalone scans and the captures the side button stores
// ---------------------------------------------------------------------------

static void drawStatus(const char *line);

static uint32_t unixNow() {
  const auto dt = M5.Rtc.getDateTime();
  if (dt.date.year < 2024) {
    return 0;
  }
  tm clock = dt.get_tm();
  const time_t seconds = mktime(&clock);
  if (seconds < 1700000000) {
    return 0;
  }
  return static_cast<uint32_t>(seconds);
}

static void showBanner(const char *text) {
  snprintf(banner, sizeof(banner), "%s", text);
  bannerUntil = millis() + 2000;
  uiDirty = true;
}

static size_t storedRecordSize(uint8_t kind) {
  if (kind == 1) return 41;
  if (kind == 4) return 13;
  return 29;
}

static void enqueueCapture(StoredCapture &cap) {
  if (cap.queued || cap.blob.size() < 11) {
    return;
  }
  const uint8_t *blob = cap.blob.data();
  const uint8_t kind = blob[0];
  const uint32_t id = getU32(blob + 1);
  const uint32_t when = getU32(blob + 5);
  const uint16_t count = readU16(blob + 9);
  const size_t record = storedRecordSize(kind);
  if (count == 0 || 11 + static_cast<size_t>(count) * record > cap.blob.size()) {
    return;
  }
  const size_t bodyEnd = 11 + static_cast<size_t>(count) * record;
  if (bodyEnd + 11 <= cap.blob.size() && blob[bodyEnd] == 'G' && blob[bodyEnd + 1] == 'P') {
    uint8_t pos[18];
    pos[0] = kMsgSavedGps;
    putU32(pos + 1, id);
    putU32(pos + 5, when);
    memcpy(pos + 9, blob + bodyEnd + 2, 9);
    outbox.emplace_back(pos, pos + sizeof(pos));
  }
  for (uint16_t i = 0; i < count; ++i) {
    const uint8_t *row = blob + 11 + static_cast<size_t>(i) * record;
    uint8_t msg[64];
    size_t len = 0;
    if (kind == 1) {
      const uint8_t ssidLen = static_cast<uint8_t>(strnlen(reinterpret_cast<const char *>(row + 9), 32));
      msg[0] = kMsgSavedWifi;
      putU32(msg + 1, id);
      putU32(msg + 5, when);
      putU16(msg + 9, i);
      putU16(msg + 11, count);
      memcpy(msg + 13, row, 6);
      msg[19] = row[6];
      msg[20] = row[7];
      msg[21] = row[8];
      msg[22] = ssidLen;
      memcpy(msg + 23, row + 9, ssidLen);
      len = 23 + ssidLen;
    } else if (kind == 3) {
      const uint8_t nameLen = static_cast<uint8_t>(strnlen(reinterpret_cast<const char *>(row + 8), 20));
      msg[0] = kMsgSavedBle;
      putU32(msg + 1, id);
      putU32(msg + 5, when);
      putU16(msg + 9, i);
      putU16(msg + 11, count);
      memcpy(msg + 13, row, 6);
      msg[19] = row[6];
      msg[20] = row[7];
      msg[21] = nameLen;
      memcpy(msg + 22, row + 8, nameLen);
      len = 22 + nameLen;
    } else if (kind == 2) {
      const uint8_t nameLen = static_cast<uint8_t>(strnlen(reinterpret_cast<const char *>(row + 4), 24));
      msg[0] = kMsgSavedLan;
      putU32(msg + 1, id);
      putU32(msg + 5, when);
      putU16(msg + 9, i);
      putU16(msg + 11, count);
      memcpy(msg + 13, row, 4);
      msg[17] = nameLen;
      memcpy(msg + 18, row + 4, nameLen);
      len = 18 + nameLen;
    } else if (kind == 4) {
      msg[0] = kMsgSavedLora;
      putU32(msg + 1, id);
      putU32(msg + 5, when);
      putU16(msg + 9, i);
      putU16(msg + 11, count);
      memcpy(msg + 13, row, 13);
      len = 26;
    } else {
      return;
    }
    outbox.emplace_back(msg, msg + len);
  }
  cap.queued = true;
}

static void queueUnsent() {
  for (StoredCapture &cap : stored) {
    enqueueCapture(cap);
  }
}

static void forgetCapture(uint32_t id) {
  Preferences prefs;
  prefs.begin("spectra", false);
  for (auto it = stored.begin(); it != stored.end();) {
    if (it->id == id) {
      prefs.remove(it->key);
      it = stored.erase(it);
    } else {
      ++it;
    }
  }
  prefs.end();
}

static void loadStored() {
  stored.clear();
  Preferences prefs;
  prefs.begin("spectra", true);
  for (int slot = 0; slot < 6; ++slot) {
    char key[4];
    snprintf(key, sizeof(key), "c%d", slot);
    const size_t size = prefs.getBytesLength(key);
    if (size < 11) {
      continue;
    }
    StoredCapture cap;
    snprintf(cap.key, sizeof(cap.key), "c%d", slot);
    cap.blob.resize(size);
    prefs.getBytes(key, cap.blob.data(), size);
    cap.id = getU32(cap.blob.data() + 1);
    cap.queued = false;
    stored.push_back(std::move(cap));
  }
  prefs.end();
}

static bool storeBlob(uint8_t kind, const uint8_t *body, size_t bodyLen, uint16_t count) {
  if (count == 0 || bodyLen == 0) {
    return false;
  }
  Preferences prefs;
  prefs.begin("spectra", false);
  const uint32_t id = prefs.getUInt("seq", 1);
  prefs.putUInt("seq", id + 1);

  int slot = -1;
  for (int i = 0; i < 6; ++i) {
    char key[4];
    snprintf(key, sizeof(key), "c%d", i);
    if (prefs.getBytesLength(key) == 0) {
      slot = i;
      break;
    }
  }
  if (slot < 0) {
    uint32_t oldest = UINT32_MAX;
    for (const StoredCapture &cap : stored) {
      if (cap.id < oldest) {
        oldest = cap.id;
      }
    }
    for (auto it = stored.begin(); it != stored.end(); ++it) {
      if (it->id == oldest) {
        slot = it->key[1] - '0';
        prefs.remove(it->key);
        stored.erase(it);
        break;
      }
    }
  }
  if (slot < 0 || slot > 5) {
    prefs.end();
    return false;
  }

  GpsFix located;
  const bool haveFix = loraCapFix(located);
  std::vector<uint8_t> blob(11 + bodyLen + (haveFix ? 11 : 0));
  blob[0] = kind;
  putU32(blob.data() + 1, id);
  putU32(blob.data() + 5, unixNow());
  putU16(blob.data() + 9, count);
  memcpy(blob.data() + 11, body, bodyLen);
  if (haveFix) {
    uint8_t *tail = blob.data() + 11 + bodyLen;
    tail[0] = 'G';
    tail[1] = 'P';
    putU32(tail + 2, static_cast<uint32_t>(located.latE7));
    putU32(tail + 6, static_cast<uint32_t>(located.lonE7));
    tail[10] = located.sats;
  }

  char key[4];
  snprintf(key, sizeof(key), "c%d", slot);
  prefs.putBytes(key, blob.data(), blob.size());
  prefs.end();

  StoredCapture cap;
  snprintf(cap.key, sizeof(cap.key), "c%d", slot);
  cap.id = id;
  cap.queued = false;
  cap.blob = std::move(blob);
  stored.push_back(std::move(cap));
  return true;
}

static bool saveWifi() {
  if (keptWifi.empty()) {
    return false;
  }
  const uint16_t count = static_cast<uint16_t>(std::min<size_t>(keptWifi.size(), 40));
  std::vector<uint8_t> body(static_cast<size_t>(count) * 41);
  for (uint16_t i = 0; i < count; ++i) {
    uint8_t *row = body.data() + static_cast<size_t>(i) * 41;
    memcpy(row, keptWifi[i].bssid, 6);
    row[6] = static_cast<uint8_t>(keptWifi[i].rssi);
    row[7] = keptWifi[i].channel;
    row[8] = keptWifi[i].auth;
    memcpy(row + 9, keptWifi[i].ssid, 32);
  }
  return storeBlob(1, body.data(), body.size(), count);
}

static bool saveBle() {
  if (keptBle.empty()) {
    return false;
  }
  const uint16_t count = static_cast<uint16_t>(std::min<size_t>(keptBle.size(), 50));
  std::vector<uint8_t> body(static_cast<size_t>(count) * 29);
  for (uint16_t i = 0; i < count; ++i) {
    uint8_t *row = body.data() + static_cast<size_t>(i) * 29;
    memcpy(row, keptBle[i].addr, 6);
    row[6] = keptBle[i].addrType;
    row[7] = static_cast<uint8_t>(keptBle[i].rssi);
    memcpy(row + 8, keptBle[i].name, 20);
  }
  return storeBlob(3, body.data(), body.size(), count);
}

static bool saveLora() {
  if (keptLora.empty()) {
    return false;
  }
  const uint16_t count = static_cast<uint16_t>(std::min<size_t>(keptLora.size(), 40));
  std::vector<uint8_t> body(static_cast<size_t>(count) * 13);
  for (uint16_t i = 0; i < count; ++i) {
    uint8_t *row = body.data() + static_cast<size_t>(i) * 13;
    putU32(row, keptLora[i].freqKhz);
    row[4] = keptLora[i].sf;
    putU16(row + 5, static_cast<uint16_t>(keptLora[i].rssi));
    row[7] = static_cast<uint8_t>(keptLora[i].snr);
    row[8] = keptLora[i].flags;
    putU32(row + 9, keptLora[i].id);
  }
  return storeBlob(4, body.data(), body.size(), count);
}

static bool saveLan() {
  if (keptLan.empty()) {
    return false;
  }
  const uint16_t count = static_cast<uint16_t>(std::min<size_t>(keptLan.size(), 40));
  std::vector<uint8_t> body(static_cast<size_t>(count) * 29);
  for (uint16_t i = 0; i < count; ++i) {
    uint8_t *row = body.data() + static_cast<size_t>(i) * 29;
    memcpy(row, keptLan[i].ip, 4);
    memcpy(row + 4, keptLan[i].name, 24);
  }
  return storeBlob(2, body.data(), body.size(), count);
}

static void rememberLan(const IPAddress &ip, const String &name) {
  if (ip[0] == 0 || keptLan.size() >= 40) {
    return;
  }
  for (const KeptLan &have : keptLan) {
    if (have.ip[0] == ip[0] && have.ip[1] == ip[1] && have.ip[2] == ip[2] && have.ip[3] == ip[3]) {
      return;
    }
  }
  KeptLan row{};
  row.ip[0] = ip[0];
  row.ip[1] = ip[1];
  row.ip[2] = ip[2];
  row.ip[3] = ip[3];
  const size_t n = std::min<size_t>(name.length(), 24);
  memcpy(row.name, name.c_str(), n);
  keptLan.push_back(row);
}

static void loadWifiCreds() {
  Preferences prefs;
  prefs.begin("spectra", true);
  const String ssid = prefs.getString("wifiSsid", "");
  const String pass = prefs.getString("wifiPass", "");
  prefs.end();
  if (ssid.length() > 0) {
    snprintf(wifiSsid, sizeof(wifiSsid), "%s", ssid.c_str());
    snprintf(wifiPass, sizeof(wifiPass), "%s", pass.c_str());
    wifiHaveCreds = true;
  }
}

static void saveWifiCreds(const char *ssid, const char *pass) {
  Preferences prefs;
  prefs.begin("spectra", false);
  prefs.putString("wifiSsid", ssid);
  prefs.putString("wifiPass", pass);
  prefs.end();
  wifiHaveCreds = true;
}

// Brings the stored network up, blocking up to timeoutMs. Promiscuous sniffing
// hops channels and drops any association, so the link is re-established here
// on demand rather than kept alive.
static bool ensureWifiLink(uint32_t timeoutMs) {
  if (WiFi.status() == WL_CONNECTED) {
    return true;
  }
  if (!wifiHaveCreds) {
    return false;
  }
  WiFi.begin(wifiSsid, wifiPass);
  const uint32_t until = millis() + timeoutMs;
  while (WiFi.status() != WL_CONNECTED && static_cast<int32_t>(millis() - until) < 0) {
    delay(100);
  }
  return WiFi.status() == WL_CONNECTED;
}

static void runLanScan() {
  keptLan.clear();
  lanNote[0] = '\0';
  if (WiFi.status() != WL_CONNECTED) {
    if (wifiHaveCreds) {
      drawStatus("Wi-Fi link...");
      ensureWifiLink(6000);
    }
    if (WiFi.status() != WL_CONNECTED) {
      snprintf(lanNote, sizeof(lanNote), wifiHaveCreds ? "link failed" : "no Wi-Fi");
      uiDirty = true;
      return;
    }
  }
  drawStatus("Scanning LAN...");
  if (!MDNS.begin("spectra")) {
    snprintf(lanNote, sizeof(lanNote), "mDNS failed");
    uiDirty = true;
    return;
  }
  static const char *types[] = {"http", "googlecast", "airplay", "smb", "ipp", "ssh"};
  for (const char *type : types) {
    const int found = MDNS.queryService(type, "tcp");
    for (int i = 0; i < found; ++i) {
      String name = MDNS.hostname(i);
      if (name.length() == 0) {
        name = type;
      }
      rememberLan(MDNS.address(i), name);
    }
  }
  MDNS.end();
  if (keptLan.empty()) {
    snprintf(lanNote, sizeof(lanNote), "none");
  }
  uiDirty = true;
}

static void runBluetoothScan() {
  drawStatus("Scanning Bluetooth...");
  keptBle.clear();
  NimBLEScan *scan = NimBLEDevice::getScan();
  scan->setActiveScan(true);
  scan->setInterval(100);
  scan->setWindow(90);
  scan->setMaxResults(50);
  scan->clearResults();
  // NimBLE 2.x counts the scan duration in milliseconds, so start(6) scanned
  // for 6 ms and found nothing. getResults blocks for the full window.
  const NimBLEScanResults results = scan->getResults(6000, false);
  for (int i = 0; i < results.getCount() && keptBle.size() < 50; ++i) {
    const NimBLEAdvertisedDevice *dev = results.getDevice(static_cast<uint32_t>(i));
    if (dev == nullptr) {
      continue;
    }
    KeptBle row{};
    const uint8_t *addr = dev->getAddress().getVal();
    if (addr != nullptr) {
      memcpy(row.addr, addr, 6);
    }
    row.addrType = dev->getAddressType();
    row.rssi = dev->getRSSI();
    const std::string name = dev->getName();
    const size_t n = std::min<size_t>(name.size(), 20);
    memcpy(row.name, name.data(), n);
    keptBle.push_back(row);
  }
  scan->clearResults();
  uiDirty = true;
}

static void pumpOutbox() {
  if (outbox.empty() || !isConnected() || otaActive || scanInProgress) {
    return;
  }
  if (millis() - lastOutMs < 40) {
    return;
  }
  const std::vector<uint8_t> &msg = outbox.front();
  // Keep the item queued if it could not be delivered, so a capture is not
  // lost to a transient failure.
  if (sendMessage(msg.data(), msg.size())) {
    outbox.erase(outbox.begin());
  }
  lastOutMs = millis();
}

static void drainAcks() {
  while (ackCount > 0) {
    taskENTER_CRITICAL(&ackMux);
    const uint32_t id = ackIds[0];
    for (uint8_t i = 1; i < ackCount; ++i) {
      ackIds[i - 1] = ackIds[i];
    }
    --ackCount;
    taskEXIT_CRITICAL(&ackMux);
    forgetCapture(id);
  }
}

// ---------------------------------------------------------------------------
// Display
// ---------------------------------------------------------------------------

static int capIconWidth() {
  int width = 0;
  if (loraCapAttached()) {
    width += 12;
  }
  if (loraCapReady()) {
    width += 12;
  }
  return width;
}

// A crosshair when the cap's GNSS is present (green once it has a fix) and
// an antenna when the SX1262 answered. Both sit just left of the battery.
static void drawCapIcons(int x) {
  auto &d = M5Cardputer.Display;
  if (loraCapAttached()) {
    GpsFix fix;
    const uint16_t color = loraCapFix(fix) ? TFT_GREEN : TFT_WHITE;
    d.drawCircle(x + 5, 9, 3, color);
    d.fillCircle(x + 5, 9, 1, color);
    x += 12;
  }
  if (loraCapReady()) {
    d.drawFastVLine(x + 5, 5, 8, TFT_WHITE);
    d.drawLine(x + 5, 5, x + 1, 10, TFT_WHITE);
    d.drawLine(x + 5, 5, x + 9, 10, TFT_WHITE);
    d.drawLine(x + 2, 8, x + 8, 8, TFT_WHITE);
  }
}

static void drawStatus(const char *line) {
  auto &d = M5Cardputer.Display;
  d.fillRect(0, 0, d.width(), 18, 0x10A2);

  const int bat = readBatteryPercent();
  int right = d.width() - 2;
  if (bat >= 0) {
    const bool charging = readBatteryCharging();
    char cell[12];
    snprintf(cell, sizeof(cell), "%s%d%%", charging ? "+" : "", bat);
    const int cellX = d.width() - static_cast<int>(strlen(cell)) * 6 - 4;
    d.setTextColor(charging ? TFT_GREEN : TFT_WHITE, 0x10A2);
    d.setCursor(cellX, 5);
    d.print(cell);
    right = cellX - 2;
  }
  right -= capIconWidth();
  if (right < d.width() - 2) {
    drawCapIcons(right);
  }
  const int maxChars = std::max(0, (right - 6) / 6);
  char shown[48];
  snprintf(shown, sizeof(shown), "%.*s", maxChars, line);
  d.setTextColor(TFT_WHITE, 0x10A2);
  d.setCursor(6, 5);
  d.print(shown);
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

static int listCount() {
  switch (menuIndex) {
    case 0: return static_cast<int>(keptWifi.size());
    case 1: return static_cast<int>(keptLan.size());
    case 2: return static_cast<int>(keptBle.size());
    case 3: return static_cast<int>(keptLora.size());
    default: return 0;
  }
}

static constexpr int kListTop = 36;

static int listRows() {
  return std::max(1, static_cast<int>(M5Cardputer.Display.height() - kListTop) / 10);
}

static bool apHasActivity(const uint8_t bssid[6]) {
  for (const StationEntry &sta : capturedStations) {
    if (sta.associated && memcmp(sta.bssid, bssid, 6) == 0) {
      return true;
    }
  }
  for (const TrafficEntry &traffic : capturedTraffic) {
    if (traffic.packets > 0 && memcmp(traffic.bssid, bssid, 6) == 0) {
      return true;
    }
  }
  return false;
}

static void drawLinkTab();

static void drawTabs() {
  auto &d = M5Cardputer.Display;
  const int width = d.width() / kTabCount;
  for (int i = 0; i < kTabCount; ++i) {
    const int x = i * width;
    const bool on = i == menuIndex;
    d.fillRect(x, 18, width, 14, on ? TFT_WHITE : TFT_BLACK);
    d.setTextColor(on ? TFT_BLACK : TFT_DARKGREY, on ? TFT_WHITE : TFT_BLACK);
    const int textWidth = static_cast<int>(strlen(kMenuNames[i])) * 6;
    d.setCursor(x + (width - textWidth) / 2, 22);
    d.print(kMenuNames[i]);
  }
  d.drawFastHLine(0, 32, d.width(), 0x10A2);
}

static void drawLoraList() {
  auto &d = M5Cardputer.Display;
  d.fillRect(0, kListTop, d.width(), d.height() - kListTop, TFT_BLACK);
  int y = kListTop;
  int skip = listOffset;
  if (keptLora.empty()) {
    d.setTextColor(TFT_DARKGREY, TFT_BLACK);
    d.setCursor(4, y);
    if (!loraCapAttached()) {
      d.print("No LoRa cap");
    } else if (!loraCapReady()) {
      d.print("LoRa init failed");
    } else if (gpsLabel[0] == '\0') {
      d.print("GPS searching");
    }
    return;
  }
  for (const LoraDevice &dev : keptLora) {
    if (skip > 0) {
      --skip;
      continue;
    }
    if (y > d.height() - 8) {
      break;
    }
    const uint16_t color = dev.rssi >= -100 ? TFT_GREEN : dev.rssi >= -120 ? TFT_YELLOW : TFT_ORANGE;
    d.setTextColor(color, TFT_BLACK);
    d.setCursor(4, y);
    d.printf("%4d", dev.rssi);
    d.setTextColor(TFT_WHITE, TFT_BLACK);
    d.setCursor(34, y);
    d.printf("%lu.%lu SF%u %08lX", static_cast<unsigned long>(dev.freqKhz / 1000),
             static_cast<unsigned long>((dev.freqKhz % 1000) / 100), dev.sf,
             static_cast<unsigned long>(dev.id));
    y += 10;
  }
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
  if (banner[0] != '\0' && static_cast<int32_t>(millis() - bannerUntil) < 0) {
    snprintf(header, sizeof(header), "%s", banner);
  } else if (isConnected()) {
    snprintf(header, sizeof(header), "%s  linked", deviceName);
  } else {
    snprintf(header, sizeof(header), "%s  waiting", deviceName);
  }
  drawStatus(header);
  drawTabs();
  if (menuIndex == kTabLink) {
    drawLinkTab();
    return;
  }

  const int maxOffset = std::max(0, listCount() - listRows());
  if (listOffset > maxOffset) {
    listOffset = maxOffset;
  }
  if (listOffset < 0) {
    listOffset = 0;
  }

  int y = kListTop;
  int skip = listOffset;
  if (menuIndex == 0) {
    if (keptWifi.empty()) {
      d.setTextColor(TFT_DARKGREY, TFT_BLACK);
      d.setCursor(4, y);
      d.print("No networks");
    }
    for (const KeptWifi &ap : keptWifi) {
      if (skip > 0) {
        --skip;
        continue;
      }
      if (y > d.height() - 8) {
        break;
      }
      const uint16_t color = ap.rssi >= -60 ? TFT_GREEN : ap.rssi >= -75 ? TFT_YELLOW : TFT_ORANGE;
      d.setTextColor(color, TFT_BLACK);
      d.setCursor(4, y);
      d.printf("%4d", ap.rssi);
      const bool open = ap.auth == WIFI_AUTH_OPEN;
      const bool busy = apHasActivity(ap.bssid);
      d.setTextColor(open ? TFT_GREEN : TFT_WHITE, TFT_BLACK);
      d.setCursor(34, y);
      d.print(open ? "o" : "*");
      d.setTextColor(busy ? TFT_YELLOW : TFT_BLACK, TFT_BLACK);
      d.print(busy ? "+" : " ");
      d.setTextColor(TFT_WHITE, TFT_BLACK);
      const char *name = ap.ssid[0] != '\0' ? ap.ssid : "(hidden)";
      char shown[28];
      snprintf(shown, sizeof(shown), " %.26s", name);
      d.print(shown);
      y += 10;
    }
  } else if (menuIndex == 1) {
    if (keptLan.empty()) {
      d.setTextColor(TFT_DARKGREY, TFT_BLACK);
      d.setCursor(4, y);
      d.print(lanNote[0] != '\0' ? lanNote : "No hosts");
    }
    for (const KeptLan &host : keptLan) {
      if (skip > 0) {
        --skip;
        continue;
      }
      if (y > d.height() - 8) {
        break;
      }
      d.setTextColor(TFT_WHITE, TFT_BLACK);
      d.setCursor(4, y);
      d.printf("%u.%u.%u.%u", host.ip[0], host.ip[1], host.ip[2], host.ip[3]);
      d.setCursor(96, y);
      d.print(host.name);
      y += 10;
    }
  } else if (menuIndex == 3) {
    drawLoraList();
  } else {
    for (const KeptBle &dev : keptBle) {
      if (skip > 0) {
        --skip;
        continue;
      }
      if (y > d.height() - 8) {
        break;
      }
      const uint16_t color = dev.rssi >= -60 ? TFT_GREEN : dev.rssi >= -75 ? TFT_YELLOW : TFT_ORANGE;
      d.setTextColor(color, TFT_BLACK);
      d.setCursor(4, y);
      d.printf("%4d", dev.rssi);
      d.setTextColor(TFT_WHITE, TFT_BLACK);
      d.setCursor(34, y);
      d.print(dev.name[0] != '\0' ? dev.name : "(unnamed)");
      y += 10;
    }
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
  capturedVendorIEs.clear();

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
  keptWifi.clear();
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
    if (keptWifi.size() < 40) {
      KeptWifi row{};
      memcpy(row.bssid, ap->bssid, 6);
      row.rssi = ap->rssi;
      row.channel = ap->primary;
      row.auth = static_cast<uint8_t>(ap->authmode);
      const size_t name = strnlen(reinterpret_cast<const char *>(ap->ssid), 32);
      memcpy(row.ssid, ap->ssid, name);
      keptWifi.push_back(row);
    }
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
  std::sort(keptWifi.begin(), keptWifi.end(),
            [](const KeptWifi &a, const KeptWifi &b) { return a.rssi > b.rssi; });

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

  for (const auto &v : capturedVendorIEs) {
    sendVendorIE(v);
  }

  sendBattery();

  lastScanDurationMs = millis() - started;
  lastScanMs = millis();

  sendScanFinished(apSent, staSent, probeSent,
                   static_cast<uint16_t>(std::min<uint32_t>(lastScanDurationMs, 0xFFFF)));
  uiDirty = true;
}

static void sendLoraDevice(const LoraDevice &dev) {
  uint8_t msg[16];
  msg[0] = kMsgLoraDevice;
  putU16(msg + 1, scanId);
  putU32(msg + 3, dev.freqKhz);
  msg[7] = dev.sf;
  putU16(msg + 8, static_cast<uint16_t>(dev.rssi));
  msg[10] = static_cast<uint8_t>(dev.snr);
  msg[11] = dev.flags;
  putU32(msg + 12, dev.id);
  sendLive(msg, sizeof(msg));
}

static void sendLoraProgress(uint32_t freqKhz, uint8_t sf, uint16_t index, uint16_t total) {
  uint8_t msg[12] = {kMsgLoraProgress};
  putU16(msg + 1, scanId);
  putU32(msg + 3, freqKhz);
  msg[7] = sf;
  putU16(msg + 8, index);
  putU16(msg + 10, total);
  sendLive(msg, sizeof(msg));
}

static void onLoraProgress(uint32_t freqKhz, uint8_t sf, uint16_t index, uint16_t total) {
  char line[28];
  snprintf(line, sizeof(line), "%lu.%03lu SF%u", static_cast<unsigned long>(freqKhz / 1000),
           static_cast<unsigned long>(freqKhz % 1000), sf);
  drawStatus(line);
  sendLoraProgress(freqKhz, sf, index, total);
}

static void onLoraHeard(const LoraDevice &dev) {
  for (LoraDevice &have : keptLora) {
    if (have.freqKhz == dev.freqKhz && have.sf == dev.sf && have.id == dev.id) {
      if (dev.rssi > have.rssi) {
        have = dev;
        sendLoraDevice(dev);
        drawLoraList();
      }
      return;
    }
  }
  if (keptLora.size() >= 40) {
    return;
  }
  keptLora.push_back(dev);
  sendLoraDevice(dev);
  drawLoraList();
}

static void sendLoraScan(uint8_t phase, uint16_t count, uint16_t durationMs) {
  uint8_t msg[8] = {kMsgLoraScan};
  putU16(msg + 1, scanId);
  msg[3] = phase;
  putU16(msg + 4, count);
  putU16(msg + 6, durationMs);
  sendLive(msg, sizeof(msg));
}

static void sendGpsFix() {
  if (!isConnected() || !loraCapAttached()) {
    return;
  }
  GpsFix fix;
  const bool have = loraCapFix(fix);
  uint8_t msg[11];
  msg[0] = kMsgGps;
  putU32(msg + 1, have ? static_cast<uint32_t>(fix.latE7) : 0);
  putU32(msg + 5, have ? static_cast<uint32_t>(fix.lonE7) : 0);
  msg[9] = have ? fix.sats : 0;
  msg[10] = have ? 1 : 0;
  sendMessage(msg, sizeof(msg));
}

static void runLoraScan() {
  if (!loraCapReady()) {
    showBanner(loraCapAttached() ? "LoRa init failed" : "No LoRa cap");
    sendLoraScan(2, 0, 0);
    uiDirty = true;
    return;
  }
  ++scanId;
  keptLora.clear();
  menuIndex = 3;
  listOffset = 0;
  drawUi();
  drawStatus("Scanning LoRa...");
  sendLoraScan(1, 0, 0);
  const uint32_t started = millis();
  loraCapScan(onLoraProgress, onLoraHeard);
  std::sort(keptLora.begin(), keptLora.end(),
            [](const LoraDevice &a, const LoraDevice &b) { return a.rssi > b.rssi; });
  const uint32_t elapsed = millis() - started;
  sendLoraScan(2, static_cast<uint16_t>(keptLora.size()),
               static_cast<uint16_t>(std::min<uint32_t>(elapsed, 0xFFFF)));
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
  resultsChar->setCallbacks(new ResultsCallbacks());
  NimBLECharacteristic *firmware = service->createCharacteristic(
      kFirmwareUuid, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  firmware->setCallbacks(new FirmwareCallbacks());

  // Advertise before the battery read. A stuck power-chip transaction must
  // not leave the sensor invisible.
  NimBLEAdvertising *advertising = NimBLEDevice::getAdvertising();
  advertising->addServiceUUID(kServiceUuid);
  advertising->setName(deviceName);
  advertising->enableScanResponse(true);
  advertising->start();
  updateInfo();
}

static void moveMode(int direction) {
  menuIndex = (menuIndex + (direction < 0 ? kTabCount - 1 : 1)) % kTabCount;
  listOffset = 0;
  uiDirty = true;
}

static void moveList(int direction) {
  const int maxOffset = std::max(0, listCount() - listRows());
  if (direction < 0) {
    if (listOffset > 0) {
      --listOffset;
      uiDirty = true;
    }
  } else if (listOffset < maxOffset) {
    ++listOffset;
    uiDirty = true;
  }
}

// Scans for networks and opens the picker. Blocks for the scan (~2 s).
static void startWifiPick() {
  drawStatus("Scanning Wi-Fi...");
  wifiList.clear();
  const int16_t n = WiFi.scanNetworks(false, true);
  for (int16_t i = 0; i < n && wifiList.size() < 24; ++i) {
    const String s = WiFi.SSID(i);
    if (s.length() == 0) {
      continue;
    }
    bool dup = false;
    for (const String &have : wifiList) {
      if (have == s) {
        dup = true;
        break;
      }
    }
    if (!dup) {
      wifiList.push_back(s);
    }
  }
  WiFi.scanDelete();
  wifiSel = 0;
  wifiListOffset = 0;
  wifiInputSeeded = false;
  wifiUi = WifiUi::Pick;
  menuIndex = kTabLink;
  uiDirty = true;
}

// Keyboard handling while the Wi-Fi setup screens are open.
static void handleWifiKeys() {
  const Keyboard_Class::KeysState keys = M5Cardputer.Keyboard.keysState();
  static bool prevEnter = false, prevTab = false, prevDel = false;
  static std::vector<char> prevWord;
  static int heldDir = 0;
  static uint32_t repeatAt = 0;

  // First frame after opening: record the held keys without acting, so the key
  // that opened the screen is not immediately read as a press here.
  if (!wifiInputSeeded) {
    prevEnter = keys.enter;
    prevTab = keys.tab;
    prevDel = keys.del;
    prevWord.assign(keys.word.begin(), keys.word.end());
    wifiInputSeeded = true;
    return;
  }

  const bool enterEdge = keys.enter && !prevEnter;
  const bool tabEdge = keys.tab && !prevTab;
  const bool delEdge = keys.del && !prevDel;

  if (wifiUi == WifiUi::Pick) {
    const int dir = (keys.up && !keys.down) ? -1 : (keys.down && !keys.up) ? 1 : 0;
    if (dir != 0 && !wifiList.empty()) {
      const uint32_t now = millis();
      const bool first = dir != heldDir;
      if (first || static_cast<int32_t>(now - repeatAt) >= 0) {
        wifiSel += dir;
        if (wifiSel < 0) {
          wifiSel = 0;
        }
        if (wifiSel >= static_cast<int>(wifiList.size())) {
          wifiSel = static_cast<int>(wifiList.size()) - 1;
        }
        heldDir = dir;
        repeatAt = now + (first ? 360 : 140);
        uiDirty = true;
      }
    } else if (dir == 0) {
      heldDir = 0;
    }
    if (tabEdge) {
      wifiUi = WifiUi::None;
      uiDirty = true;
    } else if (enterEdge && !wifiList.empty()) {
      snprintf(wifiSsid, sizeof(wifiSsid), "%s", wifiList[wifiSel].c_str());
      wifiPass[0] = '\0';
      wifiPassLen = 0;
      wifiUi = WifiUi::Pass;
      uiDirty = true;
    }
  } else if (wifiUi == WifiUi::Pass) {
    if (tabEdge) {
      wifiUi = WifiUi::Pick;
      uiDirty = true;
    } else if (enterEdge) {
      saveWifiCreds(wifiSsid, wifiPass);
      WiFi.begin(wifiSsid, wifiPass);
      wifiConnectUntil = millis() + 12000;
      snprintf(wifiMsg, sizeof(wifiMsg), "Connecting...");
      wifiUi = WifiUi::Connecting;
      uiDirty = true;
    } else {
      if (delEdge && wifiPassLen > 0) {
        wifiPass[--wifiPassLen] = '\0';
        uiDirty = true;
      }
      for (const char c : keys.word) {
        if (std::find(prevWord.begin(), prevWord.end(), c) != prevWord.end()) {
          continue;
        }
        if (c >= 32 && c < 127 && wifiPassLen < sizeof(wifiPass) - 1) {
          wifiPass[wifiPassLen++] = c;
          wifiPass[wifiPassLen] = '\0';
          uiDirty = true;
        }
      }
    }
  } else if (wifiUi == WifiUi::Connecting) {
    if (tabEdge) {
      wifiUi = WifiUi::None;
      uiDirty = true;
    }
  }

  prevEnter = keys.enter;
  prevTab = keys.tab;
  prevDel = keys.del;
  prevWord.assign(keys.word.begin(), keys.word.end());
}

// Watches a pending connection and reports the outcome.
static void serviceWifiUi() {
  if (wifiUi != WifiUi::Connecting) {
    return;
  }
  if (WiFi.status() == WL_CONNECTED) {
    wifiUi = WifiUi::None;
    showBanner("Wi-Fi OK");
    uiDirty = true;
  } else if (static_cast<int32_t>(millis() - wifiConnectUntil) >= 0) {
    snprintf(wifiMsg, sizeof(wifiMsg), "Failed, try again");
    wifiUi = WifiUi::Pass;
    showBanner("Wi-Fi failed");
    uiDirty = true;
  }
}

static void drawLinkTab() {
  auto &d = M5Cardputer.Display;
  if (wifiUi == WifiUi::Pick) {
    const int rows = std::max(1, static_cast<int>(d.height() - kListTop) / 12);
    if (wifiSel < wifiListOffset) {
      wifiListOffset = wifiSel;
    }
    if (wifiSel >= wifiListOffset + rows) {
      wifiListOffset = wifiSel - rows + 1;
    }
    int y = kListTop;
    for (int i = wifiListOffset; i < static_cast<int>(wifiList.size()); ++i) {
      if (y > d.height() - 10) {
        break;
      }
      const bool on = i == wifiSel;
      d.fillRect(0, y - 1, d.width(), 12, on ? TFT_WHITE : TFT_BLACK);
      d.setTextColor(on ? TFT_BLACK : TFT_WHITE, on ? TFT_WHITE : TFT_BLACK);
      d.setCursor(4, y);
      char shown[36];
      snprintf(shown, sizeof(shown), "%.34s", wifiList[i].c_str());
      d.print(shown);
      y += 12;
    }
    if (wifiList.empty()) {
      d.setTextColor(TFT_DARKGREY, TFT_BLACK);
      d.setCursor(4, kListTop);
      d.print("No networks found");
    }
    return;
  }
  d.setTextColor(TFT_WHITE, TFT_BLACK);
  d.setCursor(4, kListTop);
  if (wifiUi == WifiUi::Pass) {
    char ssidLine[40];
    snprintf(ssidLine, sizeof(ssidLine), "%.34s", wifiSsid);
    d.print(ssidLine);
    d.setCursor(4, kListTop + 16);
    d.print("Pass ");
    char masked[34];
    const uint8_t dots = wifiPassLen < 32 ? wifiPassLen : 32;
    for (uint8_t i = 0; i < dots; ++i) {
      masked[i] = '*';
    }
    masked[dots] = '\0';
    d.print(masked);
    d.print("_");
    return;
  }
  if (wifiUi == WifiUi::Connecting) {
    d.print(wifiMsg);
    d.setCursor(4, kListTop + 16);
    d.print(wifiSsid);
    return;
  }
  if (WiFi.status() == WL_CONNECTED) {
    d.print(WiFi.SSID().c_str());
    d.setTextColor(TFT_DARKGREY, TFT_BLACK);
    d.setCursor(4, kListTop + 16);
    d.print(WiFi.localIP().toString().c_str());
    return;
  }
  d.setTextColor(TFT_DARKGREY, TFT_BLACK);
  d.print(wifiHaveCreds ? wifiSsid : "No network");
}

static void handleKeyboard() {
  if (scanInProgress || otaActive || otaJobBusy) {
    return;
  }
  const Keyboard_Class::KeysState keys = M5Cardputer.Keyboard.keysState();
  // Arrows only with Fn held, on the ; , . / legends. A bare key does
  // nothing, so a stray press in a pocket does not move the list.
  const bool up = keys.up;
  const bool down = keys.down;
  const bool left = keys.left;
  const bool right = keys.right;

  int scroll = 0;
  int mode = 0;
  if (left && !right) {
    mode = -1;
  } else if (right && !left) {
    mode = 1;
  } else if (up && !down) {
    scroll = -1;
  } else if (down && !up) {
    scroll = 1;
  }

  const int gesture = scroll * 2 + mode;
  static int held = 0;
  static uint32_t repeatAt = 0;
  if (gesture != 0) {
    const uint32_t now = millis();
    const bool first = gesture != held;
    if (first || static_cast<int32_t>(now - repeatAt) >= 0) {
      if (mode != 0) {
        moveMode(mode);
      } else if (menuIndex != kTabLink) {
        moveList(scroll);
      }
      held = gesture;
      repeatAt = now + (first ? 360 : 140);
    }
  } else {
    held = 0;
  }

  // Joining a network owns Enter, Tab and the vertical arrows. Horizontal
  // arrows already switched tabs above, so a half-typed password survives.
  static bool enterHeld = false;
  if (menuIndex == kTabLink && wifiUi != WifiUi::None) {
    handleWifiKeys();
  } else if (keys.enter && !enterHeld) {
    if (menuIndex == kTabLink) {
      startWifiPick();
    } else {
      taskENTER_CRITICAL(&radioMux);
      requestedKind = static_cast<uint8_t>(menuIndex);
      scanRequested = true;
      taskEXIT_CRITICAL(&radioMux);
    }
  }
  enterHeld = keys.enter;
}

static void handleSideButton() {
  if (scanInProgress || otaActive || otaJobBusy || !M5Cardputer.BtnA.wasPressed()) {
    return;
  }
  bool saved = false;
  if (menuIndex == 0) {
    saved = saveWifi();
  } else if (menuIndex == 1) {
    saved = saveLan();
  } else if (menuIndex == 2) {
    saved = saveBle();
  } else if (menuIndex == 3) {
    saved = saveLora();
  }
  GpsFix located;
  const bool withGps = saved && loraCapFix(located);
  showBanner(!saved ? "Nothing to save" : withGps ? "Saved + GPS" : "Saved");
  if (saved && isConnected()) {
    queueUnsent();
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
  loraCapBegin();

  otaCrcOk = otaCrcSelfCheck();
  otaUpdatable = otaCrcOk && otaAvailable();
  xTaskCreatePinnedToCore(otaTaskMain, "ota", 16384, nullptr, 1, &otaTask, 1);
  setupBle();
  loadStored();
  loadWifiCreds();
  if (wifiHaveCreds) {
    // Bring the saved network up in the background so the LAN scan is ready.
    WiFi.begin(wifiSsid, wifiPass);
  }
  uiDirty = true;
}

void loop() {
  M5Cardputer.update();
  if (otaRebootPending && static_cast<int32_t>(millis() - otaRebootAt) >= 0) {
    ESP.restart();
  }
  if (!otaActive) {
    handleKeyboard();
    handleSideButton();
    serviceWifiUi();
  }

  if (intervalChanged) {
    intervalChanged = false;
    updateInfo();
    uiDirty = true;
  }
  if (linkChanged) {
    linkChanged = false;
    uiDirty = true;
    if (isConnected()) {
      // Captures and the GPS fix wait for the Results subscription (see
      // captureFlushPending); a notify before that is dropped.
      // iOS keeps a copy of the service list per device and reuses it, so a
      // phone that met firmware without the Firmware characteristic never
      // sees it. A Service Changed indication makes it look again; it is sent
      // once the phone has had time to subscribe to it.
      serviceChangedAt = millis() + 1500;
    } else {
      outbox.clear();
      for (StoredCapture &cap : stored) {
        cap.queued = false;
      }
    }
  }
  if (captureFlushPending && resultsSubscribed && isConnected()) {
    captureFlushPending = false;
    queueUnsent();
    sendGpsFix();
  }
  if (serviceChangedAt != 0 && static_cast<int32_t>(millis() - serviceChangedAt) >= 0) {
    serviceChangedAt = 0;
    if (isConnected() && server != nullptr) {
      server->sendServiceChangedIndication();
    }
  }
  watchBattery();
  loraCapPoll();
  if (loraCapAttached()) {
    char nextGps[24];
    loraCapFormat(nextGps, sizeof(nextGps));
    if (std::strcmp(nextGps, gpsLabel) != 0) {
      snprintf(gpsLabel, sizeof(gpsLabel), "%s", nextGps);
      uiDirty = true;
      updateInfo();
    }
    static uint32_t nextGpsNotify = 0;
    if (isConnected() && static_cast<int32_t>(millis() - nextGpsNotify) >= 0) {
      sendGpsFix();
      nextGpsNotify = millis() + 5000;
    }
  }
  drainAcks();
  if (banner[0] != '\0' && static_cast<int32_t>(millis() - bannerUntil) >= 0) {
    banner[0] = '\0';
    uiDirty = true;
  }

  const bool due = scanIntervalSec > 0 && isConnected() && !otaActive && !otaJobBusy &&
                   millis() - lastScanMs >= static_cast<uint32_t>(scanIntervalSec) * 1000;
  bool startScan = false;
  uint8_t kind = 0;
  taskENTER_CRITICAL(&radioMux);
  if (due && !scanRequested) {
    requestedKind = 0;
  }
  const bool joinBusy =
      wifiUi == WifiUi::Connecting || (menuIndex == kTabLink && wifiUi != WifiUi::None);
  if ((scanRequested || due) && !otaActive && !otaJobBusy && !joinBusy) {
    scanRequested = false;
    kind = requestedKind;
    scanInProgress = true;
    startScan = true;
  } else if (otaActive) {
    scanRequested = false;
  }
  taskEXIT_CRITICAL(&radioMux);
  if (startScan) {
    if (kind == 1) {
      runLanScan();
    } else if (kind == 2) {
      runBluetoothScan();
    } else if (kind == 3) {
      runLoraScan();
    } else {
      runScan();
    }
    scanInProgress = false;
    uiDirty = true;
  }
  pumpOutbox();

  if (uiDirty) {
    uiDirty = false;
    drawUi();
  }
  delay(10);
}
