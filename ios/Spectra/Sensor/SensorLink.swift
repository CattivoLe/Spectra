import CoreBluetooth
import Foundation
import Observation
import UIKit
import os

/// Connection and firmware update trail, readable in Console as subsystem cattivole.spectra.
private let log = Logger(subsystem: "cattivole.spectra", category: "sensor")

/// The Bluetooth connection to a Spectra sensor (a Cardputer running the
/// firmware in /firmware), or the built-in demo standing in for one.
@Observable
final class SensorLink: NSObject {
    enum State: Equatable {
        case bluetoothUnavailable(Unavailability)
        case idle
        case searching
        case connecting(name: String)
        case connected(name: String)
        case demo
    }

    enum Unavailability: Equatable {
        case starting, poweredOff, unauthorized, unsupported
    }

    struct DiscoveredSensor: Identifiable, Equatable {
        var id: UUID
        var name: String
        var rssi: Int
        var lastSeen: Date
    }

    private(set) var state: State = .bluetoothUnavailable(.starting)
    private(set) var discovered: [DiscoveredSensor] = []
    private(set) var info: SensorInfo?
    private(set) var batteryLevel: Int?
    private(set) var isCharging: Bool?
    private(set) var loraDevices: [SensorProtocol.LoRaDevice] = []
    private(set) var loraScanning = false
    /// Frequency and spreading factor of the dwell in progress. Nil until the
    /// first step of a scan, and again once the scan ends.
    private(set) var loraTune: (freqKHz: UInt32, spreadingFactor: Int, step: Int, total: Int)?
    private var loraWatch: Task<Void, Never>?
    private let knownNetworks = KnownNetworks()
    struct CapFix: Equatable {
        var latitude: Double
        var longitude: Double
        var satellites: Int
    }
    private(set) var capFix: CapFix?
    /// Set while an application image is being sent to the sensor.
    private(set) var firmwareUpdate: FirmwareUpdateProgress?

    // MARK: Raw frame capture

    /// A short, thinned sniff of one channel, collected from `0x24` frames and
    /// written to a `.pcap` on the phone.
    private(set) var rawCapturing = false
    private(set) var rawFrameCount = 0
    private(set) var rawByteCount = 0
    /// True once any frame was lost — the capture has gaps, which is expected
    /// over BLE and worth saying in the UI.
    private(set) var rawDropped = false
    private var rawFrames: [PcapWriter.Frame] = []
    private var rawAssembler = RawFrameAssembler()
    private var rawCaptureStart: Date?
    private var rawFirstDeviceTs: UInt32?
    private var demoRawTask: Task<Void, Never>?
    /// Stop on our own once a capture reaches these, so a run cannot grow without
    /// bound on the narrow BLE link.
    static let rawFrameCap = 2000
    static let rawByteCap = 1_000_000

    /// Seconds between automatic scans while connected; 0 means scan only on
    /// request. Remembered across launches.
    var liveInterval: Int {
        didSet {
            UserDefaults.standard.set(liveInterval, forKey: Keys.liveInterval)
            send(.setInterval(seconds: liveInterval))
            send(.setPeer(name: UIDevice.current.name))
            demo?.interval = liveInterval
        }
    }

    var isConnected: Bool {
        switch state {
        case .connected, .demo: true
        default: false
        }
    }

    private let store: NetworkStore
    private let history: HistoryStore
    private var central: CBCentralManager!
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var sensor: CBPeripheral?
    private var control: CBCharacteristic?
    private var firmware: CBCharacteristic?
    private var userDisconnected = false
    private var firmwareWriteWait: CheckedContinuation<Void, Error>?
    private var firmwareWriteGeneration = 0
    private var pendingStatuses: [FirmwareUpdate.Status] = []
    private var firmwareCancel = false
    private var updateTask: Task<Void, Never>?
    /// Set when a drop should be followed by a scan, not by sitting on
    /// "Not connected". A firmware install and a reboot both set it.
    private var shouldReconnect = false
    private var demo: DemoSensor?

    private enum Keys {
        static let lastSensor = "lastSensorIdentifier"
        static let liveInterval = "liveInterval"
    }

    static let liveIntervals = [0, 10, 30, 60, 120, 180, 240]

    static func intervalLabel(_ seconds: Int) -> String {
        if seconds <= 0 { return String(localized: "Off") }
        if seconds.isMultiple(of: 60) {
            return String(localized: "Every \(seconds / 60) min")
        }
        return String(localized: "Every \(seconds) s")
    }

    init(store: NetworkStore, history: HistoryStore) {
        self.store = store
        self.history = history
        let saved = UserDefaults.standard.object(forKey: Keys.liveInterval) as? Int
        liveInterval = saved.flatMap { Self.liveIntervals.contains($0) ? $0 : nil } ?? 30
        super.init()
        central = CBCentralManager(delegate: self, queue: nil)
    }

    // MARK: Actions

    func startSearching() {
        beginScan(autoConnect: false)
    }

    func stopSearching() {
        guard state == .searching else { return }
        central.stopScan()
        state = .idle
    }

    func connect(to id: UUID) {
        guard let peripheral = peripherals[id] else { return }
        central.stopScan()
        userDisconnected = false
        sensor = peripheral
        peripheral.delegate = self
        state = .connecting(name: name(of: peripheral))
        central.connect(peripheral)
    }

    func disconnect() {
        userDisconnected = true
        shouldReconnect = false
        UserDefaults.standard.removeObject(forKey: Keys.lastSensor)
        if let sensor {
            central.cancelPeripheralConnection(sensor)
        }
        resetConnection()
        state = .idle
    }

    func scanLoRa() {
        guard state != .demo, !loraScanning else { return }
        loraDevices = []
        loraTune = nil
        loraScanning = true
        armLoraWatch()
        send(.scanLoRa)
    }

    /// The line the Cardputer draws while it dwells: frequency, SF, and how
    /// far through the channel list this step is.
    var loraStatus: String {
        guard let tune = loraTune else {
            return String(localized: "Scanning the 864–870 MHz band…")
        }
        return String(
            format: "%d.%03d MHz · SF%d · %d/%d",
            Int(tune.freqKHz / 1000),
            Int(tune.freqKHz % 1000),
            tune.spreadingFactor,
            tune.step,
            tune.total
        )
    }

    /// A quiet pass is about forty seconds. If the end notice is lost, stop
    /// the spinner once the link has been silent for two minutes.
    private func armLoraWatch() {
        loraWatch?.cancel()
        loraWatch = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 120_000_000_000)
            guard !Task.isCancelled, let self, self.loraScanning else { return }
            self.loraScanning = false
            self.loraTune = nil
        }
    }

    private func endLoraScan() {
        loraWatch?.cancel()
        loraWatch = nil
        loraScanning = false
        loraTune = nil
    }

    func scanNow() {
        if let demo {
            demo.scan()
        } else {
            send(.scanOnce)
        }
    }

    /// Begin a raw-frame capture on `channel`. `bssid` is an optional filter;
    /// left nil, the sensor sends every frame on the channel.
    func startRawCapture(channel: Int, bssid: BSSID? = nil) {
        guard isConnected, !rawCapturing else { return }
        rawFrames.removeAll(keepingCapacity: true)
        rawAssembler = RawFrameAssembler()
        rawFrameCount = 0
        rawByteCount = 0
        rawDropped = false
        rawCaptureStart = .now
        rawFirstDeviceTs = nil
        rawCapturing = true
        if demo != nil {
            startDemoRawCapture(channel: channel)
        } else {
            send(.startRawCapture(channel: channel, bssid: bssid))
        }
    }

    func stopRawCapture() {
        guard rawCapturing else { return }
        rawCapturing = false
        demoRawTask?.cancel()
        demoRawTask = nil
        if demo == nil {
            send(.stopRawCapture)
        }
    }

    /// The captured frames as a `.pcap` (radiotap link type), ready to share.
    func rawCapturePcap() -> Data {
        PcapWriter.pcap(rawFrames)
    }

    /// Drop a capture in progress because the link or the demo went away. The
    /// frames already collected stay, so a finished capture can still be saved.
    private func endRawCapture() {
        rawCapturing = false
        demoRawTask?.cancel()
        demoRawTask = nil
    }

    /// Feeds synthetic beacons so the capture flow works in the simulator's demo.
    private func startDemoRawCapture(channel: Int) {
        demoRawTask?.cancel()
        demoRawTask = Task { [weak self] in
            var seq: UInt16 = 0
            let start = DispatchTime.now().uptimeNanoseconds
            while !Task.isCancelled {
                guard let self, self.rawCapturing else { return }
                let frame = Self.demoBeacon(channel: channel, seq: seq)
                let micros = UInt32(truncatingIfNeeded: (DispatchTime.now().uptimeNanoseconds - start) / 1000)
                let fragment = SensorProtocol.RawFrameFragment(
                    seq: seq, fragmentIndex: 0, fragmentCount: 1,
                    timestampMicros: micros, channel: channel,
                    rssi: Int.random(in: -82 ... -42), originalLength: frame.count, payload: frame
                )
                self.handleRawFrame(fragment)
                seq &+= 1
                try? await Task.sleep(for: .milliseconds(140))
            }
        }
    }

    /// A minimal 802.11 beacon for the demo: enough for the decoder to name it.
    private static func demoBeacon(channel: Int, seq: UInt16) -> [UInt8] {
        let bssid: [UInt8] = [0x02, 0x00, 0x5E, 0x10, 0x00, UInt8(truncatingIfNeeded: Int(seq) % 4)]
        var frame: [UInt8] = [0x80, 0x00, 0x00, 0x00]      // beacon, no duration
        frame += [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]      // addr1 broadcast
        frame += bssid                                      // addr2
        frame += bssid                                      // addr3
        frame += [0x00, 0x00]                               // sequence control
        frame += [UInt8](repeating: 0, count: 8)            // timestamp
        frame += [0x64, 0x00]                               // beacon interval
        frame += [0x01, 0x04]                               // capability info
        let ssid = Array("Spectra-Demo".utf8)
        frame += [0x00, UInt8(ssid.count)] + ssid           // SSID element
        frame += [0x03, 0x01, UInt8(clamping: channel)]     // DS parameter set
        return frame
    }

    private func handleRawFrame(_ fragment: SensorProtocol.RawFrameFragment) {
        guard rawCapturing else { return }
        guard let completed = rawAssembler.ingest(fragment) else {
            rawDropped = rawAssembler.dropped > 0
            return
        }
        if rawFirstDeviceTs == nil { rawFirstDeviceTs = completed.timestampMicros }
        let base = rawCaptureStart ?? .now
        // Unsigned subtraction wraps cleanly across the sensor's 32-bit clock.
        let delta = completed.timestampMicros &- (rawFirstDeviceTs ?? completed.timestampMicros)
        rawFrames.append(PcapWriter.Frame(
            timestamp: base.addingTimeInterval(Double(delta) / 1_000_000),
            channel: completed.channel,
            rssi: completed.rssi,
            originalLength: completed.originalLength,
            bytes: completed.bytes
        ))
        rawFrameCount = rawFrames.count
        rawByteCount += completed.bytes.count
        rawDropped = rawAssembler.dropped > 0
        if rawFrameCount >= Self.rawFrameCap || rawByteCount >= Self.rawByteCap {
            stopRawCapture()
        }
    }

    func installFirmware(_ image: Data) {
        guard firmwareUpdate?.isRunning != true else { return }
        do {
            try FirmwareUpdate.validate(image)
        } catch let error as FirmwareUpdate.ImageError {
            firmwareUpdate = FirmwareUpdateProgress(stage: .failed(error.message))
            return
        } catch {
            firmwareUpdate = FirmwareUpdateProgress(stage: .failed(String(localized: "Could not read the firmware file.")))
            return
        }
        firmwareCancel = false
        pendingStatuses.removeAll()
        firmwareUpdate = FirmwareUpdateProgress(stage: .preparing)
        updateTask = Task {
            guard await self.waitUntilReadyForUpdate() else {
                if self.firmwareCancel {
                    self.firmwareUpdate = FirmwareUpdateProgress(stage: .failed(FirmwareUpdate.TransferError.cancelled.message))
                } else {
                    self.firmwareUpdate = FirmwareUpdateProgress(stage: .failed(self.notReadyMessage))
                    self.shouldReconnect = true
                    self.beginScan(autoConnect: true)
                }
                return
            }
            await self.runFirmwareUpdate(image)
        }
    }

    func cancelFirmwareUpdate() {
        firmwareCancel = true
    }

    func dismissFirmwareUpdate() {
        guard firmwareUpdate?.isRunning != true else { return }
        firmwareUpdate = nil
    }

    func failFirmwareUpdate(_ message: String) {
        guard firmwareUpdate?.isRunning != true else { return }
        firmwareUpdate = FirmwareUpdateProgress(stage: .failed(message))
    }

    func setDemo(_ on: Bool) {
        if on {
            if sensor != nil { disconnect() }
            central.stopScan()
            let demo = DemoSensor { [weak self] data in self?.receive(data) }
            demo.interval = liveInterval
            self.demo = demo
            info = SensorInfo(proto: SensorProtocol.version, fw: String(localized: "Demo"), chip: String(localized: "Simulated"), bands: ["2.4"], interval: liveInterval)
            batteryLevel = 72
            isCharging = false
            state = .demo
            demo.scan()
        } else {
            demo?.stop()
            demo = nil
            info = nil
            loraDevices = []
            endLoraScan()
            endRawCapture()
            capFix = nil
            store.abandonScan()
            updateIdleState()
        }
    }

    // MARK: Plumbing

    /// The sensor draws this list. Eighty is as many as its screen buffer keeps.
    private func pushKnownNetworks() {
        send(.resetKnown)
        for network in knownNetworks.all().prefix(80) {
            send(.rememberWifi(ssid: network.ssid, password: network.password))
        }
    }

    private func send(_ command: SensorProtocol.Command) {
        guard let sensor, let control, case .connected = state else { return }
        sensor.writeValue(command.data, for: control, type: .withResponse)
    }

    private func receive(_ data: Data) {
        if let status = FirmwareUpdate.Status.decode(data) {
            if status.phase != .progress {
                log.notice("update status \(String(describing: status.phase), privacy: .public) accepted \(status.bytesAccepted) failure \(String(describing: status.failure), privacy: .public)")
            }
            pendingStatuses.append(status)
            return
        }
        if let message = SensorProtocol.decode(data) {
            if case .savedPosition(let captureID, let latitude, let longitude) = message {
                history.notePosition(captureID: captureID, source: sensorSource, latitude: latitude, longitude: longitude)
                return
            }
            if case .gps(let valid, let latitude, let longitude, let satellites) = message {
                capFix = valid ? CapFix(latitude: latitude, longitude: longitude, satellites: satellites) : nil
                return
            }
            if case .loraScan(_, let started, _, _) = message {
                if started {
                    loraDevices = []
                    loraTune = nil
                    loraScanning = true
                    armLoraWatch()
                } else {
                    endLoraScan()
                }
                return
            }
            if case .loraProgress(_, let freqKHz, let spreadingFactor, let step, let total) = message {
                loraTune = (freqKHz, spreadingFactor, step, total)
                loraScanning = true
                armLoraWatch()
                return
            }
            if case .lora(_, let device) = message {
                if let index = loraDevices.firstIndex(where: {
                    $0.freqKHz == device.freqKHz && $0.spreadingFactor == device.spreadingFactor && $0.id == device.id
                }) {
                    if device.rssi > loraDevices[index].rssi {
                        loraDevices[index] = device
                    }
                } else {
                    loraDevices.append(device)
                }
                loraDevices.sort { $0.rssi > $1.rssi }
                return
            }
            if case .saved(let record) = message {
                if history.ingest(record, source: sensorSource) {
                    send(.ackSaved(captureID: record.captureID))
                }
                return
            }
            if case .knownWifi(let index, let total, let ssid, let password) = message {
                knownNetworks.remember(ssid: ssid, password: password)
                if index + 1 == total {
                    send(.ackKnown)
                    pushKnownNetworks()
                }
                return
            }
            if case .rawFrame(let fragment) = message {
                handleRawFrame(fragment)
                return
            }
            if case .battery(_, let level, let charging) = message {
                batteryLevel = level
                isCharging = charging
            }
            store.handle(message)
        }
    }

    private var sensorSource: String {
        if case .connected(let name) = state { return name }
        return "Cardputer"
    }

    private func name(of peripheral: CBPeripheral) -> String {
        peripheral.name ?? String(localized: "Spectra sensor")
    }

    private func resetConnection() {
        sensor = nil
        control = nil
        firmware = nil
        info = nil
        loraDevices = []
        endLoraScan()
        endRawCapture()
        capFix = nil
        batteryLevel = nil
        isCharging = nil
        store.abandonScan()
        finishFirmwareWrite(FirmwareUpdate.TransferError.linkLost)
    }

    private func updateIdleState() {
        switch central.state {
        case .poweredOn: state = .idle
        case .poweredOff: state = .bluetoothUnavailable(.poweredOff)
        case .unauthorized: state = .bluetoothUnavailable(.unauthorized)
        case .unsupported: state = .bluetoothUnavailable(.unsupported)
        default: state = .bluetoothUnavailable(.starting)
        }
    }

    /// Reconnects to the sensor used last time, if iOS still knows it.
    @discardableResult
    private func restoreLastSensor() -> Bool {
        guard sensor == nil,
              let saved = UserDefaults.standard.string(forKey: Keys.lastSensor),
              let id = UUID(uuidString: saved),
              let peripheral = central.retrievePeripherals(withIdentifiers: [id]).first
        else { return false }
        peripherals[id] = peripheral
        connect(to: id)
        return true
    }

    private func beginScan(autoConnect: Bool) {
        guard central.state == .poweredOn, sensor == nil else { return }
        shouldReconnect = autoConnect
        discovered = []
        state = .searching
        central.scanForPeripherals(
            withServices: [SensorProtocol.service],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
    }

    /// The file picker and a reboot both drop the link. Wait until the
    /// characteristics are back before sending an image.
    private func waitUntilReadyForUpdate() async -> Bool {
        log.notice("update waits for the link: state \(String(describing: self.state), privacy: .public), firmware characteristic \(self.firmware != nil)")
        if case .connected = state, firmware != nil { return true }
        shouldReconnect = true
        if case .connecting = state {
            // A connection request is already pending.
        } else if !restoreLastSensor() {
            beginScan(autoConnect: true)
        }
        let deadline = ContinuousClock.now.advanced(by: .seconds(25))
        while ContinuousClock.now < deadline {
            if firmwareCancel { return false }
            if case .connected = state, firmware != nil { return true }
            if case .idle = state {
                shouldReconnect = true
                if !restoreLastSensor() { beginScan(autoConnect: true) }
            }
            try? await Task.sleep(for: .milliseconds(200))
        }
        return false
    }

    // MARK: Firmware update

    /// The link can drop while the sensor finishes a sweep or during the
    /// transfer; the sensor abandons a half-written image when it does. The
    /// update then starts over on the next link, up to three tries.
    private func runFirmwareUpdate(_ image: Data) async {
        let resumeInterval = liveInterval
        var attempt = 0
        while true {
            attempt += 1
            switch await attemptFirmwareUpdate(image) {
            case .finished:
                if case .failed = firmwareUpdate?.stage {
                    send(.setInterval(seconds: resumeInterval))
                }
                return
            case .retry(let error) where attempt < 3 && !firmwareCancel:
                firmwareUpdate = FirmwareUpdateProgress(stage: .preparing)
                guard await waitUntilReadyForUpdate() else {
                    firmwareUpdate = FirmwareUpdateProgress(stage: .failed(firmwareCancel
                        ? FirmwareUpdate.TransferError.cancelled.message
                        : error == .notConnected ? notReadyMessage : error.message))
                    return
                }
            case .retry(let error):
                firmwareUpdate = FirmwareUpdateProgress(stage: .failed(firmwareCancel
                    ? FirmwareUpdate.TransferError.cancelled.message
                    : error.message))
                send(.setInterval(seconds: resumeInterval))
                return
            }
        }
    }

    private enum UpdateOutcome {
        case finished
        /// The link went away; worth another go on a fresh one.
        case retry(FirmwareUpdate.TransferError)
    }

    private func attemptFirmwareUpdate(_ image: Data) async -> UpdateOutcome {
        log.notice("update attempt, \(image.count) bytes, chunk \(self.currentChunkSize())")
        send(.setInterval(seconds: 0))
        await waitUntilNotScanning()
        if firmwareCancel {
            firmwareUpdate = FirmwareUpdateProgress(stage: .failed(FirmwareUpdate.TransferError.cancelled.message))
            return .finished
        }
        // A sweep that was running can take the link down with it.
        guard case .connected = state, firmware != nil else {
            log.error("update: link gone after the sweep, state \(String(describing: self.state), privacy: .public)")
            return .retry(.notConnected)
        }
        pendingStatuses.removeAll()

        var upload = FirmwareUpload(image: image, chunkSize: currentChunkSize())
        var packet: Data? = upload.begin()
        do {
            while let current = packet {
                if firmwareCancel { throw FirmwareUpdate.TransferError.cancelled }
                try await writeFirmware(current)
                switch upload.phase {
                case .waitReady, .waitDone:
                    // Erasing the inactive slot, and checking it at the end,
                    // both outlast a normal command. The write itself returns
                    // immediately; Ready and Done arrive when the flash is done.
                    let status = try await waitForStatus(timeout: .seconds(120))
                    if upload.phase == .waitReady, status.phase == .failed, status.failure == .busy {
                        // The sensor was still sweeping. Let it finish and start over.
                        return .retry(.notConnected)
                    }
                    packet = upload.handle(status)
                    if upload.phase == .sending {
                        firmwareUpdate = FirmwareUpdateProgress(stage: .uploading(sent: 0, total: image.count))
                    }
                case .sending:
                    if let failure = consumeFailure() {
                        packet = upload.handle(FirmwareUpdate.Status(phase: .failed, bytesAccepted: 0, failure: failure))
                        break
                    }
                    let acknowledged = upload.offset
                    packet = upload.onWriteCompleted()
                    if upload.phase == .waitDone {
                        firmwareUpdate = FirmwareUpdateProgress(stage: .finishing)
                    } else {
                        firmwareUpdate = FirmwareUpdateProgress(stage: .uploading(sent: acknowledged, total: image.count))
                    }
                default:
                    packet = nil
                }
                if case .succeeded = upload.phase {
                    firmwareUpdate = FirmwareUpdateProgress(stage: .rebooting)
                    await waitForReboot()
                    return .finished
                }
                if case .failed(let failure) = upload.phase {
                    firmwareUpdate = FirmwareUpdateProgress(stage: .failed(failure.message))
                    return .finished
                }
            }
            firmwareUpdate = FirmwareUpdateProgress(stage: .failed(FirmwareUpdate.TransferError.linkLost.message))
            return .finished
        } catch let error as FirmwareUpdate.TransferError {
            log.error("update attempt failed: \(String(describing: error), privacy: .public) in \(String(describing: upload.phase), privacy: .public) at \(upload.offset)")
            if case .cancelled = error, case .connected = state {
                try? await writeFirmware(upload.abortCommand)
            }
            switch error {
            case .notConnected, .linkLost:
                // The sensor dropped the half-done image with the link; start over.
                return .retry(error)
            default:
                firmwareUpdate = FirmwareUpdateProgress(stage: .failed(error.message))
                return .finished
            }
        } catch {
            log.error("update write failed: \(error.localizedDescription, privacy: .public) in \(String(describing: upload.phase), privacy: .public) at \(upload.offset)")
            if case .connected = state {
                try? await writeFirmware(upload.abortCommand)
            }
            firmwareUpdate = FirmwareUpdateProgress(stage: .failed(FirmwareUpdate.TransferError.linkLost.message))
            return .finished
        }
    }

    /// Why the update could not start. Connected without the Firmware
    /// characteristic means iOS is using a list of the sensor's services from
    /// before it could take updates.
    private var notReadyMessage: String {
        if case .connected = state, firmware == nil {
            return String(localized: "iOS does not see the sensor's update channel, probably from an old cached list of its services. Turn Bluetooth off and on in Settings, reconnect and try again.")
        }
        return FirmwareUpdate.TransferError.notConnected.message
    }

    /// Preparing and sending: a sweep now would compete with the transfer for the radio.
    private var updateHoldsScans: Bool {
        switch firmwareUpdate?.stage {
        case .preparing, .uploading, .finishing: true
        default: false
        }
    }

    private func waitUntilNotScanning() async {
        let deadline = ContinuousClock.now.advanced(by: .seconds(20))
        while store.isScanning, ContinuousClock.now < deadline, !firmwareCancel {
            try? await Task.sleep(for: .milliseconds(200))
        }
    }

    private func waitForStatus(timeout: Duration) async throws -> FirmwareUpdate.Status {
        let deadline = ContinuousClock.now.advanced(by: timeout)
        while ContinuousClock.now < deadline {
            if firmwareCancel { throw FirmwareUpdate.TransferError.cancelled }
            if let status = popStatus() { return status }
            // The sensor drops a half-done update with the link; no status is coming.
            if firmware == nil { throw FirmwareUpdate.TransferError.linkLost }
            try await Task.sleep(for: .milliseconds(40))
        }
        throw FirmwareUpdate.TransferError.timedOut
    }

    /// A failure that arrived while chunks were still going out.
    private func consumeFailure() -> FirmwareUpdate.Status.Failure? {
        while !pendingStatuses.isEmpty {
            let next = pendingStatuses.removeFirst()
            if next.phase == .failed {
                return next.failure == .none ? .finalizeFailed : next.failure
            }
        }
        return nil
    }

    private func popStatus() -> FirmwareUpdate.Status? {
        while !pendingStatuses.isEmpty {
            let next = pendingStatuses.removeFirst()
            if next.phase != .progress { return next }
        }
        return nil
    }

    private func waitForReboot() async {
        shouldReconnect = true
        var sawDrop = false
        let deadline = ContinuousClock.now.advanced(by: .seconds(45))
        while ContinuousClock.now < deadline {
            switch state {
            case .connecting, .searching, .bluetoothUnavailable:
                sawDrop = true
            case .idle:
                sawDrop = true
                beginScan(autoConnect: true)
            case .connected where sawDrop && info != nil:
                shouldReconnect = false
                firmwareUpdate = FirmwareUpdateProgress(stage: .succeeded(version: info?.fw ?? ""))
                // The reconnect happened while the update still held scans back.
                send(.setInterval(seconds: liveInterval))
                send(.setPeer(name: UIDevice.current.name))
                return
            default:
                break
            }
            try? await Task.sleep(for: .milliseconds(250))
        }
        firmwareUpdate = FirmwareUpdateProgress(
            stage: .failed(String(localized: "The sensor rebooted, but the app could not reconnect. It is looking for the Cardputer again."))
        )
        shouldReconnect = true
        beginScan(autoConnect: true)
    }

    private func currentChunkSize() -> Int {
        guard let sensor else { return 180 }
        let maxWrite = sensor.maximumWriteValueLength(for: .withResponse)
        guard maxWrite > 5 else { return 16 }
        return min(FirmwareUpdate.maxChunkBytes, maxWrite - 5)
    }

    private func writeFirmware(_ data: Data) async throws {
        guard let sensor, let firmware, case .connected = state else {
            throw FirmwareUpdate.TransferError.notConnected
        }
        firmwareWriteGeneration += 1
        let generation = firmwareWriteGeneration
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            firmwareWriteWait = continuation
            sensor.writeValue(data, for: firmware, type: .withResponse)
            Task {
                try? await Task.sleep(for: .seconds(20))
                guard self.firmwareWriteGeneration == generation else { return }
                self.finishFirmwareWrite(FirmwareUpdate.TransferError.timedOut)
            }
        }
    }

    private func finishFirmwareWrite(_ error: Error?) {
        guard let waiter = firmwareWriteWait else { return }
        firmwareWriteWait = nil
        firmwareWriteGeneration += 1
        if let error {
            waiter.resume(throwing: error)
        } else {
            waiter.resume()
        }
    }
}

extension SensorLink: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard demo == nil else { return }
        if central.state != .poweredOn {
            resetConnection()
        }
        updateIdleState()
        if central.state == .poweredOn {
            restoreLastSensor()
        }
        #if targetEnvironment(simulator)
        // There is no Bluetooth in the simulator; show something to look at.
        if central.state == .unsupported {
            setDemo(true)
        }
        #endif
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        peripherals[peripheral.identifier] = peripheral
        if shouldReconnect, case .searching = state {
            connect(to: peripheral.identifier)
        }
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? name(of: peripheral)
        let found = DiscoveredSensor(
            id: peripheral.identifier, name: name, rssi: RSSI.intValue, lastSeen: .now
        )
        if let index = discovered.firstIndex(where: { $0.id == found.id }) {
            discovered[index] = found
        } else {
            discovered.append(found)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        log.notice("connected to \(peripheral.identifier, privacy: .public); write limit \(peripheral.maximumWriteValueLength(for: .withResponse))")
        UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: Keys.lastSensor)
        peripheral.discoverServices([SensorProtocol.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        log.error("connect failed: \(error?.localizedDescription ?? "no error", privacy: .public)")
        let retry = shouldReconnect
        resetConnection()
        if retry, central.state == .poweredOn {
            beginScan(autoConnect: true)
        } else {
            updateIdleState()
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        log.error("disconnected: \(error?.localizedDescription ?? "no error", privacy: .public); update \(String(describing: self.firmwareUpdate?.stage), privacy: .public)")
        guard peripheral == sensor, !userDisconnected else { return }
        // Out of range or rebooted: keep a connection request pending, which
        // iOS completes whenever the sensor comes back. If that request
        // fails, didFailToConnect scans again.
        shouldReconnect = true
        control = nil
        firmware = nil
        info = nil
        loraDevices = []
        endLoraScan()
        endRawCapture()
        capFix = nil
        store.abandonScan()
        finishFirmwareWrite(FirmwareUpdate.TransferError.linkLost)
        state = .connecting(name: name(of: peripheral))
        central.connect(peripheral)
    }
}

extension SensorLink: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        log.notice("services: \(peripheral.services?.map(\.uuid.uuidString).joined(separator: ", ") ?? "none", privacy: .public) \(error?.localizedDescription ?? "", privacy: .public)")
        guard let service = peripheral.services?.first(where: { $0.uuid == SensorProtocol.service }) else {
            disconnect()
            return
        }
        peripheral.discoverCharacteristics(
            [SensorProtocol.info, SensorProtocol.control, SensorProtocol.results, SensorProtocol.firmware],
            for: service
        )
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        log.notice("characteristics: \(service.characteristics?.map(\.uuid.uuidString).joined(separator: ", ") ?? "none", privacy: .public) \(error?.localizedDescription ?? "", privacy: .public)")
        for characteristic in service.characteristics ?? [] {
            switch characteristic.uuid {
            case SensorProtocol.info:
                peripheral.readValue(for: characteristic)
            case SensorProtocol.control:
                control = characteristic
            case SensorProtocol.results:
                peripheral.setNotifyValue(true, for: characteristic)
            case SensorProtocol.firmware:
                firmware = characteristic
            default:
                break
            }
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard characteristic.uuid == SensorProtocol.results, characteristic.isNotifying else { return }
        state = .connected(name: name(of: peripheral))
        // The sensor keeps the last interval, and the phone sets it again on
        // every connect. An update in progress keeps the sensor quiet instead.
        if updateHoldsScans {
            send(.setInterval(seconds: 0))
            return
        }
        send(.setInterval(seconds: liveInterval))
        send(.setPeer(name: UIDevice.current.name))
        pushKnownNetworks()
        if liveInterval == 0 {
            send(.scanOnce)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        switch characteristic.uuid {
        case SensorProtocol.results:
            receive(data)
        case SensorProtocol.info:
            if let decoded = try? JSONDecoder().decode(SensorInfo.self, from: data) {
                info = decoded
                if let bat = decoded.batteryLevel {
                    batteryLevel = bat
                    isCharging = decoded.isCharging ?? false
                }
                if let latitude = decoded.latitude, let longitude = decoded.longitude {
                    capFix = CapFix(latitude: latitude, longitude: longitude, satellites: decoded.satellites ?? 0)
                }
            }
        default:
            break
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error {
            log.error("write to \(characteristic.uuid.uuidString, privacy: .public) failed: \(error.localizedDescription, privacy: .public)")
        }
        guard characteristic.uuid == SensorProtocol.firmware else { return }
        finishFirmwareWrite(error)
    }

    /// The sensor's services changed under a cached copy, as after flashing
    /// firmware with new characteristics. Look again instead of using the old list.
    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        log.notice("services changed: \(invalidatedServices.map(\.uuid.uuidString).joined(separator: ", "), privacy: .public)")
        guard invalidatedServices.contains(where: { $0.uuid == SensorProtocol.service }) else { return }
        control = nil
        firmware = nil
        peripheral.discoverServices([SensorProtocol.service])
    }
}

/// Every network a linked sensor has joined and handed over. The sensor only
/// keeps a short unsent buffer; this is the list it draws from.
private struct KnownNetworks {
    struct Item {
        var ssid: String
        var password: String
    }

    private let key = "spectra-known-wifi"

    func all() -> [Item] {
        guard let text = UserDefaults.standard.string(forKey: key),
              let data = text.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: String]] else {
            return []
        }
        return array.compactMap { row in
            guard let ssid = row["ssid"], !ssid.isEmpty else { return nil }
            return Item(ssid: ssid, password: row["pass"] ?? "")
        }
    }

    func remember(ssid: String, password: String) {
        guard !ssid.isEmpty else { return }
        var rows = all().filter { $0.ssid != ssid }
        rows.insert(Item(ssid: ssid, password: password), at: 0)
        let array = rows.map { ["ssid": $0.ssid, "pass": $0.password] }
        guard let data = try? JSONSerialization.data(withJSONObject: array),
              let text = String(data: data, encoding: .utf8) else { return }
        UserDefaults.standard.set(text, forKey: key)
    }
}
