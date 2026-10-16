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

    var isScanning: Bool { state == .scanning }

    var nearbyCount: Int {
        devices.values.filter { $0.isNearby(at: now) }.count
    }

    private var central: CBCentralManager?
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
}
