import CoreBluetooth
import Foundation
import Observation

/// Listens for every Bluetooth LE advertisement the iPhone can hear.
///
/// The sensor link already owns a central, filtered to the Cardputer's
/// service. This is a second central so that scan and that connection do
/// not have to share one session. An unfiltered scan only delivers while
/// the app is in the foreground, so it is suspended as soon as the scene
/// backgrounds.
@Observable
final class BLEScanner: NSObject {
    enum Availability: Equatable {
        case starting, poweredOff, unauthorized, unsupported
    }

    enum State: Equatable {
        case unavailable(Availability)
        case idle
        case scanning
    }

    private(set) var state: State = .unavailable(.starting)
    private(set) var devices: [UUID: BLEDevice] = [:]
    /// Bumped once a second so rows can dim devices that stopped advertising.
    private(set) var now = Date.now

    // MARK: GATT connection

    enum GATTState: Equatable {
        case idle
        case connecting
        case discovering
        case connected
        case failed(String)
    }

    private(set) var gattState: GATTState = .idle
    private(set) var gattServices: [GATTService] = []
    /// The device the GATT section is bound to, so a stale connection to an
    /// earlier device does not spill into another device's detail view.
    private(set) var gattDeviceID: UUID?

    var isScanning: Bool { state == .scanning }

    var nearbyCount: Int {
        devices.values.filter { $0.isNearby(at: now) }.count
    }

    private var central: CBCentralManager?
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var wantsScan = false
    /// Set when the user presses Stop, so a later appearance of the list
    /// (opening a device, for instance) does not turn the radio back on.
    private var pausedByUser = false
    private var ticker: Timer?

    func start() {
        pausedByUser = false
        wantsScan = true
        ensureCentral()
        beginScanIfPossible()
        ensureClock()
    }

    func stop() {
        pausedByUser = true
        wantsScan = false
        central?.stopScan()
        if isScanning { state = .idle }
    }

    /// Starts a scan the user has not paused. Appearing again after Stop does nothing.
    func resumeUnlessPaused() {
        guard !pausedByUser else { return }
        start()
    }

    /// The Bluetooth tab went away. Opening a device does not come through
    /// here, so Stop stays stopped and a running scan keeps running in the detail.
    func leave() {
        central?.stopScan()
        wantsScan = false
        if isScanning { state = .idle }
    }

    /// Drops the radio without forgetting that the user wants it back when
    /// the app is foregrounded again.
    func suspend() {
        central?.stopScan()
        if isScanning { state = .idle }
    }

    func resumeIfWanted() {
        guard wantsScan else { return }
        beginScanIfPossible()
    }

    func clear() {
        devices = [:]
    }

    /// Connects to the peripheral and reads its services and characteristics.
    func connectGATT(_ id: UUID) {
        ensureCentral()
        guard let central, let peripheral = peripherals[id] else {
            gattState = .failed(String(localized: "Device is no longer in range."))
            return
        }
        cancelConnection()
        gattDeviceID = id
        gattServices = []
        gattState = .connecting
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
    }

    func disconnectGATT() {
        cancelConnection()
        gattState = .idle
        gattServices = []
        gattDeviceID = nil
    }

    private func cancelConnection() {
        if let id = gattDeviceID, let peripheral = peripherals[id] {
            central?.cancelPeripheralConnection(peripheral)
        }
    }

    private func upsertService(_ service: CBService) {
        let uuid = service.uuid.uuidString
        if !gattServices.contains(where: { $0.uuid == uuid }) {
            gattServices.append(
                GATTService(
                    uuid: BLENumbers.displayUUID(uuid),
                    name: BLENumbers.serviceName(uuid: uuid),
                    characteristics: []
                )
            )
        }
    }

    private func ensureCentral() {
        guard central == nil else { return }
        central = CBCentralManager(
            delegate: self,
            queue: .main,
            options: [CBCentralManagerOptionShowPowerAlertKey: false]
        )
    }

    private func beginScanIfPossible() {
        guard wantsScan, let central, central.state == .poweredOn else { return }
        if !central.isScanning {
            central.scanForPeripherals(
                withServices: nil,
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
            )
        }
        state = .scanning
    }

    private func ensureClock() {
        guard ticker == nil else { return }
        ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.now = .now
            }
        }
    }

    private func remember(
        _ peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi: Int
    ) {
        let fields = BLEFields(
            localName: advertisementData[CBAdvertisementDataLocalNameKey] as? String,
            manufacturerData: advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
            serviceUUIDs: uuids(advertisementData[CBAdvertisementDataServiceUUIDsKey]),
            overflowServiceUUIDs: uuids(advertisementData[CBAdvertisementDataOverflowServiceUUIDsKey]),
            solicitedServiceUUIDs: uuids(advertisementData[CBAdvertisementDataSolicitedServiceUUIDsKey]),
            serviceData: serviceData(advertisementData[CBAdvertisementDataServiceDataKey]),
            txPower: (advertisementData[CBAdvertisementDataTxPowerLevelKey] as? NSNumber)?.intValue,
            isConnectable: (advertisementData[CBAdvertisementDataIsConnectable] as? NSNumber)?.boolValue
        )
        let id = peripheral.identifier
        peripherals[id] = peripheral
        let seen = Date.now
        let device = devices[id] ?? BLEDevice(id: id, rssi: rssi == 127 ? -100 : rssi, seen: seen)
        device.absorb(BLEAdvertisement.parse(fields), peripheralName: peripheral.name, rssi: rssi, at: seen)
        devices[id] = device
    }

    private func uuids(_ value: Any?) -> [String] {
        (value as? [CBUUID])?.map(\.uuidString) ?? []
    }

    private func serviceData(_ value: Any?) -> [String: Data] {
        guard let raw = value as? [CBUUID: Data] else { return [:] }
        var result: [String: Data] = [:]
        for (uuid, data) in raw {
            result[uuid.uuidString] = data
        }
        return result
    }

    private func noteState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            if wantsScan {
                beginScanIfPossible()
            } else if case .unavailable = state {
                state = .idle
            }
        case .poweredOff:
            state = .unavailable(.poweredOff)
        case .unauthorized:
            state = .unavailable(.unauthorized)
        case .unsupported:
            state = .unavailable(.unsupported)
        case .resetting, .unknown:
            state = .unavailable(.starting)
        @unknown default:
            state = .unavailable(.starting)
        }
    }
}

extension BLEScanner: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        noteState(central)
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        remember(peripheral, advertisementData: advertisementData, rssi: RSSI.intValue)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral.identifier == gattDeviceID else { return }
        gattState = .discovering
        peripheral.discoverServices(nil)
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        guard peripheral.identifier == gattDeviceID else { return }
        gattState = .failed(error?.localizedDescription ?? String(localized: "Could not connect."))
        gattDeviceID = nil
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        guard peripheral.identifier == gattDeviceID else { return }
        // A disconnect the user did not ask for is a failure; an intentional one
        // has already reset the state in disconnectGATT.
        if case .idle = gattState {
            return
        }
        if let error {
            gattState = .failed(error.localizedDescription)
        } else if case .connected = gattState {
            gattState = .idle
        }
        gattDeviceID = nil
    }
}

extension BLEScanner: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral.identifier == gattDeviceID else { return }
        if let error {
            gattState = .failed(error.localizedDescription)
            return
        }
        for service in peripheral.services ?? [] {
            upsertService(service)
            peripheral.discoverCharacteristics(nil, for: service)
        }
        gattState = .connected
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard peripheral.identifier == gattDeviceID, error == nil else { return }
        let serviceKey = BLENumbers.displayUUID(service.uuid.uuidString)
        guard let index = gattServices.firstIndex(where: { $0.uuid == serviceKey }) else { return }
        gattServices[index].characteristics = (service.characteristics ?? []).map { characteristic in
            GATTCharacteristic(
                uuid: BLENumbers.displayUUID(characteristic.uuid.uuidString),
                properties: Self.propertyLabels(characteristic.properties),
                value: characteristic.value
            )
        }
        for characteristic in service.characteristics ?? []
        where characteristic.properties.contains(.read) {
            peripheral.readValue(for: characteristic)
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard peripheral.identifier == gattDeviceID, error == nil,
              let service = characteristic.service else { return }
        let serviceKey = BLENumbers.displayUUID(service.uuid.uuidString)
        let charKey = BLENumbers.displayUUID(characteristic.uuid.uuidString)
        guard let si = gattServices.firstIndex(where: { $0.uuid == serviceKey }),
              let ci = gattServices[si].characteristics.firstIndex(where: { $0.uuid == charKey })
        else { return }
        gattServices[si].characteristics[ci].value = characteristic.value
    }

    private static func propertyLabels(_ properties: CBCharacteristicProperties) -> String {
        var parts: [String] = []
        if properties.contains(.read) { parts.append("Read") }
        if properties.contains(.write) { parts.append("Write") }
        if properties.contains(.writeWithoutResponse) { parts.append("WriteNR") }
        if properties.contains(.notify) { parts.append("Notify") }
        if properties.contains(.indicate) { parts.append("Indicate") }
        return parts.joined(separator: " · ")
    }
}

struct GATTCharacteristic: Identifiable, Equatable {
    let uuid: String
    let properties: String
    var value: Data?
    var id: String { uuid }
}

struct GATTService: Identifiable, Equatable {
    let uuid: String
    let name: String?
    var characteristics: [GATTCharacteristic]
    var id: String { uuid }
}
