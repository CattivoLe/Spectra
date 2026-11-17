#pragma once

// Which board this firmware is built for. The Cardputer is the default.
// SPECTRA_BOARD_C5 and SPECTRA_BOARD_WROOM32 come from platformio.ini.
//
// The two dev boards have no screen, keyboard, battery gauge or LoRa cap.
// The phone drives them over BLE. The C5 hears 5 GHz as well as 2.4 GHz.
//
// SPECTRA_ALERT_OUTPUTS is what the board can alert with, in the bits of the
// alert flags (kAlertSound, kAlertLed): the Cardputer has a speaker and an RGB
// LED, the ESP32-C5-DevKitC-1 an addressable RGB LED (WS2812 on GPIO27), the
// WROOM-32 board nothing the firmware drives.

#if defined(SPECTRA_BOARD_C5)
#define SPECTRA_SENSOR_NAME "ESP32-C5"
#define SPECTRA_CHIP_NAME "ESP32-C5"
#define SPECTRA_BANDS_JSON "\"2.4\",\"5\""
#define SPECTRA_HAS_UI 0
#define SPECTRA_HAS_LORA 0
#define SPECTRA_HAS_BATTERY 0
#define SPECTRA_WIFI_5G 1
#define SPECTRA_RGB_LED_PIN 27
#define SPECTRA_ALERT_OUTPUTS 0x02
#elif defined(SPECTRA_BOARD_WROOM32)
#define SPECTRA_SENSOR_NAME "WROOM32"
#define SPECTRA_CHIP_NAME "ESP32"
#define SPECTRA_BANDS_JSON "\"2.4\""
#define SPECTRA_HAS_UI 0
#define SPECTRA_HAS_LORA 0
#define SPECTRA_HAS_BATTERY 0
#define SPECTRA_WIFI_5G 0
#define SPECTRA_ALERT_OUTPUTS 0x00
#else
#define SPECTRA_SENSOR_NAME "M5Cardputer"
#define SPECTRA_CHIP_NAME "ESP32-S3"
#define SPECTRA_BANDS_JSON "\"2.4\""
#define SPECTRA_HAS_UI 1
#define SPECTRA_HAS_LORA 1
#define SPECTRA_HAS_BATTERY 1
#define SPECTRA_WIFI_5G 0
#define SPECTRA_ALERT_OUTPUTS 0x03
#endif
