# Changelog

Each `## X.Y.Z` section is the text of the GitHub release for tag `vX.Y.Z`
(see [docs/releasing.md](docs/releasing.md)). The version is the Android app's
`versionName`; the sensor firmware keeps its own version, which the release
notes name.

## Unreleased

iPhone app, brought level with Android 1.1.0 (its version is now 1.1.0 too).

- Assistant: summary cards instead of the chat, as on Android. ✨ on a screen
  opens a card with the findings, the model's words and buttons for the next
  check; More detail and Deep analysis can search the web for the device's
  maker and model. Summaries live in folders. The old chats and the dossier
  are erased on the first start. The Face ID lock stays.
- Tabs: Wi‑Fi, LAN, Internet, Bluetooth and Assistant. The new Internet tab
  holds the link check, the connection check, your sites, traceroute, the
  speed test and the phone's traffic. There is no shared History tab: the
  clock on each tab opens that tab's own history, and deleting or clearing
  there removes only that tab's records.
- LAN: Xiaomi Mi Home appliances are found through their UDP hello (sent to
  each address in turn) and `_miio._udp`; a full port scan stands for 30
  minutes across a rescan.
- Wi‑Fi: a Saved networks screen with the passwords that worked (show, copy,
  connect, add, edit, delete); the join prompt can show the password and
  offers the saved one.
- Bluetooth: the Trackers section is folded to the tags that need a look.
- Licenses moved to the Sensor screen's menu; a slim "add a site" field.

Android app.

- Assistant: the old chats are gone. The assistant has worked through summary
  cards since 1.0.0, and the chats it kept from before could only be read;
  they are erased once on the first start of this version. Summaries and
  their folders stay. The code behind the chat (tool calling by the model,
  the search-query planner, pictures by question) is removed with them.

## 1.1.0 (2026-11-22)

Android app. The sensor firmware is unchanged.

- New icon, shared with Probe Lab.
- Assistant: **More detail** and **Deep analysis** on a summary card can search
  the web for the device's maker and model (never an address or a name) and
  show its photo and the sources; every query is listed under What was sent.
  New search services: Tavily, Exa, Firecrawl, You.com and Serper (Google),
  besides SearXNG and Brave.
- Assistant: a stopped summary can be continued; folders live on the main
  screen (tap shows a folder, hold picks where new summaries go, a new folder
  is named by its date); the phone gives a short vibration when the text is written.
- Assistant: the dossier is gone. It could no longer gain notes, and History
  already tells the model what was seen before.
- Wi-Fi: joining uses Android's own "save network" sheet, so the phone really
  switches; WPA3-only and OWE networks work. New **Saved networks** screen with
  the passwords that worked.
- LAN: Xiaomi Mi Home appliances (no TCP ports) are found through their UDP
  hello; a full 65535-port scan is remembered and not offered again.
- Layout: the Check button on narrow screens, a slim "add a site" field, a
  speed chart with a filled curve and a scale, a folded board list and the
  licenses in the Sensor menu.
- Fixed: deleting a summary while it was being written closed the app.

## 1.0.0 (2026-11-22)

First public release.

- Android app (arm64 phones): Wi-Fi networks around you, devices
  on your local network, nearby Bluetooth devices and your internet connection,
  with an on-device assistant (llama.cpp) that explains the results.
- Sensor firmware for the M5Stack Cardputer ADV (ESP32-S3), ESP32-C5 and
  ESP32-WROOM-32. The app flashes and updates it; the same images are attached
  here for flashing over USB.
