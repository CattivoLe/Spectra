// Who is sending, and how long the sensor listened: per-client traffic counted
// during the promiscuous sweep, and the rule that tells a camera's or a
// microphone's feed from ordinary use. No hardware in here, so the host tests
// can drive it. The rule is in docs/protocol.md (0x25) and is mirrored by both
// phone apps; change the three together.

#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "Bytes.h"
#include "Protocol.h"

// ---------------------------------------------------------------------------
// How long to listen
// ---------------------------------------------------------------------------

// Every channel gets this much.
inline constexpr uint32_t kSniffBaseMs = 250;
// Channels with an access point on them share an extra budget, so one sweep
// stays bounded however crowded the band is: four channels or fewer get the
// full extra, thirteen get about a third of a second each.
inline constexpr uint32_t kSniffExtraBudgetMs = 4000;
inline constexpr uint32_t kSniffExtraMaxMs = 1000;
// A channel where no access point was heard but a client is sending: the
// access point is too far away, the camera is not.
inline constexpr uint32_t kSniffSurpriseMs = 750;
inline constexpr uint8_t kSniffSurprisesMax = 2;

inline constexpr uint32_t sniffExtraMs(size_t audibleChannels) {
  if (audibleChannels == 0) return 0;
  const uint32_t share = kSniffExtraBudgetMs / static_cast<uint32_t>(audibleChannels);
  return share < kSniffExtraMaxMs ? share : kSniffExtraMaxMs;
}

// The 0x17 record predates this and means "bytes in a quarter of a second":
// phones compare it with a fixed bar. Counts from a longer listen are scaled
// back to that window.
inline constexpr uint32_t kTrafficWindowMs = 250;

inline constexpr uint32_t scaleToWindow(uint32_t counted, uint32_t listenMs) {
  return listenMs == 0 ? 0
                       : static_cast<uint32_t>((static_cast<uint64_t>(counted) * kTrafficWindowMs +
                                                listenMs - 1) / listenMs);
}

// ---------------------------------------------------------------------------
// Per-client counts
// ---------------------------------------------------------------------------

inline constexpr uint32_t kSlotMs = 100;

struct ClientTraffic {
  uint8_t mac[6];
  uint8_t bssid[6];
  uint8_t channel;
  int8_t rssi;         // strongest uplink frame
  uint16_t listenMs;   // time really spent on `channel`
  uint32_t upBytes;    // client -> access point
  uint32_t downBytes;  // access point -> client, from the first uplink frame on
  uint16_t upFrames;
  uint16_t downFrames;
  uint32_t slots;      // bit n: uplink data heard in the n-th 100 ms
};

inline uint8_t busySlots(const ClientTraffic &c) {
  uint8_t n = 0;
  for (uint32_t bits = c.slots; bits != 0; bits &= bits - 1) ++n;
  return n;
}

inline uint8_t totalSlots(uint16_t listenMs) {
  const uint32_t n = (listenMs + kSlotMs - 1) / kSlotMs;
  return n > 32 ? 32 : static_cast<uint8_t>(n);
}

// A data frame that carries no data (Null, QoS Null, CF-Ack...): clients send
// those to announce power saving, they are not traffic.
inline constexpr bool dataSubtypeCarriesData(uint8_t subtype) { return (subtype & 0x04) == 0; }

// Counts for the channel being listened to. A row is opened by uplink, the
// only direction that says what a client sends.
template <size_t N>
struct ClientPass {
  ClientTraffic rows[N];
  size_t count = 0;

  ClientTraffic *find(const uint8_t *mac, const uint8_t *bssid) {
    for (size_t i = 0; i < count; ++i) {
      if (memcmp(rows[i].mac, mac, 6) == 0 && memcmp(rows[i].bssid, bssid, 6) == 0) return &rows[i];
    }
    return nullptr;
  }

  void uplink(const uint8_t *mac, const uint8_t *bssid, uint16_t length, int8_t rssi,
              uint32_t elapsedMs) {
    ClientTraffic *row = find(mac, bssid);
    if (row == nullptr) {
      if (count >= N) return;
      row = &rows[count++];
      memset(row, 0, sizeof(*row));
      memcpy(row->mac, mac, 6);
      memcpy(row->bssid, bssid, 6);
      row->rssi = rssi;
    }
    row->upBytes += length;
    if (row->upFrames < 0xFFFF) row->upFrames++;
    if (rssi > row->rssi) row->rssi = rssi;
    const uint32_t slot = elapsedMs / kSlotMs;
    row->slots |= 1u << (slot > 31 ? 31 : slot);
  }

  void downlink(const uint8_t *mac, const uint8_t *bssid, uint16_t length) {
    ClientTraffic *row = find(mac, bssid);
    if (row == nullptr) return;
    row->downBytes += length;
    if (row->downFrames < 0xFFFF) row->downFrames++;
  }

  // A client that keeps sending, on a channel nothing was expected on.
  bool anySending() const {
    for (size_t i = 0; i < count; ++i) {
      if (busySlots(rows[i]) >= 2) return true;
    }
    return false;
  }
};

inline uint32_t uplinkBytesPerSecond(const ClientTraffic &c) {
  return c.listenMs == 0 ? 0 : static_cast<uint32_t>(static_cast<uint64_t>(c.upBytes) * 1000 / c.listenMs);
}

// The sweep's result: for each client the channel it was heard best on. A
// frame leaks onto neighbouring channels, so the same client can turn up on
// several; adding those up would count it twice and stretch its time.
template <size_t N>
struct ClientSweep {
  ClientTraffic rows[N];
  size_t count = 0;

  void fold(const ClientTraffic *pass, size_t passCount, uint8_t channel, uint16_t listenMs) {
    for (size_t i = 0; i < passCount; ++i) {
      ClientTraffic row = pass[i];
      row.channel = channel;
      row.listenMs = listenMs;
      put(row);
    }
  }

 private:
  void put(const ClientTraffic &row) {
    const uint32_t rate = uplinkBytesPerSecond(row);
    size_t weakest = 0;
    for (size_t i = 0; i < count; ++i) {
      if (memcmp(rows[i].mac, row.mac, 6) == 0 && memcmp(rows[i].bssid, row.bssid, 6) == 0) {
        if (rate > uplinkBytesPerSecond(rows[i])) rows[i] = row;
        return;
      }
      if (uplinkBytesPerSecond(rows[i]) < uplinkBytesPerSecond(rows[weakest])) weakest = i;
    }
    if (count < N) {
      rows[count++] = row;
    } else if (rate > uplinkBytesPerSecond(rows[weakest])) {
      rows[weakest] = row;
    }
  }

 public:
  // Busiest senders first, so that what a slow link cuts off is the rest.
  void sort() {
    for (size_t i = 1; i < count; ++i) {
      const ClientTraffic row = rows[i];
      const uint32_t rate = uplinkBytesPerSecond(row);
      size_t j = i;
      for (; j > 0 && uplinkBytesPerSecond(rows[j - 1]) < rate; --j) rows[j] = rows[j - 1];
      rows[j] = row;
    }
  }
};

// ---------------------------------------------------------------------------
// The streaming rule
// ---------------------------------------------------------------------------

// 64 kbit/s heard. A camera sends several times that, but the sensor does not
// hear every frame. Not calibrated on a real camera yet.
inline constexpr uint32_t kStreamMinBytesPerSecond = 8000;
// Media goes out in large frames. TCP acknowledgements of a download are about
// 100 bytes, and their download may be in frames the sensor cannot decode.
inline constexpr uint32_t kStreamMinFrameBytes = 200;
// Sends at least this many times what it receives: a call is symmetrical.
inline constexpr uint32_t kStreamUpOverDown = 2;
// Share of the 100 ms slots with uplink in them. A feed does not pause.
inline constexpr uint32_t kStreamBusyPercent = 60;
// Sweeps in a row it has to keep up, and how many are remembered.
inline constexpr uint8_t kStreamSweeps = 3;
inline constexpr uint8_t kStreamSweepsKept = 4;

inline bool looksLikeStream(const ClientTraffic &c) {
  if (c.upFrames == 0 || c.listenMs == 0) return false;
  return uplinkBytesPerSecond(c) >= kStreamMinBytesPerSecond &&
         c.upBytes >= kStreamMinFrameBytes * c.upFrames &&
         c.upBytes / kStreamUpOverDown >= c.downBytes &&
         busySlots(c) * 100u >= kStreamBusyPercent * totalSlots(c.listenMs);
}

// Remembers across sweeps who keeps streaming. Each sweep that looks like a
// stream adds one, each that does not takes one off, so a single missed sweep
// changes nothing and a feed that stopped is dropped within two.
template <size_t N>
struct StreamWatch {
  struct Row {
    uint8_t mac[6];
    uint8_t score;
    bool seen;
    bool announced;
  };
  Row rows[N];
  size_t count = 0;

  // True when a client has just become a steady one. The sensor announces
  // that moment, not every sweep after it.
  bool note(const ClientTraffic *clients, size_t clientCount) {
    for (size_t i = 0; i < count; ++i) rows[i].seen = false;
    for (size_t i = 0; i < clientCount; ++i) {
      if (!looksLikeStream(clients[i])) continue;
      Row *row = find(clients[i].mac);
      if (row == nullptr) {
        if (count >= N) continue;
        row = &rows[count++];
        memcpy(row->mac, clients[i].mac, 6);
        row->score = 0;
        row->announced = false;
        row->seen = false;
      }
      // One device can be heard under two access points; it still counts once.
      if (row->seen) continue;
      row->seen = true;
      if (row->score < kStreamSweepsKept) row->score++;
    }
    bool fresh = false;
    size_t kept = 0;
    for (size_t i = 0; i < count; ++i) {
      Row row = rows[i];
      if (!row.seen) row.score--;
      if (row.score == 0) continue;
      if (row.score >= kStreamSweeps && !row.announced) {
        row.announced = true;
        fresh = true;
      }
      rows[kept++] = row;
    }
    count = kept;
    return fresh;
  }

  bool steady(const uint8_t *mac) {
    const Row *row = find(mac);
    return row != nullptr && row->score >= kStreamSweeps;
  }

 private:
  Row *find(const uint8_t *mac) {
    for (size_t i = 0; i < count; ++i) {
      if (memcmp(rows[i].mac, mac, 6) == 0) return &rows[i];
    }
    return nullptr;
  }
};

// ---------------------------------------------------------------------------
// The 0x25 record
// ---------------------------------------------------------------------------

inline constexpr size_t kClientTrafficLength = 32;

inline void encodeClientTraffic(uint8_t out[kClientTrafficLength], uint16_t scanId,
                                const ClientTraffic &c) {
  out[0] = kMsgClientTraffic;
  putU16(out + 1, scanId);
  memcpy(out + 3, c.mac, 6);
  memcpy(out + 9, c.bssid, 6);
  out[15] = c.channel;
  out[16] = static_cast<uint8_t>(c.rssi);
  putU16(out + 17, c.listenMs);
  putU32(out + 19, c.upBytes);
  putU16(out + 23, c.upFrames);
  putU32(out + 25, c.downBytes);
  putU16(out + 29, c.downFrames);
  out[31] = busySlots(c);
}
