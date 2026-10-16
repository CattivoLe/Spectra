// Spectra sensor firmware for the M5Stack Cardputer.
//
// Scans for Wi-Fi access points and nearby stations (clients), then sends the
// results to the Spectra iOS app over BLE. The wire format is described in
// docs/protocol.md.

#include <M5Cardputer.h>
#include <NimBLEDevice.h>
#include <WiFi.h>
#include <esp_mac.h>
#include <esp_wifi.h>
#include <algorithm>
#include <cstring>
#include <vector>

static constexpr char kFirmwareVersion[] = "0.2.0";
static constexpr uint8_t kProtocolVersion = 2;

static constexpr char kServiceUuid[] = "53500001-8F2D-4B6A-9C1E-737065637472";
static constexpr char kInfoUuid[] = "53500002-8F2D-4B6A-9C1E-737065637472";
static constexpr char kControlUuid[] = "53500003-8F2D-4B6A-9C1E-737065637472";
static constexpr char kResultsUuid[] = "53500004-8F2D-4B6A-9C1E-737065637472";

static constexpr uint8_t kCmdScanOnce = 0x01;
static constexpr uint8_t kCmdSetInterval = 0x02;

static constexpr uint8_t kMsgScanStarted = 0x10;
static constexpr uint8_t kMsgAccessPoint = 0x11;
static constexpr uint8_t kMsgScanFinished = 0x12;
static constexpr uint8_t kMsgStation = 0x13;

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
static bool uiDirty = true;

// Collected during promiscuous-mode capture; accessed only from loop().
static std::vector<StationEntry> capturedStations;
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
    } else if (subtype == 0 || subtype == 2) {
      // Association / reassociation request: addr2 is the client, addr1 is the AP.
      recordStation(addr2, addr1, rssi, true);
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
    } else if (ds == 0x02) {
      // ToDS=0, FromDS=1: AP → client.
      // Addr1 = destination (client), Addr2 = BSSID, Addr3 = source.
      recordStation(addr1, addr2, rssi, true);
    }
  }
}

// ---------------------------------------------------------------------------
// BLE protocol
// ---------------------------------------------------------------------------

static void updateInfo() {
  char json[96];
  snprintf(json, sizeof(json),
           "{\"proto\":%u,\"fw\":\"%s\",\"chip\":\"ESP32-S3\",\"bands\":[\"2.4\"],\"interval\":%u}",
           kProtocolVersion, kFirmwareVersion, scanIntervalSec);
  infoChar->setValue(json);
}

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer *, NimBLEConnInfo &) override { linkChanged = true; }

  void onDisconnect(NimBLEServer *, NimBLEConnInfo &, int) override {
    // Automatic scans are for the app; stop them when it goes away.
    scanIntervalSec = 0;
    intervalChanged = true;
    linkChanged = true;
  }
};

class ControlCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &) override {
    const NimBLEAttValue value = c->getValue();
    if (value.size() < 1) {
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

static void sendScanFinished(uint16_t apCount, uint16_t staCount, uint16_t durationMs) {
  uint8_t msg[9] = {kMsgScanFinished};
  putU16(msg + 1, scanId);
  putU16(msg + 3, apCount);
  putU16(msg + 5, durationMs);
  putU16(msg + 7, staCount);
  sendMessage(msg, sizeof(msg));
}

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

static void drawUi() {
  auto &d = M5Cardputer.Display;
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
    d.printf("%u AP  %u STA  %lu ms", static_cast<unsigned>(lastResults.size()),
             static_cast<unsigned>(lastStationCount),
             static_cast<unsigned long>(lastScanDurationMs));
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

  lastScanDurationMs = millis() - started;
  lastScanMs = millis();

  sendScanFinished(apSent, staSent,
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
  NimBLEDevice::setMTU(185);

  server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  server->advertiseOnDisconnect(true);

  NimBLEService *service = server->createService(kServiceUuid);
  infoChar = service->createCharacteristic(kInfoUuid, NIMBLE_PROPERTY::READ);
  NimBLECharacteristic *control = service->createCharacteristic(
      kControlUuid, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  control->setCallbacks(new ControlCallbacks());
  resultsChar = service->createCharacteristic(kResultsUuid, NIMBLE_PROPERTY::NOTIFY);
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

  setupBle();
  uiDirty = true;
}

void loop() {
  M5Cardputer.update();
  handleKeyboard();

  if (intervalChanged) {
    intervalChanged = false;
    updateInfo();
    uiDirty = true;
  }
  if (linkChanged) {
    linkChanged = false;
    uiDirty = true;
  }

  const bool due = scanIntervalSec > 0 && isConnected() &&
                   millis() - lastScanMs >= static_cast<uint32_t>(scanIntervalSec) * 1000;
  if (scanRequested || due) {
    scanRequested = false;
    runScan();
  }

  if (uiDirty) {
    uiDirty = false;
    drawUi();
  }
  delay(10);
}
