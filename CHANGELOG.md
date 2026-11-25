# Changelog

Each `## X.Y.Z` section is the text of the GitHub release for tag `vX.Y.Z`
(see [docs/releasing.md](docs/releasing.md)). The version is the Android app's
`versionName`; the sensor firmware keeps its own version, which the release
notes name.

## Unreleased

Web interfaces:

- A list of your devices' web pages on the LAN tab, on Android and iPhone: a tap
  opens one in the browser, without a scan first. A page is added with the
  bookmark next to its port on the device's screen; its name, address, icon
  and colour can be changed, with icons suggested by what the device is, and the
  links put in any order. When the router gives the device another address, the
  next LAN scan updates the link.

Assistant:

- A ✨ summary now ties its subject to what the app's other screens know about
  it, on Android and iPhone. A device of the local network shows the Wi-Fi
  network it is on and its signal at the sensor, the ports the router forwards
  to it from the internet (and whether they answer right now), a steady data
  stream the sensor counted from it, its Bluetooth side, and the phone's
  recorded traffic to it. A Wi-Fi network shows the devices of the LAN scan
  on it, its router, and the connection check made through it. The internet
  summary names the device behind each port that is open from outside, and
  recorded traffic names a server that is a device of your own network.
- The local network summary counts devices the sensor hears on your Wi-Fi
  that the network scan did not find.

Other:

- The app opens on the tab it was left on.

## 1.3.0 (2026-11-25)

Android and iPhone apps. The sensor firmware is unchanged.

Wi-Fi handshakes:

- The pcap decode screen now shows whether a capture holds each of the EAPOL
  4-way handshake messages (M1-M4), on Android and iPhone.
- During a sensor capture the same M1-M4 indicator fills in live, as the
  frames arrive.
- A captured handshake can be exported from the decode screen as a hashcat
  22000 file (`WPA*02` for the 4-way handshake, `WPA*01` for a PMKID), to
  recover the password of a network you own with hashcat on a computer.

Speed test:

- Upload gives a figure on a slow mobile uplink. Every stream used to send a
  whole megabyte at a time, which counts only once the server has answered; on a
  slow line none was answered within the eight seconds. Bodies start at 16 KB
  now and grow with the line.
- iPhone: a stalled stream no longer keeps the test going past its end, an
  upload connection that drops is opened again, and a direction that could not
  be measured says so.

## 1.2.1 (2026-11-23)

Android and iPhone apps. The sensor firmware is unchanged.

Traffic recording:

- An app is answered only after the server is. On a network whose IPv6 leads
  nowhere, apps stayed with the dead address while recording; they go on to IPv4
  now. A refused connection is reported to the app at once (iPhone).
- Android: an app that refuses the certificate no longer leaves its host unread
  for other apps.
- The screen says that an installed certificate needs nothing else, and, when
  only apps that refuse it were used, how to check that decryption works.

## 1.2.0 (2026-11-23)

Android and iPhone apps, sensor firmware 0.11.0. The iPhone app is level with
the Android one and has the same version from now on.

Traffic recording:

- Everything Charles shows: request and response bodies of any type, timing and
  sizes, HTTP/2 and WebSocket messages, HAR and cURL export, and a list of hosts
  to decrypt. Requests can be repeated and edited, answered by the phone (Map
  Local), and connections slowed down.
- Requests and connections have a screen of their own, with filters on it (GET,
  POST, PUT, PATCH, DELETE, errors, WebSocket; HTTP, TLS, DNS, TCP, UDP). TCP and
  UDP show everything that went over them. JSON bodies are coloured, and the
  whole body can be searched.
- Requests can be grouped by endpoint: method, server and path, with numbers,
  UUIDs and long tokens in the path shown as `{id}`. A group shows how many
  requests it has, how many failed, the typical and the longest time, the size
  and the last request; filters and search work inside it.
- ✨ on a recording, an endpoint, a request and a connection: whom it goes to,
  what is wrong and what to check next. The app's own rules look for passwords,
  tokens and keys, unencrypted HTTP, errors, slow and large answers, needless
  repeats, missing compression and caching, weak cookies, known analytics and
  advertising services, and refused certificates. Values never reach the summary
  or the model, only their kind and place, and nothing from recorded traffic
  goes to the web search.
- Android: the certificate can be saved and installed again. iPhone: HTTPS is
  really decrypted now; it never was before, and a refused certificate no longer
  stops the recording.

Sensor and Wi‑Fi:

- A stream is traced to the device that sends it. The sensor (protocol 10) counts
  what each client sends and receives, says for how long it listened, and stays
  longer on channels with access points. A device that keeps sending like a
  camera or a microphone for three sweeps is named with its maker, address and
  LAN name. With an older sensor the network is judged, at about 320 kbit/s kept
  up for three sweeps; before, only a feed of 4 Mbit/s was noticed. The limits
  are not calibrated on a real camera yet.
- The sensor's own alert uses the same rule and sounds once per device. The
  estimated bitrate is counted over the time the sensor listened.
- "Connected" follows the network the phone is on, and an error is not shown
  once it is there. "Out of range" moved from the chips to the menu. No "Open
  Wi‑Fi" warning when a password is saved for the network or another reading of
  it is protected.

Everywhere:

- "First seen" of a network, a host and a Bluetooth device is the earliest date
  in History (records from other phones do not count).
- History: a session opens onto its records, with no page in between.
- Bluetooth filters are chips on the screen. The search log and the saved
  searches are one list, with what each search found.
- Texts and translations were checked throughout.

Android:

- VPN: a VPN that lets no app around it is no longer measured as if it were
  Wi‑Fi or the mobile network. The connection check, the speed test, the
  traceroute and the device scan say so instead, and follow the VPN as it is
  turned on and off. A VPN that leaves the local network or the internet alone
  does not get in the way. HTTPS banners of LAN devices went into the VPN even
  when it could be bypassed; they no longer do.
- The connection check moved to the mobile network by itself on a phone that
  hides the Wi‑Fi name (location off or not allowed). It now does so only when
  there is no Wi‑Fi.
- Assistant: the old chats are gone. The assistant has worked through summary
  cards since 1.0.0, and the chats it kept from before could only be read; they
  are erased once on the first start of this version. Summaries and their
  folders stay. The code behind the chat is removed with them.

iPhone:

- Assistant: summary cards instead of the chat, as on Android. ✨ on a screen
  opens a card with the findings, the model's words and buttons for the next
  check; More detail and Deep analysis can search the web for the device's
  maker and model. Summaries live in folders. The old chats and the dossier
  are erased on the first start. The Face ID lock stays.
- Tabs: Wi‑Fi, LAN, Internet, Bluetooth and Assistant. The new Internet tab
  holds the link check, the connection check, your sites, traceroute, the
  speed test and the phone's traffic. The clock on each tab opens that tab's own
  history, and deleting or clearing there removes only that tab's records.
- LAN: the scan waits for the local network permission and asks quiet addresses
  once more. Xiaomi Mi Home appliances are found through their UDP hello and
  `_miio._udp`; a full port scan stands for 30 minutes across a rescan.
- Wi‑Fi: a Saved networks screen with the passwords that worked; the join prompt
  can show the password and offers the saved one.
- Bluetooth: the Trackers section is folded to the tags that need a look.

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
