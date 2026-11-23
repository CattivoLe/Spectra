// Spectra sensor firmware.
//
// The Cardputer build draws on its screen and reads its keyboard. The
// ESP32-C5 and WROOM-32 builds have neither: the phone is the only control.
// Scans for Wi-Fi access points and nearby stations (clients), then sends the
// results to the Spectra app over BLE. The wire format is described in
// docs/protocol.md.

#include "Board.h"
#include "Bytes.h"
#include "Crc32.h"
#include "LoraCap.h"
#include "Protocol.h"
#include "Stream.h"

#if SPECTRA_HAS_UI
#include <M5Cardputer.h>
#include <HWCDC.h>
#endif
#include <ESPmDNS.h>
#include <HTTPClient.h>
#include <NimBLEDevice.h>
#include <Preferences.h>
#include <Update.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <time.h>
#include <esp_mac.h>
#include <esp_ota_ops.h>
#include <esp_task_wdt.h>
#include <esp_timer.h>
#include <esp_wifi.h>
#include <algorithm>
#include <cstring>
#include <vector>

// The phone app finds this tag in a firmware.bin to show which version it
// carries. The version below points into it, so the linker keeps the tag.
static constexpr char kFirmwareTag[] = "SPECTRA-FW:0.11.0";
static constexpr const char* kFirmwareVersion = kFirmwareTag + 11;

// Wire protocol constants (UUIDs, command/message/update bytes) live in
// Protocol.h; the byte-packing helpers in Bytes.h.

// Time spent on each channel during an active scan. 13 channels at 120 ms
// keeps a full sweep under two seconds.
static constexpr uint32_t kMsPerChannel = 120;

// Fixed identity for this firmware. Other boards ship with their own name.
static constexpr char kSensorName[] = SPECTRA_SENSOR_NAME;

// Channels promiscuous mode listens on for clients, besides any other channel
// an access point was heard on. How long it stays is in Stream.h.
static constexpr uint8_t kSniffChannels[] = {
    1, 6, 11, 2, 3, 4, 5, 7, 8, 9, 10, 12, 13,
#if SPECTRA_WIFI_5G
    // Non-DFS 5 GHz channels. A channel the country rejects is skipped.
    36, 40, 44, 48, 149, 153, 157, 161,
#endif
};

// Task watchdog. loop() and the long scans it runs are fed below. The timeout
// sits well above the longest legitimate blocking stretch — a full LoRa sweep
// (~37 s) is fed per channel, a Bluetooth scan blocks ~6 s in one call — so it
// only trips on a genuine hang, rebooting the sensor instead of leaving it dead.
static constexpr uint32_t kWatchdogTimeoutMs = 20000;

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
static char peerName[32] = "";

// Written from the NimBLE host task, read from loop().
static volatile bool scanRequested = false;
static volatile uint8_t scanIntervalSec = 30;
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

// The interval patrol stays off after boot until a Wi-Fi scan is asked for,
// from the keyboard or the phone. From then on the interval drives it.
static bool patrolArmed = false;
static bool bleScanned = false;
// Set by the first Bluetooth scan asked for by hand. The patrol then
// alternates Wi-Fi and Bluetooth, so a tracker can be seen scan after scan.
static bool blePatrolArmed = false;
static bool patrolNextBle = false;

// A tracker in this many Bluetooth scans in a row is treated as following.
static constexpr uint8_t kTrackerStreak = 3;
struct TrackerSighting {
  uint8_t addr[6];
  uint8_t streak;
  bool alerted;
};
static std::vector<TrackerSighting> trackerSightings;

// Alert mode, set from the phone and kept in NVS. See kAlertSound / kAlertLed.
static volatile uint8_t alertFlags = kAlertSound | kAlertLed;
static volatile bool alertsChanged = false;

// Watched addresses, first octet first. Written from the NimBLE host task
// under watchMux, read from loop().
static uint8_t watchAddrs[kWatchMax][6];
static volatile uint8_t watchCount = 0;
static volatile bool watchChanged = false;
static portMUX_TYPE watchMux = portMUX_INITIALIZER_UNLOCKED;

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
// Item trackers recognised from the advertisement.
enum class Tracker : uint8_t { None, FindMy, SmartTag, Tile };

struct KeptBle {
  uint8_t addr[6];
  uint8_t addrType;
  int8_t rssi;
  char name[21];
  Tracker tracker;
  uint8_t streak;  // consecutive Bluetooth scans this tracker was in
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

// Wi-Fi join for LAN scans. The Cardputer stays unassociated until the user
// picks a network. A network with a stored password, or an open one, joins
// straight from the picker; otherwise the password is typed. Stored passwords
// are never drawn, and nothing connects on its own.
enum class WifiUi : uint8_t { None, Pick, Pass, Connecting };
static WifiUi wifiUi = WifiUi::None;
struct PickRow {
  String ssid;
  int8_t rssi;
  bool open;
};
static std::vector<PickRow> wifiList;
// -1 is the "Scan again" row above the list.
static int wifiSel = 0;
static int wifiListOffset = 0;
static char wifiSsid[33] = "";
static char wifiPass[65] = "";
static uint8_t wifiPassLen = 0;
static char wifiMsg[28] = "";
static uint32_t wifiConnectUntil = 0;
// The join in flight used a password the sensor already had.
static bool wifiUsedStored = false;
// Selected button on the Join tab's main screen.
static int joinSel = 0;
// Joins not yet accepted by the phone. Eight is the unsent buffer: a full one
// drops the oldest. The phone keeps the history and sends it back to draw.
struct KnownWifi {
  char ssid[33];
  char pass[65];
  uint8_t epoch;
};
static constexpr int kPendingMax = 8;
static constexpr int kPhoneMax = 80;
static std::vector<KnownWifi> knownWifi;
static std::vector<KnownWifi> phoneWifi;
static uint8_t nextEpoch = 1;
static uint8_t inFlightEpoch = 0;
static bool knownQueued = false;

struct WifiOp {
  uint8_t op;
  char ssid[33];
  char pass[65];
};
static constexpr int kWifiOpMax = 80;
static WifiOp wifiOps[kWifiOpMax];
static uint8_t wifiOpCount = 0;
static portMUX_TYPE wifiOpMux = portMUX_INITIALIZER_UNLOCKED;
// Swallows the key that opened the setup so its release is not read as a press.
static bool wifiInputSeeded = false;

static uint16_t lastStationCount = 0;
static uint16_t lastProbeCount = 0;
// Written from the internet-check task as well as loop(), so a cached read
// would miss the result.
static volatile bool uiDirty = true;

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
// Per-AP volume, scaled to kTrafficWindowMs when the sweep moves on.
static std::vector<TrafficEntry> capturedTraffic;
// The same two counts for the channel being listened to. Written by the
// promiscuous callback, taken by loop() at the end of each channel.
static TrafficEntry passTraffic[64];
static size_t passTrafficCount = 0;
static ClientPass<48> passClients;
static portMUX_TYPE sniffMux = portMUX_INITIALIZER_UNLOCKED;
static volatile uint8_t sniffChannel = 0;    // 0: between channels, nothing is counted
static volatile uint32_t sniffStartedMs = 0;
// What each client sent in the latest sweep, and who has kept it up.
static ClientSweep<64> clientSweep;
static StreamWatch<16> streamWatch;
static bool newStream = false;
// Most per-client records sent per scan, busiest first.
static constexpr size_t kClientTrafficMax = 32;
// Where each access point of the latest scan lives.
struct ApChannel { uint8_t bssid[6]; uint8_t channel; };
static std::vector<ApChannel> apChannels;
static std::vector<VendorIEEntry> capturedVendorIEs;
// BSSIDs of hidden APs from the latest scan, for filtering probe responses.
struct HiddenAP { uint8_t bssid[6]; };
static std::vector<HiddenAP> hiddenAPs;
static uint8_t ownMac[6];

// Negotiated ATT MTU for the live connection, updated from onMTUChange. Raw
// frames are split to fit MTU - 3 (the ATT notification header). 23 is the
// BLE default both phones raise during connection setup.
static volatile uint16_t attMtu = 23;

// ---------------------------------------------------------------------------
// Raw-frame capture (0x24)
// ---------------------------------------------------------------------------
//
// A short, thinned sniff of one channel: the promiscuous callback copies each
// frame into a fixed ring of slots, and loop() drains them to the phone as
// 0x24 notifications. BLE moves far less than the air does, so the ring drops
// the oldest frame when the phone falls behind — this is a sample, not a full
// dump. The phone caps the total and sends stop.
struct RawSlot {
  uint16_t capLen;   // bytes stored here (<= kRawSnapLen)
  uint16_t origLen;  // true frame length on the air
  uint32_t ts;       // microseconds since boot, from esp_timer
  uint16_t seq;      // per-frame id, shared by every fragment
  uint8_t channel;
  int8_t rssi;
  uint8_t data[kRawSnapLen];
};
static constexpr int kRawSlots = 48;
static RawSlot rawSlots[kRawSlots];
static portMUX_TYPE rawMux = portMUX_INITIALIZER_UNLOCKED;
static int rawHead = 0;             // next write slot
static int rawTail = 0;             // next read slot
static volatile int rawCount = 0;   // filled slots
static volatile bool rawActive = false;
static volatile bool rawStartPending = false;
static volatile bool rawStopPending = false;
static volatile uint8_t rawChannel = 0;   // locked channel, 0 = current
static volatile bool rawFilter = false;   // keep only frames touching rawBssid
static uint8_t rawBssid[6];
static uint16_t rawSeq = 0;
static uint32_t rawDropped = 0;

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

/// The channel the latest scan heard a BSSID on, 0 when it did not.
static uint8_t homeChannel(const uint8_t *bssid) {
  for (const auto &ap : apChannels) {
    if (macEqual(ap.bssid, bssid)) return ap.channel;
  }
  return 0;
}

/// Accumulate one data frame: its volume for the access point, and its
/// direction for the client.
static void recordTraffic(const uint8_t *client, const uint8_t *bssid, bool uplink,
                          uint8_t subtype, uint16_t frameLen, int8_t rssi) {
  const uint8_t channel = sniffChannel;
  if (channel == 0) return;
  const uint32_t elapsed = millis() - sniffStartedMs;
  // Group-addressed downlink has no client; our own frames are not findings.
  const bool countClient = !isBroadcast(client) && !isMulticast(client) && !macEqual(client, ownMac) &&
                           dataSubtypeCarriesData(subtype);
  // A strong frame leaks onto neighbouring channels, where part of it is lost.
  const uint8_t home = countClient ? homeChannel(bssid) : 0;

  taskENTER_CRITICAL(&sniffMux);
  size_t at = 0;
  while (at < passTrafficCount && !macEqual(passTraffic[at].bssid, bssid)) ++at;
  if (at == passTrafficCount && passTrafficCount < sizeof(passTraffic) / sizeof(passTraffic[0])) {
    memcpy(passTraffic[at].bssid, bssid, 6);
    passTraffic[at].bytes = 0;
    passTraffic[at].packets = 0;
    ++passTrafficCount;
  }
  if (at < passTrafficCount) {
    passTraffic[at].bytes += frameLen;
    if (passTraffic[at].packets < 0xFFFF) passTraffic[at].packets++;
  }
  if (countClient && (home == 0 || home == channel)) {
    if (uplink) {
      passClients.uplink(client, bssid, frameLen, rssi, elapsed);
    } else {
      passClients.downlink(client, bssid, frameLen);
    }
  }
  taskEXIT_CRITICAL(&sniffMux);
}

/// Move one channel's counts into the sweep, now that it is known how long the
/// channel was really listened to.
static void foldPass(uint8_t channel, uint32_t listenMs) {
  static TrafficEntry traffic[sizeof(passTraffic) / sizeof(passTraffic[0])];
  static ClientPass<48> clients;
  taskENTER_CRITICAL(&sniffMux);
  const size_t trafficCount = passTrafficCount;
  memcpy(traffic, passTraffic, trafficCount * sizeof(TrafficEntry));
  clients = passClients;
  passTrafficCount = 0;
  passClients.count = 0;
  taskEXIT_CRITICAL(&sniffMux);

  if (listenMs == 0) return;
  for (size_t i = 0; i < trafficCount; ++i) {
    const uint32_t bytes = scaleToWindow(traffic[i].bytes, listenMs);
    const uint32_t packets = scaleToWindow(traffic[i].packets, listenMs);
    bool known = false;
    for (auto &t : capturedTraffic) {
      if (macEqual(t.bssid, traffic[i].bssid)) {
        t.bytes += bytes;
        t.packets = static_cast<uint16_t>(std::min<uint32_t>(t.packets + packets, 0xFFFF));
        known = true;
        break;
      }
    }
    if (known || capturedTraffic.size() >= 64) continue;
    TrafficEntry entry = traffic[i];
    entry.bytes = bytes;
    entry.packets = static_cast<uint16_t>(std::min<uint32_t>(packets, 0xFFFF));
    capturedTraffic.push_back(entry);
  }
  clientSweep.fold(clients.rows, clients.count, channel,
                   static_cast<uint16_t>(std::min<uint32_t>(listenMs, 0xFFFF)));
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

/// Store one frame in the raw-capture ring. Runs inside the promiscuous
/// callback; the critical section is only the copy, and the oldest frame is
/// dropped when the ring is full so the radio task never blocks on the phone.
static void IRAM_ATTR recordRawFrame(const uint8_t *frame, uint16_t sigLen, int8_t rssi) {
  if (rawFilter) {
    const uint8_t *a1 = frame + 4, *a2 = frame + 10, *a3 = frame + 16;
    const bool hit = macEqual(a1, rawBssid) ||
                     (sigLen >= 16 && macEqual(a2, rawBssid)) ||
                     (sigLen >= 22 && macEqual(a3, rawBssid));
    if (!hit) return;
  }
  uint16_t capLen = sigLen;
  if (capLen > kRawSnapLen) capLen = kRawSnapLen;
  const uint32_t ts = static_cast<uint32_t>(esp_timer_get_time());

  taskENTER_CRITICAL_ISR(&rawMux);
  if (rawCount >= kRawSlots) {
    // Full: drop the oldest so the newest still lands.
    rawTail = (rawTail + 1) % kRawSlots;
    rawCount--;
    rawDropped++;
  }
  RawSlot &slot = rawSlots[rawHead];
  slot.capLen = capLen;
  slot.origLen = sigLen;
  slot.ts = ts;
  slot.seq = rawSeq++;
  slot.channel = rawChannel;
  slot.rssi = rssi;
  memcpy(slot.data, frame, capLen);
  rawHead = (rawHead + 1) % kRawSlots;
  rawCount++;
  taskEXIT_CRITICAL_ISR(&rawMux);
}

static void IRAM_ATTR promiscuousCallback(void *buf, wifi_promiscuous_pkt_type_t type) {
  if (type != WIFI_PKT_MGMT && type != WIFI_PKT_DATA) return;

  const auto *pkt = static_cast<const wifi_promiscuous_pkt_t *>(buf);
  if (pkt->rx_ctrl.sig_len < 24) return;

  // Raw capture takes over the callback: just copy frames, skip the per-AP
  // bookkeeping the normal sniff does.
  if (rawActive) {
    recordRawFrame(pkt->payload, pkt->rx_ctrl.sig_len, pkt->rx_ctrl.rssi);
    return;
  }

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
    const uint8_t ds = frame[1] & 0x03;

    if (ds == 0x01) {
      // ToDS=1, FromDS=0: client → AP.
      // Addr1 = BSSID, Addr2 = source (client), Addr3 = destination.
      recordStation(addr2, addr1, rssi, true);
      recordTraffic(addr2, addr1, true, subtype, pkt->rx_ctrl.sig_len, rssi);
    } else if (ds == 0x02) {
      // ToDS=0, FromDS=1: AP → client.
      // Addr1 = destination (client), Addr2 = BSSID, Addr3 = source.
      recordStation(addr1, addr2, rssi, true);
      recordTraffic(addr1, addr2, false, subtype, pkt->rx_ctrl.sig_len, rssi);
    }
  }
}

// ---------------------------------------------------------------------------
// BLE protocol
// ---------------------------------------------------------------------------

static int readBatteryPercent() {
#if SPECTRA_HAS_BATTERY
  const int level = M5Cardputer.Power.getBatteryLevel();
  if (level < 0) {
    return -1;
  }
  return std::min(level, 100);
#else
  return -1;
#endif
}

// The Cardputer charger status pins are not wired to the ESP32, so
// isCharging() stays "unknown" and must not be used as a boolean. A USB host
// still announces itself with SOF packets; a bare power bank does not.
// Boards with no battery omit bat and chg from the info JSON.
static bool readBatteryCharging() {
#if SPECTRA_HAS_BATTERY
  if (M5Cardputer.Power.isCharging() == m5::Power_Class::is_charging) {
    return true;
  }
  return HWCDC::isPlugged();
#else
  return false;
#endif
}

static void appendRaw(char *out, size_t cap, const char *text) {
  const size_t used = strlen(out);
  const size_t add = strlen(text);
  if (used + add + 1 > cap) {
    return;
  }
  memcpy(out + used, text, add + 1);
}

static void appendQuoted(char *out, size_t cap, const char *text) {
  appendRaw(out, cap, "\"");
  char tmp[4];
  for (const unsigned char *p = reinterpret_cast<const unsigned char *>(text); *p != 0; ++p) {
    if (*p == '"' || *p == '\\') {
      tmp[0] = '\\';
      tmp[1] = static_cast<char>(*p);
      tmp[2] = '\0';
      appendRaw(out, cap, tmp);
    } else if (*p >= 32 && *p < 127) {
      tmp[0] = static_cast<char>(*p);
      tmp[1] = '\0';
      appendRaw(out, cap, tmp);
    }
  }
  appendRaw(out, cap, "\"");
}

static void updateInfo() {
  char json[1100];
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
  char knownJson[700];
  knownJson[0] = '\0';
  if (!phoneWifi.empty() || !knownWifi.empty()) {
    appendRaw(knownJson, sizeof(knownJson), ",\"known\":[");
    bool any = false;
    auto appendName = [&](const char *ssid) {
      char piece[80];
      piece[0] = '\0';
      if (any) {
        appendRaw(piece, sizeof(piece), ",");
      }
      appendQuoted(piece, sizeof(piece), ssid);
      if (strlen(knownJson) + strlen(piece) + 2 >= sizeof(knownJson)) {
        return false;
      }
      appendRaw(knownJson, sizeof(knownJson), piece);
      any = true;
      return true;
    };
    for (const KnownWifi &row : phoneWifi) {
      if (!appendName(row.ssid)) {
        break;
      }
    }
    for (const KnownWifi &row : knownWifi) {
      bool seen = false;
      for (const KnownWifi &have : phoneWifi) {
        if (std::strcmp(have.ssid, row.ssid) == 0) {
          seen = true;
          break;
        }
      }
      if (!seen && !appendName(row.ssid)) {
        break;
      }
    }
    appendRaw(knownJson, sizeof(knownJson), "]");
  }
  snprintf(json, sizeof(json),
           "{\"proto\":%u,\"fw\":\"%s\",\"name\":\"%s\",\"chip\":\"" SPECTRA_CHIP_NAME
           "\",\"bands\":[" SPECTRA_BANDS_JSON "],\"interval\":%u,"
           "\"alerts\":%u,\"outputs\":%u,\"watch\":%u%s%s%s%s}",
           kProtocolVersion, kFirmwareVersion, kSensorName, scanIntervalSec, alertFlags, SPECTRA_ALERT_OUTPUTS, watchCount,
           otaUpdatable ? ",\"ota\":true" : "", batJson, capJson, knownJson);
  infoChar->setValue(json);
}

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer *, NimBLEConnInfo &) override { linkChanged = true; }

  void onMTUChange(uint16_t mtu, NimBLEConnInfo &) override { attMtu = mtu; }

  void onDisconnect(NimBLEServer *, NimBLEConnInfo &, int) override {
    // The interval stays. The phone set it, and the patrol keeps using it.
    peerName[0] = '\0';
    linkChanged = true;
    resultsSubscribed = false;
    captureFlushPending = false;
    attMtu = 23;
    // A raw capture belongs to the phone that started it.
    if (rawActive) rawStopPending = true;
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
      case kCmdRememberWifi:
      case kCmdForgetWifi: {
        if (value.data() == nullptr || value.size() < 2) {
          break;
        }
        const uint8_t *bytes = value.data();
        const uint8_t ssidLen = bytes[1];
        if (ssidLen == 0 || ssidLen > 32 || value.size() < static_cast<size_t>(2 + ssidLen)) {
          break;
        }
        uint8_t passLen = 0;
        const uint8_t *pass = nullptr;
        if (value[0] == kCmdRememberWifi) {
          if (value.size() < static_cast<size_t>(3 + ssidLen)) {
            break;
          }
          passLen = bytes[2 + ssidLen];
          if (passLen > 64 || value.size() < static_cast<size_t>(3 + ssidLen + passLen)) {
            break;
          }
          pass = bytes + 3 + ssidLen;
        }
        taskENTER_CRITICAL(&wifiOpMux);
        if (wifiOpCount < kWifiOpMax) {
          WifiOp &slot = wifiOps[wifiOpCount++];
          slot.op = value[0] == kCmdRememberWifi ? 1 : 2;
          memcpy(slot.ssid, bytes + 2, ssidLen);
          slot.ssid[ssidLen] = '\0';
          if (pass != nullptr && passLen > 0) {
            memcpy(slot.pass, pass, passLen);
          }
          slot.pass[passLen] = '\0';
        }
        taskEXIT_CRITICAL(&wifiOpMux);
        break;
      }
      case kCmdAckKnown:
      case kCmdResetKnown: {
        taskENTER_CRITICAL(&wifiOpMux);
        if (wifiOpCount < kWifiOpMax) {
          WifiOp &slot = wifiOps[wifiOpCount++];
          slot.op = value[0] == kCmdAckKnown ? 3 : 4;
          slot.ssid[0] = '\0';
          slot.pass[0] = '\0';
        }
        taskEXIT_CRITICAL(&wifiOpMux);
        break;
      }
      case kCmdSetPeer: {
        if (value.size() < 2 || value.data() == nullptr) {
          break;
        }
        size_t n = value.size() - 1;
        if (n > 31) {
          n = 31;
        }
        memcpy(peerName, value.data() + 1, n);
        peerName[n] = '\0';
        linkChanged = true;
        break;
      }
      case kCmdStartRawCapture: {
        if (value.size() < 9 || value.data() == nullptr) {
          break;
        }
        const uint8_t *bytes = value.data();
        const uint8_t ch = bytes[1];
        if (!rawCaptureChannelAllowed(ch, SPECTRA_WIFI_5G != 0)) {
          break;
        }
        taskENTER_CRITICAL(&radioMux);
        rawChannel = ch;
        rawFilter = (bytes[2] & 0x01) != 0;
        memcpy(rawBssid, bytes + 3, 6);
        rawStartPending = true;
        rawStopPending = false;
        taskEXIT_CRITICAL(&radioMux);
        break;
      }
      case kCmdStopRawCapture:
        taskENTER_CRITICAL(&radioMux);
        rawStopPending = true;
        rawStartPending = false;
        taskEXIT_CRITICAL(&radioMux);
        break;
      case kCmdSetAlerts:
        if (value.size() >= 2) {
          alertFlags = value[1] & (kAlertSound | kAlertLed);
          alertsChanged = true;
        }
        break;
      case kCmdSetWatch: {
        if (value.size() < 2 || value.data() == nullptr) {
          break;
        }
        const uint8_t *bytes = value.data();
        uint8_t count = bytes[1];
        if (count > kWatchMax) {
          count = kWatchMax;
        }
        if (value.size() < static_cast<size_t>(2 + count * 6)) {
          break;
        }
        taskENTER_CRITICAL(&watchMux);
        memcpy(watchAddrs, bytes + 2, static_cast<size_t>(count) * 6);
        watchCount = count;
        watchChanged = true;
        taskEXIT_CRITICAL(&watchMux);
        break;
      }
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

static void sendClientTraffic(const ClientTraffic &c) {
  uint8_t msg[kClientTrafficLength];
  encodeClientTraffic(msg, scanId, c);
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
                             uint16_t probeCount, uint16_t durationMs, uint16_t clientCount) {
  uint8_t msg[13] = {kMsgScanFinished};
  putU16(msg + 1, scanId);
  putU16(msg + 3, apCount);
  putU16(msg + 5, durationMs);
  putU16(msg + 7, staCount);
  putU16(msg + 9, probeCount);
  putU16(msg + 11, clientCount);
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
#if SPECTRA_HAS_UI
  const auto dt = M5.Rtc.getDateTime();
  if (dt.date.year < 2024) {
    return 0;
  }
  tm clock = dt.get_tm();
  const time_t seconds = mktime(&clock);
#else
  const time_t seconds = time(nullptr);
#endif
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

// ---------------------------------------------------------------------------
// Alerts: speaker and RGB LED
// ---------------------------------------------------------------------------

// Red and yellow follow the phone's critical and warning banners.
struct Findings {
  bool critical = false;
  bool warning = false;
  bool open = false;
  bool watchedWifi = false;
  bool watchedBle = false;
  bool tracker = false;
};

struct Blink {
  uint8_t r, g, b;
  uint8_t times;
};
static constexpr int kBlinkQueueMax = 6;
static Blink blinkQueue[kBlinkQueueMax];
static int blinkCount = 0;
// Steps taken within the head entry: even lights it, odd turns it off.
static int blinkStep = 0;
static uint32_t blinkNextAt = 0;
static bool ledLit = false;

static void ledSet(uint8_t r, uint8_t g, uint8_t b) {
#if SPECTRA_HAS_UI
  if (!M5.Led.isEnabled()) {
    return;
  }
  M5.Led.setAllColor(r, g, b);
  ledLit = (r | g | b) != 0;
#elif defined(SPECTRA_RGB_LED_PIN)
  // Alerts should be seen across a room, like the Cardputer's at full
  // brightness, but this WS2812 sits bare on the board with no diffuser: half
  // power is still bright and does not glare up close.
  constexpr uint16_t kLevel = 128;
  rgbLedWrite(SPECTRA_RGB_LED_PIN, r * kLevel / 255, g * kLevel / 255, b * kLevel / 255);
  ledLit = (r | g | b) != 0;
#else
  (void)r;
  (void)g;
  (void)b;
  ledLit = false;
#endif
}

static void ledOff() {
  if (ledLit) {
    ledSet(0, 0, 0);
  }
}

static void queueBlink(uint8_t r, uint8_t g, uint8_t b, uint8_t times) {
  if (!(alertFlags & kAlertLed) || blinkCount >= kBlinkQueueMax) {
    return;
  }
  blinkQueue[blinkCount++] = {r, g, b, times};
}

static void serviceLed() {
  if (!(alertFlags & kAlertLed)) {
    blinkCount = 0;
    blinkStep = 0;
  }
  if (blinkCount == 0) {
    ledOff();
    return;
  }
  if (static_cast<int32_t>(millis() - blinkNextAt) < 0) {
    return;
  }
  const Blink &head = blinkQueue[0];
  const int steps = head.times * 2;
  if (blinkStep >= steps) {
    for (int i = 1; i < blinkCount; ++i) {
      blinkQueue[i - 1] = blinkQueue[i];
    }
    --blinkCount;
    blinkStep = 0;
    return;
  }
  if (blinkStep % 2 == 0) {
    ledSet(head.r, head.g, head.b);
    blinkNextAt = millis() + 180;
  } else {
    ledSet(0, 0, 0);
    blinkNextAt = millis() + (blinkStep + 1 == steps ? 500 : 160);
  }
  ++blinkStep;
}

static bool playTone(float hz, uint32_t ms) {
#if SPECTRA_HAS_UI
  M5Cardputer.Speaker.setVolume(200);
  if (!M5Cardputer.Speaker.tone(hz, ms)) {
    return false;
  }
  delay(ms + 50);
  esp_task_wdt_reset();
  return true;
#else
  (void)hz;
  (void)ms;
  return false;
#endif
}

static void announce(const Findings &f) {
  const bool threat = f.critical || f.warning;
  const bool watched = f.watchedWifi || f.watchedBle;
  if (!threat && !f.open && !watched && !f.tracker) {
    return;
  }
  char text[sizeof(banner)] = "";
  auto add = [&](const char *label) {
    if (text[0] != '\0') {
      appendRaw(text, sizeof(text), " + ");
    }
    appendRaw(text, sizeof(text), label);
  };
  if (f.critical) add("Threat");
  else if (f.warning) add("Warning");
  if (f.tracker) add("Tracker");
  if (watched) add(f.watchedBle && !f.watchedWifi ? "Watched BLE" : "Watched");
  if (f.open) add(threat || watched ? "open" : "Open network");
  showBanner(text);

  if (f.critical) queueBlink(255, 0, 0, 4);
  else if (f.warning) queueBlink(255, 150, 0, 3);
  if (f.tracker) queueBlink(255, 50, 0, 4);
  if (f.watchedBle) queueBlink(0, 0, 255, 3);
  if (f.watchedWifi) queueBlink(170, 0, 255, 3);
  if (f.open) queueBlink(0, 255, 0, 2);

  if (!(alertFlags & kAlertSound)) {
    return;
  }
  if (f.critical) {
    playTone(520, 90);
    playTone(520, 90);
    playTone(390, 180);
  } else if (f.warning) {
    playTone(520, 90);
    playTone(390, 140);
  }
  if (f.tracker) {
    playTone(880, 120);
    playTone(660, 120);
    playTone(880, 120);
    playTone(660, 120);
  }
  if (watched) {
    playTone(1319, 60);
    playTone(1760, 60);
    playTone(1319, 60);
  }
  if (f.open) {
    playTone(1568, 70);
    playTone(2093, 110);
  }
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

static int indexIn(const std::vector<KnownWifi> &list, const char *ssid) {
  if (ssid == nullptr) {
    return -1;
  }
  for (size_t i = 0; i < list.size(); ++i) {
    if (std::strcmp(list[i].ssid, ssid) == 0) {
      return static_cast<int>(i);
    }
  }
  return -1;
}

static bool isKnownSsid(const char *ssid) {
  return indexIn(phoneWifi, ssid) >= 0 || indexIn(knownWifi, ssid) >= 0;
}

static const char *knownPassword(const char *ssid) {
  const int fromPhone = indexIn(phoneWifi, ssid);
  if (fromPhone >= 0) {
    return phoneWifi[fromPhone].pass;
  }
  const int pending = indexIn(knownWifi, ssid);
  return pending >= 0 ? knownWifi[pending].pass : nullptr;
}

// One NVS blob. The record itself is the only ceiling: a value past this
// does not fit in a single flash entry, so the oldest network is dropped.
static constexpr size_t kWifiBlobMax = 4000;

static std::vector<uint8_t> encodeKnown() {
  std::vector<uint8_t> blob;
  const uint16_t count = static_cast<uint16_t>(knownWifi.size());
  blob.push_back(static_cast<uint8_t>(count & 0xFF));
  blob.push_back(static_cast<uint8_t>(count >> 8));
  for (const KnownWifi &row : knownWifi) {
    const uint8_t ssidLen = static_cast<uint8_t>(strlen(row.ssid));
    const uint8_t passLen = static_cast<uint8_t>(strlen(row.pass));
    blob.push_back(ssidLen);
    blob.insert(blob.end(), row.ssid, row.ssid + ssidLen);
    blob.push_back(passLen);
    blob.insert(blob.end(), row.pass, row.pass + passLen);
  }
  return blob;
}

static void decodeKnown(const uint8_t *blob, size_t len) {
  if (blob == nullptr || len < 2) {
    return;
  }
  const uint16_t count = static_cast<uint16_t>(blob[0] | (static_cast<uint16_t>(blob[1]) << 8));
  size_t at = 2;
  knownWifi.clear();
  for (uint16_t i = 0; i < count && at < len; ++i) {
    const uint8_t ssidLen = blob[at++];
    if (ssidLen == 0 || ssidLen > 32 || at + ssidLen >= len) {
      break;
    }
    KnownWifi row{};
    memcpy(row.ssid, blob + at, ssidLen);
    row.ssid[ssidLen] = '\0';
    at += ssidLen;
    const uint8_t passLen = blob[at++];
    if (passLen > 64 || at + passLen > len) {
      break;
    }
    memcpy(row.pass, blob + at, passLen);
    row.pass[passLen] = '\0';
    at += passLen;
    knownWifi.push_back(row);
  }
}

static void persistKnown() {
  while (knownWifi.size() > 1 && encodeKnown().size() > kWifiBlobMax) {
    knownWifi.pop_back();
  }
  const std::vector<uint8_t> blob = encodeKnown();
  Preferences prefs;
  prefs.begin("spectra", false);
  if (blob.size() <= kWifiBlobMax) {
    prefs.putBytes("wifi", blob.data(), blob.size());
  }
  for (int i = 0; i < 8; ++i) {
    char keyS[4];
    char keyP[4];
    snprintf(keyS, sizeof(keyS), "w%d", i);
    snprintf(keyP, sizeof(keyP), "p%d", i);
    prefs.remove(keyS);
    prefs.remove(keyP);
  }
  prefs.remove("wifiN");
  prefs.end();
}

static void saveInterval() {
  Preferences prefs;
  prefs.begin("spectra", false);
  prefs.putUChar("interval", scanIntervalSec);
  prefs.end();
}

static void saveAlerts() {
  Preferences prefs;
  prefs.begin("spectra", false);
  prefs.putUChar("alerts", alertFlags);
  prefs.end();
}

static void saveWatch() {
  uint8_t copy[kWatchMax][6];
  taskENTER_CRITICAL(&watchMux);
  const uint8_t count = watchCount;
  memcpy(copy, watchAddrs, static_cast<size_t>(count) * 6);
  taskEXIT_CRITICAL(&watchMux);
  Preferences prefs;
  prefs.begin("spectra", false);
  if (count == 0) {
    prefs.remove("watch");
  } else {
    prefs.putBytes("watch", copy, static_cast<size_t>(count) * 6);
  }
  prefs.end();
}

static void loadAlertPrefs() {
  Preferences prefs;
  prefs.begin("spectra", true);
  alertFlags = prefs.getUChar("alerts", kAlertSound | kAlertLed) & (kAlertSound | kAlertLed);
  const size_t len = prefs.getBytesLength("watch");
  const size_t count = std::min<size_t>(len / 6, kWatchMax);
  if (count > 0 && prefs.getBytes("watch", watchAddrs, count * 6) == count * 6) {
    watchCount = static_cast<uint8_t>(count);
  }
  prefs.end();
}

// `addr` in the order the phone writes it, first octet first.
static bool isWatched(const uint8_t *addr) {
  bool hit = false;
  taskENTER_CRITICAL(&watchMux);
  for (uint8_t i = 0; i < watchCount && !hit; ++i) {
    hit = memcmp(watchAddrs[i], addr, 6) == 0;
  }
  taskEXIT_CRITICAL(&watchMux);
  return hit;
}

static void touchKnownUi() {
  if (infoChar != nullptr) {
    updateInfo();
  }
  uiDirty = true;
}

static void rememberWifi(const char *ssid, const char *pass) {
  if (ssid == nullptr || ssid[0] == '\0') {
    return;
  }
  KnownWifi row{};
  snprintf(row.ssid, sizeof(row.ssid), "%s", ssid);
  snprintf(row.pass, sizeof(row.pass), "%s", pass == nullptr ? "" : pass);
  row.epoch = 0;
  const int existing = indexIn(knownWifi, ssid);
  if (existing >= 0) {
    knownWifi.erase(knownWifi.begin() + existing);
  } else if (static_cast<int>(knownWifi.size()) >= kPendingMax) {
    knownWifi.pop_back();
  }
  knownWifi.insert(knownWifi.begin(), row);
  persistKnown();
  touchKnownUi();
}

static void rememberFromPhone(const char *ssid, const char *pass) {
  if (ssid == nullptr || ssid[0] == '\0') {
    return;
  }
  KnownWifi row{};
  snprintf(row.ssid, sizeof(row.ssid), "%s", ssid);
  snprintf(row.pass, sizeof(row.pass), "%s", pass == nullptr ? "" : pass);
  const int existing = indexIn(phoneWifi, ssid);
  if (existing >= 0) {
    snprintf(phoneWifi[existing].pass, sizeof(phoneWifi[existing].pass), "%s", row.pass);
  } else if (static_cast<int>(phoneWifi.size()) < kPhoneMax) {
    // The phone sends newest first, so appending keeps that order.
    phoneWifi.push_back(row);
  }
  touchKnownUi();
}

static void forgetWifi(const char *ssid) {
  const int pending = indexIn(knownWifi, ssid);
  if (pending >= 0) {
    knownWifi.erase(knownWifi.begin() + pending);
    persistKnown();
  }
  const int fromPhone = indexIn(phoneWifi, ssid);
  if (fromPhone >= 0) {
    phoneWifi.erase(phoneWifi.begin() + fromPhone);
  }
  if (pending >= 0 || fromPhone >= 0) {
    touchKnownUi();
  }
}

static void resetPhoneWifi() {
  phoneWifi.clear();
  touchKnownUi();
}

static void ackPendingWifi() {
  if (inFlightEpoch != 0) {
    knownWifi.erase(std::remove_if(knownWifi.begin(), knownWifi.end(), [](const KnownWifi &row) {
                      return row.epoch == inFlightEpoch;
                    }),
                    knownWifi.end());
    persistKnown();
  }
  inFlightEpoch = 0;
  knownQueued = false;
  touchKnownUi();
}

static void queueKnown() {
  if (!isConnected() || knownQueued) {
    return;
  }
  uint8_t total = 0;
  for (const KnownWifi &row : knownWifi) {
    if (row.epoch == 0) {
      total++;
    }
  }
  if (total == 0) {
    return;
  }
  const uint8_t epoch = nextEpoch == 0 ? 1 : nextEpoch;
  nextEpoch = static_cast<uint8_t>(epoch + 1);
  if (nextEpoch == 0) {
    nextEpoch = 1;
  }
  uint8_t index = 0;
  for (KnownWifi &row : knownWifi) {
    if (row.epoch != 0) {
      continue;
    }
    row.epoch = epoch;
    uint8_t msg[1 + 2 + 1 + 32 + 1 + 64];
    const uint8_t ssidLen = static_cast<uint8_t>(strlen(row.ssid));
    const uint8_t passLen = static_cast<uint8_t>(strlen(row.pass));
    msg[0] = kMsgKnownWifi;
    msg[1] = index++;
    msg[2] = total;
    msg[3] = ssidLen;
    memcpy(msg + 4, row.ssid, ssidLen);
    msg[4 + ssidLen] = passLen;
    memcpy(msg + 5 + ssidLen, row.pass, passLen);
    outbox.emplace_back(msg, msg + 5 + ssidLen + passLen);
  }
  inFlightEpoch = epoch;
  knownQueued = true;
}

static void loadWifiCreds() {
  Preferences prefs;
  prefs.begin("spectra", true);
  scanIntervalSec = prefs.getUChar("interval", 30);
  bool migrated = false;
  const size_t len = prefs.getBytesLength("wifi");
  if (len >= 2) {
    std::vector<uint8_t> blob(len);
    if (prefs.getBytes("wifi", blob.data(), len) == len) {
      decodeKnown(blob.data(), blob.size());
    }
  }
  if (knownWifi.empty()) {
    const int count = prefs.getUChar("wifiN", 0);
    for (int i = 0; i < count && i < 8; ++i) {
      char keyS[4];
      char keyP[4];
      snprintf(keyS, sizeof(keyS), "w%d", i);
      snprintf(keyP, sizeof(keyP), "p%d", i);
      const String ssid = prefs.getString(keyS, "");
      const String pass = prefs.getString(keyP, "");
      if (ssid.length() == 0 || ssid.length() > 32) {
        continue;
      }
      KnownWifi row{};
      snprintf(row.ssid, sizeof(row.ssid), "%s", ssid.c_str());
      snprintf(row.pass, sizeof(row.pass), "%s", pass.c_str());
      knownWifi.push_back(row);
      migrated = true;
    }
  }
  prefs.end();
  while (static_cast<int>(knownWifi.size()) > kPendingMax) {
    knownWifi.pop_back();
    migrated = true;
  }
  if (migrated) {
    persistKnown();
  }
}

static void runLanScan() {
  keptLan.clear();
  lanNote[0] = '\0';
  if (WiFi.status() != WL_CONNECTED) {
    snprintf(lanNote, sizeof(lanNote), "no Wi-Fi");
    uiDirty = true;
    return;
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

// Find My accessories away from their owner (the long 0x12 payload an AirTag
// sends once separated), Samsung SmartTag and Tile.
static Tracker trackerKind(const NimBLEAdvertisedDevice &dev) {
  for (uint8_t i = 0; i < dev.getManufacturerDataCount(); ++i) {
    const std::string data = dev.getManufacturerData(i);
    if (data.size() >= 4 && static_cast<uint8_t>(data[0]) == 0x4C && data[1] == 0x00 &&
        data[2] == 0x12 && static_cast<uint8_t>(data[3]) >= 0x19) {
      return Tracker::FindMy;
    }
  }
  static const NimBLEUUID kSmartTag(static_cast<uint16_t>(0xFD5A));
  static const NimBLEUUID kTileA(static_cast<uint16_t>(0xFEED));
  static const NimBLEUUID kTileB(static_cast<uint16_t>(0xFEEC));
  auto has = [&](const NimBLEUUID &uuid) {
    if (dev.isAdvertisingService(uuid)) {
      return true;
    }
    for (uint8_t i = 0; i < dev.getServiceDataCount(); ++i) {
      if (dev.getServiceDataUUID(i) == uuid) {
        return true;
      }
    }
    return false;
  };
  if (has(kSmartTag)) {
    return Tracker::SmartTag;
  }
  if (has(kTileA) || has(kTileB)) {
    return Tracker::Tile;
  }
  return Tracker::None;
}

static const char *trackerLabel(Tracker kind) {
  switch (kind) {
    case Tracker::FindMy: return "Find My tag";
    case Tracker::SmartTag: return "SmartTag";
    case Tracker::Tile: return "Tile";
    default: return "";
  }
}

// Carries each tracker's streak over from the previous scan. One missed scan
// ends it. Returns true when a tracker has just reached kTrackerStreak.
static bool updateTrackerStreaks() {
  std::vector<TrackerSighting> next;
  bool following = false;
  for (KeptBle &dev : keptBle) {
    if (dev.tracker == Tracker::None || next.size() >= 16) {
      continue;
    }
    TrackerSighting seen{};
    memcpy(seen.addr, dev.addr, 6);
    seen.streak = 1;
    for (const TrackerSighting &before : trackerSightings) {
      if (macEqual(before.addr, dev.addr)) {
        seen.streak = static_cast<uint8_t>(std::min<int>(before.streak + 1, 99));
        seen.alerted = before.alerted;
        break;
      }
    }
    if (seen.streak >= kTrackerStreak && !seen.alerted) {
      seen.alerted = true;
      following = true;
    }
    dev.streak = seen.streak;
    next.push_back(seen);
  }
  trackerSightings = std::move(next);
  return following;
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
    row.tracker = trackerKind(*dev);
    keptBle.push_back(row);
  }
  scan->clearResults();
  bleScanned = true;

  // NimBLE keeps the address least significant octet first.
  Findings found;
  found.tracker = updateTrackerStreaks();
  for (const KeptBle &dev : keptBle) {
    uint8_t written[6];
    for (int i = 0; i < 6; ++i) {
      written[i] = dev.addr[5 - i];
    }
    if (isWatched(written)) {
      found.watchedBle = true;
      break;
    }
  }
  announce(found);
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
// Display. Headless boards keep the three calls the scanner makes.
// ---------------------------------------------------------------------------

#if SPECTRA_HAS_UI

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
    if (peerName[0] != '\0') {
      snprintf(header, sizeof(header), "%s  %.32s", deviceName, peerName);
    } else {
      snprintf(header, sizeof(header), "%s  linked", deviceName);
    }
  } else if (scanIntervalSec > 0 && !patrolArmed && !blePatrolArmed) {
    snprintf(header, sizeof(header), "%s  idle", deviceName);
  } else if (scanIntervalSec > 0) {
    snprintf(header, sizeof(header), "%s  every %us", deviceName,
             static_cast<unsigned>(scanIntervalSec));
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
      d.print(patrolArmed ? "No networks" : "Enter to scan");
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
      if (busy) {
        d.setTextColor(TFT_YELLOW, TFT_BLACK);
        d.setCursor(43, y);
        d.print("+");
      }
      d.setTextColor(TFT_WHITE, TFT_BLACK);
      d.setCursor(54, y);
      const char *name = ap.ssid[0] != '\0' ? ap.ssid : "(hidden)";
      char shown[28];
      snprintf(shown, sizeof(shown), "%.26s", name);
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
    if (keptBle.empty()) {
      d.setTextColor(TFT_DARKGREY, TFT_BLACK);
      d.setCursor(4, y);
      d.print(bleScanned ? "No devices" : "Enter to scan");
    }
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
      d.setCursor(34, y);
      if (dev.tracker != Tracker::None) {
        const bool following = dev.streak >= kTrackerStreak;
        d.setTextColor(following ? TFT_ORANGE : TFT_YELLOW, TFT_BLACK);
        char line[40];
        snprintf(line, sizeof(line), "%s%s x%u", following ? "! " : "",
                 dev.name[0] != '\0' ? dev.name : trackerLabel(dev.tracker), dev.streak);
        d.print(line);
      } else {
        d.setTextColor(TFT_WHITE, TFT_BLACK);
        d.print(dev.name[0] != '\0' ? dev.name : "(unnamed)");
      }
      y += 10;
    }
  }
}

#else

static void drawStatus(const char *) {}
static void drawLoraList() {}
static void drawUi() {}

#endif

// ---------------------------------------------------------------------------
// Scan
// ---------------------------------------------------------------------------

static bool isAllFf(const uint8_t *mac) {
  for (int i = 0; i < 6; ++i) {
    if (mac[i] != 0xFF) {
      return false;
    }
  }
  return true;
}

// The name a beacon advertised, or the one a probe response revealed for a
// hidden AP. Empty when the network is still unnamed.
static const char *apName(const KeptWifi &ap) {
  if (ap.ssid[0] != '\0') {
    return ap.ssid;
  }
  for (const RevealEntry &reveal : capturedReveals) {
    if (macEqual(reveal.bssid, ap.bssid) && reveal.ssid[0] != '\0') {
      return reveal.ssid;
    }
  }
  return "";
}

// The phone's DeauthEvent ranks: 0 none, 1 warning, 2 critical.
static int deauthSeverity(const DeauthEntry &event) {
  const bool broadcast = isAllFf(event.dst);
  if ((broadcast && event.count >= 3) || event.count >= 10) {
    return 2;
  }
  return broadcast || event.count >= 3 ? 1 : 0;
}

// One SSID on several access points is normal for mesh. Copies that differ
// in security are critical; copies that differ only in maker are a warning.
static int evilTwinSeverity() {
  int worst = 0;
  const int count = static_cast<int>(keptWifi.size());
  for (int i = 0; i < count && worst < 2; ++i) {
    const char *name = apName(keptWifi[i]);
    if (name[0] == '\0') {
      continue;
    }
    for (int j = i + 1; j < count; ++j) {
      if (std::strcmp(name, apName(keptWifi[j])) != 0) {
        continue;
      }
      if (keptWifi[j].auth != keptWifi[i].auth) {
        worst = 2;
      } else if (std::memcmp(keptWifi[j].bssid, keptWifi[i].bssid, 3) != 0) {
        worst = std::max(worst, 1);
      }
    }
  }
  return worst;
}

static void alertScanFindings() {
  Findings found;
  int worst = evilTwinSeverity();
  // A client that has just turned out to be streaming. Said once: a camera
  // that is always on would otherwise sound on every sweep.
  if (newStream) {
    worst = std::max(worst, 1);
  }
  for (const DeauthEntry &event : capturedDeauths) {
    worst = std::max(worst, deauthSeverity(event));
  }
  found.critical = worst >= 2;
  found.warning = worst == 1;
  for (const KeptWifi &ap : keptWifi) {
    if (ap.auth == WIFI_AUTH_OPEN) {
      found.open = true;
    }
    if (isWatched(ap.bssid)) {
      found.watchedWifi = true;
    }
  }
  announce(found);
}

/// Sniff Wi-Fi frames in promiscuous mode: which clients there are, and what
/// each of them sends and receives.
///
/// What this cannot hear is described in docs/protocol.md ("What the sensor
/// does not hear"). The DATA bit of the filter already lets aggregated frames
/// through: the driver tests MPDUs against DATA|DATA_MPDU and A-MPDU subframes
/// against DATA|DATA_AMPDU, on the ESP32, the S3 and the C5 alike.
static void sniffStations() {
  capturedStations.clear();
  capturedProbes.clear();
  capturedDeauths.clear();
  capturedReveals.clear();
  capturedTraffic.clear();
  capturedVendorIEs.clear();
  clientSweep.count = 0;
  taskENTER_CRITICAL(&sniffMux);
  passTrafficCount = 0;
  passClients.count = 0;
  taskEXIT_CRITICAL(&sniffMux);

  // Remember our own STA MAC so we filter it out.
  esp_read_mac(ownMac, ESP_MAC_WIFI_STA);

  // The fixed list, then any other channel an access point was heard on.
  // Listening sends nothing, so a DFS channel is fine; one the country rules
  // reject is skipped below.
  std::vector<uint8_t> channels(std::begin(kSniffChannels), std::end(kSniffChannels));
  std::vector<uint8_t> audible;
  for (const auto &ap : apChannels) {
    if (std::find(audible.begin(), audible.end(), ap.channel) == audible.end()) {
      audible.push_back(ap.channel);
    }
    if (std::find(channels.begin(), channels.end(), ap.channel) == channels.end()) {
      channels.push_back(ap.channel);
    }
  }
  const uint32_t extra = sniffExtraMs(audible.size());
  uint8_t surprises = 0;

  wifi_promiscuous_filter_t filt = {
      .filter_mask = WIFI_PROMIS_FILTER_MASK_MGMT | WIFI_PROMIS_FILTER_MASK_DATA};
  esp_wifi_set_promiscuous_filter(&filt);
  esp_wifi_set_promiscuous_rx_cb(promiscuousCallback);
  esp_wifi_set_promiscuous(true);

  for (uint8_t ch : channels) {
    if (esp_wifi_set_channel(ch, WIFI_SECOND_CHAN_NONE) != ESP_OK) {
      continue;
    }
    const uint32_t started = millis();
    sniffStartedMs = started;
    sniffChannel = ch;
    delay(kSniffBaseMs);
    if (std::find(audible.begin(), audible.end(), ch) != audible.end()) {
      delay(extra);
    } else if (surprises < kSniffSurprisesMax) {
      taskENTER_CRITICAL(&sniffMux);
      const bool sending = passClients.anySending();
      taskEXIT_CRITICAL(&sniffMux);
      if (sending) {
        ++surprises;
        delay(kSniffSurpriseMs);
      }
    }
    const uint32_t listened = millis() - started;
    sniffChannel = 0;
    foldPass(ch, listened);
    esp_task_wdt_reset();
  }

  esp_wifi_set_promiscuous(false);
  clientSweep.sort();
  newStream = streamWatch.note(clientSweep.rows, clientSweep.count);
}

// Send one captured frame, split into as many 0x24 notifications as the ATT
// MTU needs. Returns false if a notification could not be delivered, so the
// caller leaves the frame queued for the next drain.
static bool sendRawFrame(const RawSlot &slot) {
  // Header is 13 bytes on the first fragment, 5 on the rest.
  int maxPayload = static_cast<int>(attMtu) - 3;
  if (maxPayload < 20) maxPayload = 20;
  if (maxPayload > 512) maxPayload = 512;
  const int firstCap = maxPayload - 13;
  const int restCap = maxPayload - 5;
  if (firstCap < 1 || restCap < 1) return false;

  // How many fragments this frame needs.
  uint8_t fragCount = 1;
  if (slot.capLen > firstCap) {
    fragCount = 1 + static_cast<uint8_t>((slot.capLen - firstCap + restCap - 1) / restCap);
  }

  uint8_t msg[3 + 512];
  int sent = 0;
  for (uint8_t frag = 0; frag < fragCount; ++frag) {
    int n = 0;
    msg[n++] = kMsgRawFrame;
    putU16(msg + n, slot.seq);
    n += 2;
    msg[n++] = frag;
    msg[n++] = fragCount;
    int cap;
    if (frag == 0) {
      putU32(msg + n, slot.ts);
      n += 4;
      msg[n++] = slot.channel;
      msg[n++] = static_cast<uint8_t>(slot.rssi);
      putU16(msg + n, slot.origLen);
      n += 2;
      cap = firstCap;
    } else {
      cap = restCap;
    }
    int chunk = slot.capLen - sent;
    if (chunk > cap) chunk = cap;
    memcpy(msg + n, slot.data + sent, chunk);
    n += chunk;
    sent += chunk;
    if (!sendMessage(msg, n)) return false;
  }
  return true;
}

static void startRawCapture() {
  // Clear any leftover frames from a previous run.
  taskENTER_CRITICAL(&rawMux);
  rawHead = rawTail = rawCount = 0;
  rawSeq = 0;
  rawDropped = 0;
  taskEXIT_CRITICAL(&rawMux);

  esp_read_mac(ownMac, ESP_MAC_WIFI_STA);
  WiFi.mode(WIFI_STA);
  wifi_promiscuous_filter_t filt = {
      .filter_mask = WIFI_PROMIS_FILTER_MASK_MGMT | WIFI_PROMIS_FILTER_MASK_DATA};
  esp_wifi_set_promiscuous_filter(&filt);
  esp_wifi_set_promiscuous_rx_cb(promiscuousCallback);
  esp_wifi_set_promiscuous(true);
  // A 5 GHz channel the country rules reject leaves the radio where it was:
  // report the channel it is really on, so the phone's .pcap says the truth.
  if (rawChannel == 0 || esp_wifi_set_channel(rawChannel, WIFI_SECOND_CHAN_NONE) != ESP_OK) {
    uint8_t ch = 0;
    esp_wifi_get_channel(&ch, nullptr);
    rawChannel = ch;
  }
  rawActive = true;
  char line[24];
  snprintf(line, sizeof(line), "Raw capture ch %u", rawChannel);
  drawStatus(line);
}

static void stopRawCapture() {
  rawActive = false;
  esp_wifi_set_promiscuous(false);
  drawStatus("Capture stopped");
}

// Drain a bounded number of captured frames to the phone each loop, so BLE
// work stays off the radio task and the watchdog keeps getting fed.
static void pumpRawCapture() {
  int budget = 12;
  while (budget-- > 0 && isConnected()) {
    RawSlot slot;
    taskENTER_CRITICAL(&rawMux);
    if (rawCount == 0) {
      taskEXIT_CRITICAL(&rawMux);
      break;
    }
    slot = rawSlots[rawTail];
    taskEXIT_CRITICAL(&rawMux);

    if (!sendRawFrame(slot)) break;  // link busy; keep it for next time

    taskENTER_CRITICAL(&rawMux);
    // The slot may have been dropped as oldest while we sent; only advance
    // when it is still the one we read.
    if (rawCount > 0 && rawSlots[rawTail].seq == slot.seq) {
      rawTail = (rawTail + 1) % kRawSlots;
      rawCount--;
    }
    taskEXIT_CRITICAL(&rawMux);
    esp_task_wdt_reset();
  }
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
  // The sniff stays longer where an access point is, and counts a client only
  // on its access point's own channel.
  hiddenAPs.clear();
  apChannels.clear();
  for (int16_t i = 0; i < found; ++i) {
    const auto *ap = static_cast<const wifi_ap_record_t *>(WiFi.getScanInfoByIndex(i));
    if (ap && ap->primary != 0 && apChannels.size() < 128) {
      ApChannel where;
      memcpy(where.bssid, ap->bssid, 6);
      where.channel = ap->primary;
      apChannels.push_back(where);
    }
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

  const size_t clientsSent = std::min(clientSweep.count, kClientTrafficMax);
  for (size_t i = 0; i < clientsSent; ++i) {
    sendClientTraffic(clientSweep.rows[i]);
  }

  for (const auto &v : capturedVendorIEs) {
    sendVendorIE(v);
  }

  sendBattery();

  lastScanDurationMs = millis() - started;
  lastScanMs = millis();

  sendScanFinished(apSent, staSent, probeSent,
                   static_cast<uint16_t>(std::min<uint32_t>(lastScanDurationMs, 0xFFFF)),
                   static_cast<uint16_t>(clientsSent));
  alertScanFindings();
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
  // A full sweep runs for tens of seconds in one call; feed the watchdog at
  // every dwell so it only trips on a real hang, not a normal LoRa scan.
  esp_task_wdt_reset();
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
  snprintf(deviceName, sizeof(deviceName), "%s", kSensorName);

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

// The ESP32-C5 has one core, so a pin to core 1 would fail to start the task.
static BaseType_t startTask(TaskFunction_t fn, const char *name, uint32_t stack, UBaseType_t priority,
                            TaskHandle_t *handle, BaseType_t core) {
#if defined(CONFIG_FREERTOS_NUMBER_OF_CORES) && CONFIG_FREERTOS_NUMBER_OF_CORES > 1
  return xTaskCreatePinnedToCore(fn, name, stack, nullptr, priority, handle, core);
#else
  (void)core;
  return xTaskCreate(fn, name, stack, nullptr, priority, handle);
#endif
}

#if SPECTRA_HAS_UI

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
    for (const PickRow &have : wifiList) {
      if (have.ssid == s) {
        dup = true;
        break;
      }
    }
    if (!dup) {
      wifiList.push_back({s, static_cast<int8_t>(WiFi.RSSI(i)), WiFi.encryptionType(i) == WIFI_AUTH_OPEN});
    }
  }
  WiFi.scanDelete();
  std::stable_partition(wifiList.begin(), wifiList.end(), [](const PickRow &row) {
    return isKnownSsid(row.ssid.c_str());
  });
  wifiSel = wifiList.empty() ? -1 : 0;
  wifiListOffset = -1;
  wifiInputSeeded = false;
  wifiUi = WifiUi::Pick;
  menuIndex = kTabLink;
  uiDirty = true;
}

// Internet check and public-address lookup, one at a time on their own task
// so the HTTP round trips do not stall the keyboard.
enum class NetState : uint8_t { Idle, Busy, Ok, Captive, Fail };
static constexpr uint8_t kNetJobInternet = 1;
static constexpr uint8_t kNetJobPublicIp = 2;
static volatile NetState inetState = NetState::Idle;
static volatile NetState ipState = NetState::Idle;
static volatile uint32_t inetMs = 0;
static char publicLine[40] = "";
static volatile uint8_t netJob = 0;
static volatile uint8_t netEpoch = 0;
static TaskHandle_t netTask = nullptr;

static void dropNetCheck();

static void beginJoin(bool stored) {
  dropNetCheck();
  WiFi.begin(wifiSsid, wifiPass);
  wifiUsedStored = stored;
  wifiConnectUntil = millis() + 12000;
  snprintf(wifiMsg, sizeof(wifiMsg), "Connecting...");
  wifiUi = WifiUi::Connecting;
  uiDirty = true;
}

static void wipePass() {
  memset(wifiPass, 0, sizeof(wifiPass));
  wifiPassLen = 0;
}

struct NavKeys {
  bool up;
  bool down;
  bool left;
  bool right;
};

// Fn maps ; , . / onto the arrows. The same keys move the list without Fn.
// While a password is being typed they stay characters, so a passphrase can
// still contain them.
static NavKeys navKeys(const Keyboard_Class::KeysState &keys, bool bare) {
  NavKeys nav{keys.up, keys.down, keys.left, keys.right};
  if (!bare) {
    return nav;
  }
  for (const char c : keys.word) {
    if (c == ';') {
      nav.up = true;
    } else if (c == '.') {
      nav.down = true;
    } else if (c == ',') {
      nav.left = true;
    } else if (c == '/') {
      nav.right = true;
    }
  }
  return nav;
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
    prevDel = keys.del || keys.backspace;
    prevWord.assign(keys.word.begin(), keys.word.end());
    wifiInputSeeded = true;
    return;
  }

  const bool enterEdge = keys.enter && !prevEnter;
  const bool tabEdge = keys.tab && !prevTab;
  const bool wipe = keys.del || keys.backspace;
  const bool delEdge = wipe && !prevDel;

  if (wifiUi == WifiUi::Pick) {
    const NavKeys nav = navKeys(keys, true);
    const int dir = (nav.up && !nav.down) ? -1 : (nav.down && !nav.up) ? 1 : 0;
    if (dir != 0) {
      const uint32_t now = millis();
      const bool first = dir != heldDir;
      if (first || static_cast<int32_t>(now - repeatAt) >= 0) {
        wifiSel = std::max(-1, std::min(wifiSel + dir, static_cast<int>(wifiList.size()) - 1));
        heldDir = dir;
        repeatAt = now + (first ? 360 : 140);
        uiDirty = true;
      }
    } else {
      heldDir = 0;
    }
    if (tabEdge) {
      wifiUi = WifiUi::None;
      uiDirty = true;
    } else if (enterEdge && wifiSel < 0) {
      startWifiPick();
    } else if (enterEdge) {
      const PickRow &row = wifiList[wifiSel];
      snprintf(wifiSsid, sizeof(wifiSsid), "%s", row.ssid.c_str());
      wipePass();
      wifiMsg[0] = '\0';
      const char *stored = knownPassword(wifiSsid);
      if (stored != nullptr) {
        snprintf(wifiPass, sizeof(wifiPass), "%s", stored);
        beginJoin(true);
      } else if (row.open) {
        beginJoin(false);
      } else {
        wifiUi = WifiUi::Pass;
        uiDirty = true;
      }
    }
  } else if (wifiUi == WifiUi::Pass) {
    if (tabEdge) {
      wipePass();
      wifiUi = WifiUi::Pick;
      uiDirty = true;
    } else if (enterEdge) {
      beginJoin(false);
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
      WiFi.disconnect(false, false);
      wipePass();
      wifiUi = WifiUi::Pick;
      uiDirty = true;
    }
  }

  prevEnter = keys.enter;
  prevTab = keys.tab;
  prevDel = keys.del || keys.backspace;
  prevWord.assign(keys.word.begin(), keys.word.end());
}

static void copyTraceField(const String &body, const char *key, char *out, size_t outLen) {
  out[0] = '\0';
  if (outLen == 0) {
    return;
  }
  const int at = body.indexOf(key);
  if (at < 0) {
    return;
  }
  const int start = at + static_cast<int>(strlen(key));
  int end = start;
  while (end < body.length()) {
    const char c = body.charAt(end);
    if (c == '\n' || c == '\r') {
      break;
    }
    ++end;
  }
  size_t n = static_cast<size_t>(end - start);
  if (n >= outLen) {
    n = outLen - 1;
  }
  memcpy(out, body.c_str() + start, n);
  out[n] = '\0';
}

// A 204 from a connectivity probe means a clear route out. Any other answer
// means something on the way rewrote it, usually a captive login page.
static NetState probeInternet(uint32_t &elapsedMs) {
  static const char *kProbes[] = {"http://connectivitycheck.gstatic.com/generate_204",
                                  "http://cp.cloudflare.com/generate_204"};
  for (const char *url : kProbes) {
    WiFiClient client;
    HTTPClient http;
    http.setConnectTimeout(4000);
    http.setTimeout(4000);
    const uint32_t started = millis();
    if (!http.begin(client, url)) {
      continue;
    }
    const int code = http.GET();
    elapsedMs = millis() - started;
    http.end();
    if (code == 204) {
      return NetState::Ok;
    }
    if (code > 0) {
      return NetState::Captive;
    }
  }
  return NetState::Fail;
}

// Cloudflare's trace names the address the network is seen as from outside.
static bool lookupPublicIp(char *line, size_t lineLen) {
  bool ok = false;
  WiFiClientSecure client;
  client.setInsecure();
  HTTPClient http;
  http.setConnectTimeout(4000);
  http.setTimeout(4000);
  if (http.begin(client, "https://1.1.1.1/cdn-cgi/trace")) {
    if (http.GET() == 200) {
      const String body = http.getString();
      char ip[40] = "";
      char loc[8] = "";
      copyTraceField(body, "ip=", ip, sizeof(ip));
      copyTraceField(body, "loc=", loc, sizeof(loc));
      if (ip[0] != '\0') {
        if (loc[0] != '\0') {
          snprintf(line, lineLen, "%s %s", ip, loc);
        } else {
          snprintf(line, lineLen, "%s", ip);
        }
        ok = true;
      }
    }
    http.end();
  }
  return ok;
}

static void netTaskMain(void *) {
  for (;;) {
    ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
    const uint8_t epoch = netEpoch;
    const uint8_t job = netJob;
    const bool linked = WiFi.status() == WL_CONNECTED;
    if (job == kNetJobInternet) {
      uint32_t ms = 0;
      const NetState result = linked ? probeInternet(ms) : NetState::Fail;
      if (epoch == netEpoch) {
        inetMs = ms;
        inetState = result;
      }
    } else if (job == kNetJobPublicIp) {
      char line[40] = "";
      const bool ok = linked && lookupPublicIp(line, sizeof(line));
      if (epoch == netEpoch) {
        snprintf(publicLine, sizeof(publicLine), "%s", line);
        ipState = ok ? NetState::Ok : NetState::Fail;
      }
    }
    uiDirty = true;
  }
}

static void bumpNetEpoch() {
  uint8_t next = static_cast<uint8_t>(netEpoch + 1);
  if (next == 0) {
    next = 1;
  }
  netEpoch = next;
}

static bool netBusy() { return inetState == NetState::Busy || ipState == NetState::Busy; }

static void startNetJob(uint8_t job) {
  if (netBusy() || WiFi.status() != WL_CONNECTED) {
    return;
  }
  if (netTask == nullptr) {
    startTask(netTaskMain, "net", 16384, 1, &netTask, 0);
  }
  volatile NetState &state = job == kNetJobInternet ? inetState : ipState;
  if (netTask == nullptr) {
    state = NetState::Fail;
    uiDirty = true;
    return;
  }
  if (job == kNetJobInternet) {
    ipState = NetState::Idle;
    publicLine[0] = '\0';
  }
  state = NetState::Busy;
  netJob = job;
  uiDirty = true;
  xTaskNotifyGive(netTask);
}

static void dropNetCheck() {
  bumpNetEpoch();
  inetState = NetState::Idle;
  ipState = NetState::Idle;
  publicLine[0] = '\0';
}

// The Join tab's main screen: one row per action, greyed out until it applies.
static constexpr int kJoinConnect = 0;
static constexpr int kJoinInternet = 1;
static constexpr int kJoinPublicIp = 2;
static constexpr int kJoinDisconnect = 3;
static constexpr int kJoinItems = 4;
static const char *kJoinLabels[kJoinItems] = {"Connect Wi-Fi", "Check internet", "Public IP", "Disconnect"};

static bool joinEnabled(int item) {
  const bool linked = WiFi.status() == WL_CONNECTED;
  switch (item) {
    case kJoinConnect: return true;
    case kJoinInternet: return linked && !netBusy();
    case kJoinPublicIp: return linked && !netBusy() && inetState == NetState::Ok;
    case kJoinDisconnect: return linked;
    default: return false;
  }
}

static void moveJoin(int direction) {
  for (int at = joinSel + direction; at >= 0 && at < kJoinItems; at += direction) {
    if (joinEnabled(at)) {
      joinSel = at;
      uiDirty = true;
      return;
    }
  }
}

static void disconnectWifi() {
  WiFi.disconnect(false, false);
  WiFi.mode(WIFI_STA);
  wifiUi = WifiUi::None;
  wipePass();
  dropNetCheck();
  joinSel = kJoinConnect;
  showBanner("Wi-Fi off");
  uiDirty = true;
}

static void activateJoin() {
  if (!joinEnabled(joinSel)) {
    return;
  }
  switch (joinSel) {
    case kJoinConnect: startWifiPick(); break;
    case kJoinInternet: startNetJob(kNetJobInternet); break;
    case kJoinPublicIp: startNetJob(kNetJobPublicIp); break;
    case kJoinDisconnect: disconnectWifi(); break;
    default: break;
  }
}

// Watches a pending connection and reports the outcome. Also notices a link
// that drops later, and steps the selection on once a check succeeds.
static void serviceWifiUi() {
  static bool wasLinked = false;
  static NetState lastInet = NetState::Idle;
  static uint32_t refreshAt = 0;
  const bool linked = WiFi.status() == WL_CONNECTED;

  if (wifiUi == WifiUi::Connecting) {
    if (linked) {
      rememberWifi(wifiSsid, wifiPass);
      wipePass();
      wifiUi = WifiUi::None;
      joinSel = kJoinInternet;
      showBanner("Wi-Fi OK");
      uiDirty = true;
    } else if (static_cast<int32_t>(millis() - wifiConnectUntil) >= 0) {
      WiFi.disconnect(false, false);
      wipePass();
      snprintf(wifiMsg, sizeof(wifiMsg), wifiUsedStored ? "Saved password failed" : "Failed, try again");
      wifiUi = WifiUi::Pass;
      wifiInputSeeded = false;
      showBanner("Wi-Fi failed");
      uiDirty = true;
    }
  }

  if (wasLinked && !linked && wifiUi != WifiUi::Connecting) {
    dropNetCheck();
    if (joinSel != kJoinConnect) {
      joinSel = kJoinConnect;
    }
    showBanner("Wi-Fi lost");
  }
  wasLinked = linked;

  const NetState inet = inetState;
  if (inet != lastInet) {
    if (inet == NetState::Ok && joinSel == kJoinInternet) {
      joinSel = kJoinPublicIp;
    }
    lastInet = inet;
    uiDirty = true;
  }
  if (!joinEnabled(joinSel) && !netBusy()) {
    joinSel = kJoinConnect;
  }

  // Signal strength on the status line.
  if (linked && menuIndex == kTabLink && wifiUi == WifiUi::None &&
      static_cast<int32_t>(millis() - refreshAt) >= 0) {
    refreshAt = millis() + 3000;
    uiDirty = true;
  }
}

static void drawHint(const char *text) {
  auto &d = M5Cardputer.Display;
  d.setTextColor(TFT_DARKGREY, TFT_BLACK);
  d.setCursor(4, d.height() - 10);
  d.print(text);
}

static void drawJoinMenu() {
  auto &d = M5Cardputer.Display;
  const bool linked = WiFi.status() == WL_CONNECTED;
  d.setCursor(4, kListTop);
  if (linked) {
    d.setTextColor(TFT_GREEN, TFT_BLACK);
    char line[44];
    snprintf(line, sizeof(line), "%.24s  %d dBm ch%u", WiFi.SSID().c_str(), static_cast<int>(WiFi.RSSI()),
             static_cast<unsigned>(WiFi.channel()));
    d.print(line);
    d.setTextColor(TFT_DARKGREY, TFT_BLACK);
    d.setCursor(4, kListTop + 11);
    d.print("IP ");
    d.print(WiFi.localIP().toString().c_str());
  } else {
    d.setTextColor(TFT_DARKGREY, TFT_BLACK);
    d.print("Not connected");
  }

  const int buttonW = 100;
  const int resultX = buttonW + 12;
  int y = kListTop + 26;
  for (int i = 0; i < kJoinItems; ++i) {
    const bool enabled = joinEnabled(i);
    const bool on = i == joinSel;
    const uint16_t ink = enabled ? TFT_WHITE : 0x4208;
    if (on) {
      d.fillRect(2, y - 2, buttonW, 12, enabled ? TFT_WHITE : 0x4208);
      d.setTextColor(TFT_BLACK, enabled ? TFT_WHITE : 0x4208);
    } else {
      d.drawRect(2, y - 2, buttonW, 12, ink);
      d.setTextColor(ink, TFT_BLACK);
    }
    d.setCursor(7, y);
    d.print(kJoinLabels[i]);

    const NetState state = i == kJoinInternet ? inetState : i == kJoinPublicIp ? ipState : NetState::Idle;
    d.setCursor(resultX, y);
    char result[24] = "";
    uint16_t color = TFT_WHITE;
    if (state == NetState::Busy) {
      snprintf(result, sizeof(result), "...");
      color = TFT_YELLOW;
    } else if (state == NetState::Ok && i == kJoinInternet) {
      snprintf(result, sizeof(result), "OK %lu ms", static_cast<unsigned long>(inetMs));
      color = TFT_GREEN;
    } else if (state == NetState::Ok) {
      snprintf(result, sizeof(result), "%.21s", publicLine);
    } else if (state == NetState::Captive) {
      snprintf(result, sizeof(result), "Login page");
      color = TFT_ORANGE;
    } else if (state == NetState::Fail) {
      snprintf(result, sizeof(result), i == kJoinInternet ? "No internet" : "Failed");
      color = TFT_ORANGE;
    }
    d.setTextColor(color, TFT_BLACK);
    d.print(result);
    y += 14;
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
      if (i < 0) {
        d.setTextColor(on ? TFT_BLACK : TFT_CYAN, on ? TFT_WHITE : TFT_BLACK);
        d.setCursor(6, y);
        d.print(wifiList.empty() ? "No networks, scan again" : "Scan again");
        y += 12;
        continue;
      }
      const PickRow &row = wifiList[i];
      const bool known = isKnownSsid(row.ssid.c_str());
      if (known) {
        d.fillRect(0, y - 1, 3, 12, TFT_GREEN);
      }
      const uint16_t bg = on ? TFT_WHITE : TFT_BLACK;
      d.setTextColor(on ? TFT_BLACK : known ? TFT_GREEN : TFT_WHITE, bg);
      d.setCursor(6, y);
      char shown[36];
      snprintf(shown, sizeof(shown), "%.31s", row.ssid.c_str());
      d.print(shown);
      if (row.open) {
        d.setTextColor(on ? TFT_BLACK : TFT_GREEN, bg);
        d.setCursor(d.width() - 36, y);
        d.print("o");
      }
      d.setTextColor(on ? TFT_BLACK : TFT_DARKGREY, bg);
      d.setCursor(d.width() - 26, y);
      d.printf("%4d", row.rssi);
      y += 12;
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
    const char *shown = wifiPass;
    if (wifiPassLen > 33) {
      shown = wifiPass + (wifiPassLen - 33);
    }
    d.print(shown);
    d.print("_");
    if (wifiMsg[0] != '\0') {
      d.setTextColor(TFT_ORANGE, TFT_BLACK);
      d.setCursor(4, kListTop + 32);
      d.print(wifiMsg);
    }
    drawHint("Enter join   Tab back");
    return;
  }
  if (wifiUi == WifiUi::Connecting) {
    d.print(wifiMsg);
    d.setCursor(4, kListTop + 16);
    d.print(wifiSsid);
    drawHint("Tab cancel");
    return;
  }
  drawJoinMenu();
}

static void handleKeyboard() {
  if (scanInProgress || otaActive || otaJobBusy) {
    return;
  }
  const Keyboard_Class::KeysState keys = M5Cardputer.Keyboard.keysState();
  const bool typingPass = menuIndex == kTabLink && wifiUi == WifiUi::Pass;
  const NavKeys nav = navKeys(keys, !typingPass);
  const bool up = nav.up;
  const bool down = nav.down;
  const bool left = nav.left;
  const bool right = nav.right;

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
      } else if (wifiUi == WifiUi::None) {
        moveJoin(scroll);
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
  static bool tabHeld = false;
  const bool tabEdge = keys.tab && !tabHeld;
  tabHeld = keys.tab;
  if (menuIndex == kTabLink && wifiUi != WifiUi::None) {
    handleWifiKeys();
  } else if (tabEdge && !isConnected()) {
    static const uint8_t steps[] = {0, 10, 30, 60, 120, 180, 240};
    int at = -1;
    for (int i = 0; i < 7; ++i) {
      if (steps[i] == scanIntervalSec) {
        at = i;
      }
    }
    scanIntervalSec = steps[(at + 1) % 7];
    intervalChanged = true;
    if (scanIntervalSec == 0) {
      showBanner("scan off");
    } else {
      char msg[16];
      snprintf(msg, sizeof(msg), "every %us", static_cast<unsigned>(scanIntervalSec));
      showBanner(msg);
    }
  } else if (keys.enter && !enterHeld) {
    if (menuIndex == kTabLink) {
      activateJoin();
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
  if (menuIndex == kTabLink) {
    if (WiFi.status() == WL_CONNECTED || wifiUi == WifiUi::Connecting) {
      disconnectWifi();
    }
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

#endif

// Subscribes the loop task to the task watchdog. The Arduino core may already
// have started the watchdog for the idle tasks, so reconfigure if so.
static void setupWatchdog() {
  esp_task_wdt_config_t config = {};
  config.timeout_ms = kWatchdogTimeoutMs;
  config.idle_core_mask = 0;
  config.trigger_panic = true;
  if (esp_task_wdt_init(&config) == ESP_ERR_INVALID_STATE) {
    esp_task_wdt_reconfigure(&config);
  }
  esp_task_wdt_add(nullptr);
  esp_task_wdt_reset();
}

void setup() {
#if SPECTRA_HAS_UI
  auto cfg = M5.config();
  M5Cardputer.begin(cfg, true);
  M5Cardputer.Display.setRotation(1);
  M5Cardputer.Display.setTextSize(1);
  M5Cardputer.Display.fillScreen(TFT_BLACK);
  // M5Unified starts the RGB LED at 63 of 255; alerts should be seen across a room.
  M5.Led.setBrightness(255);
#else
  Serial.begin(115200);
  Serial.printf("Spectra %s on %s\n", kFirmwareVersion, kSensorName);
#endif

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
#if SPECTRA_WIFI_5G
  // The Arduino core also selects this when the radio starts. Saying it here
  // keeps a 5 GHz scan from being left on 2.4 GHz after a mode change.
  WiFi.setBandMode(WIFI_BAND_MODE_AUTO);
#endif
  loraCapBegin();

  otaCrcOk = otaCrcSelfCheck();
  otaUpdatable = otaCrcOk && otaAvailable();
  startTask(otaTaskMain, "ota", 16384, 1, &otaTask, 1);
  setupBle();
  loadStored();
  loadWifiCreds();
  loadAlertPrefs();
  ledSet(0, 0, 0);
  updateInfo();
  setupWatchdog();
  uiDirty = true;
}

void loop() {
  esp_task_wdt_reset();
#if SPECTRA_HAS_UI
  M5Cardputer.update();
#endif
  if (otaRebootPending && static_cast<int32_t>(millis() - otaRebootAt) >= 0) {
    ESP.restart();
  }
#if SPECTRA_HAS_UI
  if (!otaActive) {
    handleKeyboard();
    handleSideButton();
    serviceWifiUi();
  }
#endif

  // The batch lives in BSS. Eighty credential rows do not fit on the loop stack.
  static WifiOp batch[kWifiOpMax];
  uint8_t batchCount = 0;
  taskENTER_CRITICAL(&wifiOpMux);
  batchCount = wifiOpCount;
  if (batchCount > 0) {
    memcpy(batch, wifiOps, batchCount * sizeof(WifiOp));
    wifiOpCount = 0;
  }
  taskEXIT_CRITICAL(&wifiOpMux);
  for (uint8_t i = 0; i < batchCount; ++i) {
    if (batch[i].op == 1) {
      rememberFromPhone(batch[i].ssid, batch[i].pass);
    } else if (batch[i].op == 2) {
      forgetWifi(batch[i].ssid);
    } else if (batch[i].op == 3) {
      ackPendingWifi();
    } else if (batch[i].op == 4) {
      resetPhoneWifi();
    }
  }
  if (resultsSubscribed && isConnected()) {
    queueKnown();
  }
  if (intervalChanged) {
    intervalChanged = false;
    saveInterval();
    updateInfo();
    uiDirty = true;
  }
  if (alertsChanged) {
    alertsChanged = false;
    saveAlerts();
    updateInfo();
  }
  if (watchChanged) {
    watchChanged = false;
    saveWatch();
    updateInfo();
  }
  serviceLed();
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
      for (KnownWifi &row : knownWifi) {
        if (row.epoch == inFlightEpoch) {
          row.epoch = 0;
        }
      }
      knownQueued = false;
      inFlightEpoch = 0;
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

  // Raw frame capture: start/stop on the loop task (radio work off the host
  // task), then drain the ring to the phone. It owns the radio while running,
  // so periodic scans wait, like a firmware update.
  if (rawStartPending && !otaActive && !otaJobBusy && !scanInProgress) {
    rawStartPending = false;
    startRawCapture();
    uiDirty = true;
  }
  if (rawActive) {
    pumpRawCapture();
  }
  if (rawStopPending) {
    rawStopPending = false;
    rawStartPending = false;
    if (rawActive) {
      stopRawCapture();
      uiDirty = true;
    }
  }

  // The phone's interval drives the sweep whether or not the phone is here,
  // once a first Wi-Fi scan has been asked for. Zero means wait.
  const uint32_t everyMs = static_cast<uint32_t>(scanIntervalSec) * 1000;
  const bool due = (patrolArmed || blePatrolArmed) && everyMs > 0 && !otaActive && !otaJobBusy && !rawActive &&
                   !rawStartPending && millis() - lastScanMs >= everyMs;
  bool startScan = false;
  uint8_t kind = 0;
  taskENTER_CRITICAL(&radioMux);
  if (due && !scanRequested) {
    requestedKind = blePatrolArmed && (!patrolArmed || patrolNextBle) ? 2 : 0;
  }
  const bool joinBusy =
      wifiUi == WifiUi::Connecting || (menuIndex == kTabLink && wifiUi != WifiUi::None);
  if ((scanRequested || due) && !otaActive && !otaJobBusy && !joinBusy && !rawActive &&
      !rawStartPending) {
    scanRequested = false;
    kind = requestedKind;
    scanInProgress = true;
    startScan = true;
  } else if (otaActive) {
    scanRequested = false;
  }
  taskEXIT_CRITICAL(&radioMux);
  if (startScan) {
    // The loop stalls for the scan; do not leave a blink lit through it.
    ledOff();
    if (kind == 0) {
      patrolArmed = true;
      patrolNextBle = true;
    } else if (kind == 2) {
      blePatrolArmed = true;
      patrolNextBle = false;
    }
    if (kind == 1) {
      runLanScan();
    } else if (kind == 2) {
      runBluetoothScan();
    } else if (kind == 3) {
      runLoraScan();
    } else {
      runScan();
    }
    if (kind == 0 || kind == 2) {
      lastScanMs = millis();
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
