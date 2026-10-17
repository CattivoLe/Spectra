import CoreBluetooth
import Foundation
import Observation

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
    /// Set while an application image is being sent to the sensor.
    private(set) var firmwareUpdate: FirmwareUpdateProgress?

    /// Seconds between automatic scans while connected; 0 means scan only on
    /// request. Remembered across launches.
    var liveInterval: Int {
        didSet {
            UserDefaults.standard.set(liveInterval, forKey: Keys.liveInterval)
            send(.setInterval(seconds: liveInterval))
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

    init(store: NetworkStore) {
        self.store = store
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

    func scanNow() {
        if let demo {
            demo.scan()
        } else {
            send(.scanOnce)
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
                    self.firmwareUpdate = FirmwareUpdateProgress(stage: .failed(FirmwareUpdate.TransferError.notConnected.message))
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
            store.abandonScan()
            updateIdleState()
        }
    }

    // MARK: Plumbing

    private func send(_ command: SensorProtocol.Command) {
        guard let sensor, let control, case .connected = state else { return }
        sensor.writeValue(command.data, for: control, type: .withResponse)
    }

    private func receive(_ data: Data) {
        if let status = FirmwareUpdate.Status.decode(data) {
            pendingStatuses.append(status)
            return
        }
        if let message = SensorProtocol.decode(data) {
            if case .battery(_, let level, let charging) = message {
                batteryLevel = level
                isCharging = charging
            }
            store.handle(message)
        }
    }

    private func name(of peripheral: CBPeripheral) -> String {
        peripheral.name ?? String(localized: "Spectra sensor")
    }

    private func resetConnection() {
        sensor = nil
        control = nil
        firmware = nil
        info = nil
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

    private func runFirmwareUpdate(_ image: Data) async {
        let resumeInterval = liveInterval
        send(.setInterval(seconds: 0))
        await waitUntilNotScanning()
        guard !firmwareCancel else {
            firmwareUpdate = FirmwareUpdateProgress(stage: .failed(FirmwareUpdate.TransferError.cancelled.message))
            send(.setInterval(seconds: resumeInterval))
            return
        }

        var upload = FirmwareUpload(image: image, chunkSize: currentChunkSize())
        var packet: Data? = upload.begin()
        do {
            while let current = packet {
                if firmwareCancel { throw FirmwareUpdate.TransferError.cancelled }
                try await writeFirmware(current)
                switch upload.phase {
                case .waitReady, .waitDone:
                    let status = try await waitForStatus(timeout: .seconds(20))
                    packet = upload.handle(status)
                    if upload.phase == .sending {
                        firmwareUpdate = FirmwareUpdateProgress(stage: .uploading(sent: 0, total: image.count))
                    }
                case .sending:
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
                    return
                }
                if case .failed(let failure) = upload.phase {
                    firmwareUpdate = FirmwareUpdateProgress(stage: .failed(failure.message))
                    send(.setInterval(seconds: resumeInterval))
                    return
                }
            }
            firmwareUpdate = FirmwareUpdateProgress(stage: .failed(FirmwareUpdate.TransferError.linkLost.message))
            send(.setInterval(seconds: resumeInterval))
        } catch let error as FirmwareUpdate.TransferError {
            if case .cancelled = error, case .connected = state {
                try? await writeFirmware(upload.abortCommand)
            }
            firmwareUpdate = FirmwareUpdateProgress(stage: .failed(error.message))
            send(.setInterval(seconds: resumeInterval))
        } catch {
            if case .connected = state {
                try? await writeFirmware(upload.abortCommand)
            }
            firmwareUpdate = FirmwareUpdateProgress(stage: .failed(FirmwareUpdate.TransferError.linkLost.message))
            send(.setInterval(seconds: resumeInterval))
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
            try await Task.sleep(for: .milliseconds(40))
        }
        throw FirmwareUpdate.TransferError.timedOut
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
        UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: Keys.lastSensor)
        peripheral.discoverServices([SensorProtocol.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
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
        guard peripheral == sensor, !userDisconnected else { return }
        // Out of range or rebooted: keep a connection request pending, which
        // iOS completes whenever the sensor comes back. If that request
        // fails, didFailToConnect scans again.
        shouldReconnect = true
        control = nil
        firmware = nil
        info = nil
        store.abandonScan()
        finishFirmwareWrite(FirmwareUpdate.TransferError.linkLost)
        state = .connecting(name: name(of: peripheral))
        central.connect(peripheral)
    }
}

extension SensorLink: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
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
        // The firmware forgets the interval on disconnect, so set it every time.
        send(.setInterval(seconds: liveInterval))
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
            }
        default:
            break
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == SensorProtocol.firmware else { return }
        finishFirmwareWrite(error)
    }
}
