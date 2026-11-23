// Host-side unit tests for the firmware's hardware-independent modules.
// Run with: pio test -e native

#include <unity.h>

#include "Bytes.h"
#include "Crc32.h"
#include "Protocol.h"
#include "Stream.h"

void setUp() {}
void tearDown() {}

// The known CRC-32 test vector from docs/protocol.md.
static void test_crc_known_vector() {
  const uint8_t sample[] = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};
  TEST_ASSERT_EQUAL_HEX32(0xCBF43926u, crc32Update(0, sample, sizeof(sample)));
  TEST_ASSERT_TRUE(otaCrcSelfCheck());
}

// OTA feeds the image in chunks, continuing the CRC across writes. Splitting
// the vector must give the same result as one pass.
static void test_crc_incremental() {
  const uint8_t sample[] = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};
  uint32_t crc = crc32Update(0, sample, 4);
  crc = crc32Update(crc, sample + 4, 5);
  TEST_ASSERT_EQUAL_HEX32(0xCBF43926u, crc);
}

static void test_bytes_little_endian() {
  uint8_t buf[4];

  putU16(buf, 0xBEEF);
  TEST_ASSERT_EQUAL_HEX8(0xEF, buf[0]);
  TEST_ASSERT_EQUAL_HEX8(0xBE, buf[1]);
  TEST_ASSERT_EQUAL_UINT16(0xBEEF, readU16(buf));

  putU32(buf, 0xDEADBEEFu);
  TEST_ASSERT_EQUAL_HEX8(0xEF, buf[0]);
  TEST_ASSERT_EQUAL_HEX8(0xAD, buf[2]);
  TEST_ASSERT_EQUAL_HEX8(0xDE, buf[3]);
  TEST_ASSERT_EQUAL_HEX32(0xDEADBEEFu, getU32(buf));
}

// A capture on a 5 GHz network must reach a 5 GHz board; a 2.4 GHz-only board
// keeps ignoring channels it cannot tune to.
static void test_raw_capture_channels() {
  TEST_ASSERT_TRUE(rawCaptureChannelAllowed(0, false));
  TEST_ASSERT_TRUE(rawCaptureChannelAllowed(6, false));
  TEST_ASSERT_TRUE(rawCaptureChannelAllowed(14, false));
  TEST_ASSERT_FALSE(rawCaptureChannelAllowed(36, false));
  TEST_ASSERT_TRUE(rawCaptureChannelAllowed(36, true));
  TEST_ASSERT_TRUE(rawCaptureChannelAllowed(149, true));
  TEST_ASSERT_TRUE(rawCaptureChannelAllowed(177, true));
  TEST_ASSERT_FALSE(rawCaptureChannelAllowed(20, true));
  TEST_ASSERT_FALSE(rawCaptureChannelAllowed(200, true));
}

// ---------------------------------------------------------------------------
// Per-client traffic and the streaming rule
// ---------------------------------------------------------------------------

static const uint8_t kCam[6] = {0x44, 0x47, 0xCC, 0x01, 0x02, 0x03};
static const uint8_t kPhone[6] = {0xF0, 0x18, 0x98, 0x0A, 0x0B, 0x0C};
static const uint8_t kAp[6] = {0x50, 0xC7, 0xBF, 0x1E, 0x00, 0xA0};

// The sweep stays bounded however many channels are in use.
static void test_dwell_budget() {
  TEST_ASSERT_EQUAL_UINT32(0, sniffExtraMs(0));
  TEST_ASSERT_EQUAL_UINT32(1000, sniffExtraMs(1));
  TEST_ASSERT_EQUAL_UINT32(1000, sniffExtraMs(4));
  TEST_ASSERT_EQUAL_UINT32(500, sniffExtraMs(8));
  for (size_t n = 1; n <= 40; ++n) {
    TEST_ASSERT_TRUE(sniffExtraMs(n) * n <= kSniffExtraBudgetMs);
  }
}

// 0x17 keeps meaning "bytes in a quarter of a second" however long the listen.
static void test_traffic_is_scaled_to_the_old_window() {
  TEST_ASSERT_EQUAL_UINT32(10000, scaleToWindow(10000, 250));
  TEST_ASSERT_EQUAL_UINT32(10000, scaleToWindow(50000, 1250));
  TEST_ASSERT_EQUAL_UINT32(1, scaleToWindow(1, 1250));  // something heard stays something
  TEST_ASSERT_EQUAL_UINT32(0, scaleToWindow(0, 1250));
  TEST_ASSERT_EQUAL_UINT32(0, scaleToWindow(500, 0));
  TEST_ASSERT_EQUAL_UINT32(858993459u, scaleToWindow(0xFFFFFFFFu, 1250));  // no overflow
}

static void test_client_counts_by_direction() {
  static ClientPass<2> pass;
  pass.count = 0;
  pass.downlink(kCam, kAp, 900);  // downlink alone opens nothing
  TEST_ASSERT_EQUAL_UINT32(0, pass.count);
  pass.uplink(kCam, kAp, 1400, -60, 10);
  pass.uplink(kCam, kAp, 1400, -55, 80);
  pass.uplink(kCam, kAp, 700, -58, 420);
  pass.downlink(kCam, kAp, 100);
  TEST_ASSERT_EQUAL_UINT32(1, pass.count);
  const ClientTraffic &c = pass.rows[0];
  TEST_ASSERT_EQUAL_UINT32(3500, c.upBytes);
  TEST_ASSERT_EQUAL_UINT16(3, c.upFrames);
  TEST_ASSERT_EQUAL_UINT32(100, c.downBytes);
  TEST_ASSERT_EQUAL_UINT16(1, c.downFrames);
  TEST_ASSERT_EQUAL_INT8(-55, c.rssi);
  TEST_ASSERT_EQUAL_UINT8(2, busySlots(c));
  // A full table drops newcomers and keeps counting the ones it has.
  const uint8_t other[6] = {1, 1, 1, 1, 1, 1}, third[6] = {2, 2, 2, 2, 2, 2};
  pass.uplink(other, kAp, 10, -70, 0);
  pass.uplink(third, kAp, 10, -70, 0);
  TEST_ASSERT_EQUAL_UINT32(2, pass.count);
  TEST_ASSERT_TRUE(pass.anySending());
}

static void test_null_frames_are_not_data() {
  TEST_ASSERT_TRUE(dataSubtypeCarriesData(0));    // Data
  TEST_ASSERT_FALSE(dataSubtypeCarriesData(4));   // Null
  TEST_ASSERT_TRUE(dataSubtypeCarriesData(8));    // QoS Data
  TEST_ASSERT_FALSE(dataSubtypeCarriesData(12));  // QoS Null
}

static ClientTraffic client(const uint8_t *mac, uint16_t listenMs, uint32_t up, uint16_t upFrames,
                            uint32_t down, uint8_t busy) {
  ClientTraffic c{};
  memcpy(c.mac, mac, 6);
  memcpy(c.bssid, kAp, 6);
  c.channel = 6;
  c.rssi = -50;
  c.listenMs = listenMs;
  c.upBytes = up;
  c.upFrames = upFrames;
  c.downBytes = down;
  c.downFrames = 1;
  c.slots = (1u << busy) - 1;
  return c;
}

// The channel it is heard best on wins; nothing is added up across channels.
static void test_sweep_keeps_the_best_channel() {
  static ClientSweep<2> sweep;
  sweep.count = 0;
  ClientTraffic leak = client(kCam, 0, 2000, 2, 0, 1);
  sweep.fold(&leak, 1, 5, 250);
  ClientTraffic home = client(kCam, 0, 120000, 100, 4000, 12);
  sweep.fold(&home, 1, 6, 1250);
  sweep.fold(&leak, 1, 7, 250);
  TEST_ASSERT_EQUAL_UINT32(1, sweep.count);
  TEST_ASSERT_EQUAL_UINT8(6, sweep.rows[0].channel);
  TEST_ASSERT_EQUAL_UINT16(1250, sweep.rows[0].listenMs);
  TEST_ASSERT_EQUAL_UINT32(120000, sweep.rows[0].upBytes);
  // A full sweep gives up its quietest row for a busier one.
  ClientTraffic quiet = client(kPhone, 0, 100, 1, 0, 1);
  const uint8_t loud[6] = {9, 9, 9, 9, 9, 9};
  ClientTraffic busy = client(loud, 0, 500000, 400, 0, 12);
  sweep.fold(&quiet, 1, 6, 1250);
  sweep.fold(&busy, 1, 6, 1250);
  sweep.sort();
  TEST_ASSERT_EQUAL_UINT32(2, sweep.count);
  TEST_ASSERT_EQUAL_MEMORY(loud, sweep.rows[0].mac, 6);
  TEST_ASSERT_EQUAL_MEMORY(kCam, sweep.rows[1].mac, 6);
}

static void test_streaming_rule() {
  // A camera at 1 Mbit/s with half its frames heard, TCP acks coming back.
  TEST_ASSERT_TRUE(looksLikeStream(client(kCam, 1250, 78000, 60, 4000, 13)));
  // A G.711 microphone: small frames, never pausing.
  TEST_ASSERT_TRUE(looksLikeStream(client(kCam, 1250, 15000, 60, 0, 13)));
  // Just under the bar.
  TEST_ASSERT_FALSE(looksLikeStream(client(kCam, 1250, 9900, 12, 0, 13)));
  // A download whose own frames are in a format the sensor cannot decode:
  // only its acknowledgements are heard.
  TEST_ASSERT_FALSE(looksLikeStream(client(kPhone, 1250, 100000, 1000, 0, 13)));
  // A video call goes both ways.
  TEST_ASSERT_FALSE(looksLikeStream(client(kPhone, 1250, 150000, 150, 140000, 13)));
  // One photo going up: a burst, then silence.
  TEST_ASSERT_FALSE(looksLikeStream(client(kPhone, 1250, 300000, 220, 2000, 4)));
  TEST_ASSERT_FALSE(looksLikeStream(ClientTraffic{}));
}

static void test_stream_is_announced_once_it_keeps_up() {
  static StreamWatch<4> watch;
  watch.count = 0;
  ClientTraffic cam = client(kCam, 1250, 78000, 60, 4000, 13);
  TEST_ASSERT_FALSE(watch.note(&cam, 1));
  TEST_ASSERT_FALSE(watch.note(&cam, 1));
  TEST_ASSERT_TRUE(watch.note(&cam, 1));
  TEST_ASSERT_FALSE(watch.note(&cam, 1));  // said already
  TEST_ASSERT_FALSE(watch.note(nullptr, 0));
  TEST_ASSERT_TRUE(watch.steady(kCam));  // one missed sweep changes nothing
  TEST_ASSERT_FALSE(watch.note(nullptr, 0));
  TEST_ASSERT_FALSE(watch.steady(kCam));
  TEST_ASSERT_FALSE(watch.note(nullptr, 0));
  TEST_ASSERT_FALSE(watch.note(nullptr, 0));
  TEST_ASSERT_EQUAL_UINT32(0, watch.count);
  // Gone and back: announced again.
  watch.note(&cam, 1);
  watch.note(&cam, 1);
  TEST_ASSERT_TRUE(watch.note(&cam, 1));
}

static void test_stream_under_two_access_points_counts_once() {
  static StreamWatch<4> watch;
  watch.count = 0;
  ClientTraffic both[2] = {client(kCam, 1250, 78000, 60, 4000, 13), client(kCam, 1250, 78000, 60, 4000, 13)};
  both[1].bssid[5] = 0xA1;
  TEST_ASSERT_FALSE(watch.note(both, 2));
  TEST_ASSERT_FALSE(watch.note(both, 2));
  TEST_ASSERT_TRUE(watch.note(both, 2));
}

// The bytes SensorProtocol tests expect on both phones.
static void test_client_traffic_layout() {
  ClientTraffic c = client(kCam, 1250, 78000, 60, 4000, 13);
  c.downFrames = 40;
  uint8_t out[kClientTrafficLength];
  encodeClientTraffic(out, 0x0201, c);
  const uint8_t want[kClientTrafficLength] = {
      0x25, 0x01, 0x02,                          // type, scan id
      0x44, 0x47, 0xCC, 0x01, 0x02, 0x03,        // client
      0x50, 0xC7, 0xBF, 0x1E, 0x00, 0xA0,        // BSSID
      0x06, 0xCE,                                // channel, -50 dBm
      0xE2, 0x04,                                // 1250 ms
      0xB0, 0x30, 0x01, 0x00, 0x3C, 0x00,        // 78000 bytes, 60 frames up
      0xA0, 0x0F, 0x00, 0x00, 0x28, 0x00,        // 4000 bytes, 40 frames down
      0x0D,                                      // busy slots
  };
  TEST_ASSERT_EQUAL_HEX8_ARRAY(want, out, kClientTrafficLength);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_dwell_budget);
  RUN_TEST(test_traffic_is_scaled_to_the_old_window);
  RUN_TEST(test_client_counts_by_direction);
  RUN_TEST(test_null_frames_are_not_data);
  RUN_TEST(test_sweep_keeps_the_best_channel);
  RUN_TEST(test_streaming_rule);
  RUN_TEST(test_stream_is_announced_once_it_keeps_up);
  RUN_TEST(test_stream_under_two_access_points_counts_once);
  RUN_TEST(test_client_traffic_layout);
  RUN_TEST(test_crc_known_vector);
  RUN_TEST(test_crc_incremental);
  RUN_TEST(test_bytes_little_endian);
  RUN_TEST(test_raw_capture_channels);
  return UNITY_END();
}
