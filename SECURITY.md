# Security

## Reporting a vulnerability

Please report security problems privately through GitHub:
**Security → Report a vulnerability** on this repository (private
vulnerability reporting). Do not open a public issue for them.

Include what is affected (Android app, iOS app, tunnel extension, sensor
firmware, BLE protocol), the version or commit, and how to reproduce it.
This is a personal project maintained in spare time: expect an answer within
a couple of weeks, not hours. There is no bug bounty.

Problems that matter most here:

- anything that lets another device read or change data held by the app
  (scan history, the assistant's encrypted chat store, saved Wi-Fi
  credentials on the sensor);
- the local certificate authority used by the traffic inspector: its key
  leaving the device, or certificates it issues being trusted outside the
  app's own tunnel;
- the sensor accepting firmware, commands or Wi-Fi credentials over
  Bluetooth from a phone it should not trust;
- parsers that read untrusted input (802.11 frames, `.pcap` files, BLE
  advertisements, HTTP responses, web pages fetched by the assistant)
  crashing or misbehaving on crafted data.

## What Spectra is for

Spectra is a defensive tool for auditing **your own** networks and devices,
or ones you have written permission to test. It is built to observe:

- The sensor firmware listens in promiscuous mode. It **detects**
  deauthentication and disassociation frames sent by others; it does not
  transmit them, and it does not inject any 802.11 frames.
- The LoRa radio on the optional Cap LoRa-1262 is used **only to receive**.
- Raw frame capture to `.pcap` records management and data frames on one
  channel, optionally filtered to one BSSID. On a WPA network those frames
  include the four-way handshake of any client that joins. Capture only
  networks you own or are authorised to test, and treat the files as
  sensitive: they contain other people's device addresses and, on open
  networks, their traffic.
- The traffic inspector (iOS packet tunnel, Android `VpnService`) sees only
  the phone's own traffic. Decrypting TLS requires the user to install and
  trust a certificate authority that the app generates on the device.
- LAN discovery and port checks run against the subnet the phone has joined.

Using these features against networks, devices or traffic you are not
authorised to inspect may be illegal where you live. You are responsible
for how you use this software.

## Handling sensitive data

- Scans, history and the assistant's chats stay on the phone. The assistant
  runs locally; web lookups happen only when the user asks for them.
- Wi-Fi passwords entered on the sensor's **Join** screen are stored in the
  ESP32's NVS flash in plain text, so anyone with physical access to the
  board can read them. Erase the flash before giving a sensor away.
- On Android, the traffic inspector's certificate authority is kept in
  storage that is never backed up or transferred, and its private keys are
  sealed with a key held in the Android Keystore. Decrypted traffic and the
  assistant's encrypted chats are also left out of backups.
- Exported `.pcap` files and history exports are not encrypted.
