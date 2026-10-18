// Host-side unit tests for the firmware's hardware-independent modules.
// Run with: pio test -e native

#include <unity.h>

#include "Bytes.h"
#include "Crc32.h"

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

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_crc_known_vector);
  RUN_TEST(test_crc_incremental);
  RUN_TEST(test_bytes_little_endian);
  return UNITY_END();
}
