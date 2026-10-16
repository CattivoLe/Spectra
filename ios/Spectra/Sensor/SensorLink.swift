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
    private var userDisconnected = false
    private var demo: DemoSensor?

    private enum Keys {
        static let lastSensor = "lastSensorIdentifier"
        static let liveInterval = "liveInterval"
    }

    static let liveIntervals = [0, 3, 5, 10, 30]

    init(store: NetworkStore) {
        self.store = store
        let saved = UserDefaults.standard.object(forKey: Keys.liveInterval) as? Int
        liveInterval = saved ?? 5
        super.init()
        central = CBCentralManager(delegate: self, queue: nil)
    }

    // MARK: Actions

    func startSearching() {
        guard central.state == .poweredOn, sensor == nil else { return }
        discovered = []
        state = .searching
        central.scanForPeripherals(
            withServices: [SensorProtocol.service],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
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

    func setDemo(_ on: Bool) {
        if on {
            if sensor != nil { disconnect() }
            central.stopScan()
            let demo = DemoSensor { [weak self] data in self?.receive(data) }
            demo.interval = liveInterval
            self.demo = demo
            info = SensorInfo(proto: SensorProtocol.version, fw: "demo", chip: "Simulated", bands: ["2.4"], interval: liveInterval)
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
        if let message = SensorProtocol.decode(data) {
            store.handle(message)
        }
    }

    private func name(of peripheral: CBPeripheral) -> String {
        peripheral.name ?? String(localized: "Spectra sensor")
    }

    private func resetConnection() {
        sensor = nil
        control = nil
        info = nil
        store.abandonScan()
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
    private func restoreLastSensor() {
        guard sensor == nil,
              let saved = UserDefaults.standard.string(forKey: Keys.lastSensor),
              let id = UUID(uuidString: saved),
              let peripheral = central.retrievePeripherals(withIdentifiers: [id]).first
        else { return }
        peripherals[id] = peripheral
        connect(to: id)
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
        resetConnection()
        updateIdleState()
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        guard peripheral == sensor, !userDisconnected else { return }
        // Out of range or rebooted: keep a connection request pending, which
        // iOS completes whenever the sensor comes back.
        control = nil
        info = nil
        store.abandonScan()
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
            [SensorProtocol.info, SensorProtocol.control, SensorProtocol.results], for: service
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
            info = try? JSONDecoder().decode(SensorInfo.self, from: data)
        default:
            break
        }
    }
}
