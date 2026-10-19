// Spectra BLE wire protocol constants. The byte layouts live in docs/protocol.md;
// this header is their single home in the firmware, mirrored by SensorProtocol
// on each phone app.

#pragma once

#include <stdint.h>

inline constexpr uint8_t kProtocolVersion = 8;

// All UUIDs share one base; only the first group differs.
inline constexpr char kServiceUuid[] = "53500001-8F2D-4B6A-9C1E-737065637472";
inline constexpr char kInfoUuid[] = "53500002-8F2D-4B6A-9C1E-737065637472";
inline constexpr char kControlUuid[] = "53500003-8F2D-4B6A-9C1E-737065637472";
inline constexpr char kResultsUuid[] = "53500004-8F2D-4B6A-9C1E-737065637472";
inline constexpr char kFirmwareUuid[] = "53500005-8F2D-4B6A-9C1E-737065637472";

// Control characteristic commands (first byte of the write).
inline constexpr uint8_t kCmdScanOnce = 0x01;
inline constexpr uint8_t kCmdSetInterval = 0x02;
inline constexpr uint8_t kCmdAckSaved = 0x03;
inline constexpr uint8_t kCmdScanLora = 0x04;
// The phone's copy of a network. Shown on the sensor; not queued for upload.
inline constexpr uint8_t kCmdRememberWifi = 0x05;
inline constexpr uint8_t kCmdForgetWifi = 0x06;
// The peripheral cannot read the central's name, so the phone sends its own.
inline constexpr uint8_t kCmdSetPeer = 0x07;
// The phone stored the batch the sensor just uploaded. Clear that batch.
inline constexpr uint8_t kCmdAckKnown = 0x08;
// The phone is about to send its whole list. Drop the previous copy.
inline constexpr uint8_t kCmdResetKnown = 0x09;
// Start streaming raw Wi-Fi frames from a single channel (v8). Payload:
// channel (1–13, 0 = keep current), flags (bit0 = filter to the BSSID below),
// BSSID (6 bytes, zeros when the filter is off).
inline constexpr uint8_t kCmdStartRawCapture = 0x0A;
// Stop the raw-frame stream (v8). No payload.
inline constexpr uint8_t kCmdStopRawCapture = 0x0B;

// Results notification message types (first byte).
inline constexpr uint8_t kMsgScanStarted = 0x10;
inline constexpr uint8_t kMsgAccessPoint = 0x11;
inline constexpr uint8_t kMsgScanFinished = 0x12;
inline constexpr uint8_t kMsgStation = 0x13;
inline constexpr uint8_t kMsgProbeReq = 0x14;
inline constexpr uint8_t kMsgDeauth = 0x15;
inline constexpr uint8_t kMsgSSIDReveal = 0x16;
inline constexpr uint8_t kMsgTraffic = 0x17;
inline constexpr uint8_t kMsgVendorIE = 0x18;
inline constexpr uint8_t kMsgBattery = 0x19;
inline constexpr uint8_t kMsgSavedWifi = 0x1A;
inline constexpr uint8_t kMsgSavedBle = 0x1B;
inline constexpr uint8_t kMsgSavedLan = 0x1C;
inline constexpr uint8_t kMsgSavedGps = 0x1D;
inline constexpr uint8_t kMsgLoraDevice = 0x1E;
inline constexpr uint8_t kMsgLoraScan = 0x1F;
inline constexpr uint8_t kMsgLoraProgress = 0x22;
// A network the sensor joined, waiting for the phone to keep it.
inline constexpr uint8_t kMsgKnownWifi = 0x23;
// One captured raw Wi-Fi frame, possibly split across several notifications
// to fit the ATT MTU (v8).
inline constexpr uint8_t kMsgRawFrame = 0x24;
inline constexpr uint8_t kMsgSavedLora = 0x20;
inline constexpr uint8_t kMsgGps = 0x21;
inline constexpr uint8_t kMsgUpdate = 0x30;

// Raw capture limits. A frame is truncated to this many bytes on the wire;
// the full length is still reported so a reader can show it. A short snap
// length keeps per-frame RAM and the narrow BLE link manageable.
inline constexpr uint16_t kRawSnapLen = 256;

// Firmware characteristic commands (first byte of the write).
inline constexpr uint8_t kOtaBegin = 0x01;
inline constexpr uint8_t kOtaData = 0x02;
inline constexpr uint8_t kOtaFinish = 0x03;
inline constexpr uint8_t kOtaAbort = 0x04;

// Update status phases (0x30 message, byte 1).
inline constexpr uint8_t kOtaReady = 1;
inline constexpr uint8_t kOtaProgress = 2;
inline constexpr uint8_t kOtaDone = 3;
inline constexpr uint8_t kOtaFailed = 4;

// Update status error codes (0x30 message, byte 6).
inline constexpr uint8_t kOtaErrNone = 0;
inline constexpr uint8_t kOtaErrBusy = 1;
inline constexpr uint8_t kOtaErrTooLarge = 2;
inline constexpr uint8_t kOtaErrBegin = 3;
inline constexpr uint8_t kOtaErrOffset = 4;
inline constexpr uint8_t kOtaErrWrite = 5;
inline constexpr uint8_t kOtaErrCrc = 6;
inline constexpr uint8_t kOtaErrFinish = 7;
inline constexpr uint8_t kOtaErrAbort = 8;
inline constexpr uint8_t kOtaErrImage = 9;
