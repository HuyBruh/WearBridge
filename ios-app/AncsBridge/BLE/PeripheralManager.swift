import HealthKit
import EventKit
import UserNotifications
import CoreBluetooth
import Combine
import UIKit
import os.log

/// Manages BLE for the WearBridge companion app.
///
/// Acts as both:
/// - CBPeripheralManager: advertises for initial watch pairing
/// - CBCentralManager: detects connected watches via system BLE connections
///
/// ANCS itself is system-managed — this class provides discoverability and
/// connection status tracking.
class PeripheralManager: NSObject, ObservableObject {

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "PeripheralManager")

    private var peripheralManager: CBPeripheralManager!
    private var centralManager: CBCentralManager!

    @Published var isAdvertising = false
    @Published var bluetoothState: CBManagerState = .unknown
    @Published var discoveredWatches: [DiscoveredWatch] = []

    let settingsStore = SettingsStore()

    private var companionService: CBMutableService?
    private var statusCharacteristic: CBMutableCharacteristic?
    private var callStateCharacteristic: CBMutableCharacteristic?
    private var readSnapshots: [UUID: Data] = [:]
    /// Health records being reassembled, per connected watch.
    private var healthBuffers: [UUID: Data] = [:]
    private var replySnapshots: [UUID: Data] = [:]
    private var infoSnapshots: [UUID: Data] = [:]
    private var infoCharacteristic: CBMutableCharacteristic?
    private var serviceAdded = false
    private var scanTimer: Timer?

    /// Monitors phone call state via CallKit for live call status sync
    let callStateObserver = CallStateObserver()

    /// Standard BLE service UUIDs to probe for connected devices.
    /// Wear OS watches expose these standard GATT services.
    private static let probeServiceUUIDs: [CBUUID] = [
        CBUUID(string: "180A"),  // Device Information
        CBUUID(string: "180F"),  // Battery Service
        CBUUID(string: "1800"),  // Generic Access
        CBUUID(string: "1805"),  // Current Time Service
    ]

    /// Known Wear OS watch name patterns
    private static let watchNamePatterns = [
        "pixel watch", "galaxy watch", "ticwatch", "fossil",
        "oppo watch", "oneplus watch", "mobvoi", "wear os",
        "ancs"  // Our own app's advertised name during pairing
    ]

    override init() {
        super.init()
        peripheralManager = CBPeripheralManager(
            delegate: self,
            queue: nil,
            options: [
                CBPeripheralManagerOptionRestoreIdentifierKey: BLEConstants.restoreIdentifier
            ]
        )
        centralManager = CBCentralManager(
            delegate: self,
            queue: nil,
            options: [
                CBCentralManagerOptionShowPowerAlertKey: false
            ]
        )

        // Wire up call state observer to push BLE notifications
        callStateObserver.onCallStateChanged = { [weak self] state in
            self?.notifyCallState(state)
        }
        // Start/stop based on setting
        updateCallStateObserving()
    }

    /// Start or stop call state observing based on the current setting.
    func updateCallStateObserving() {
        if settingsStore.liveCallStatus {
            callStateObserver.startObserving()
        } else {
            callStateObserver.stopObserving()
        }
    }

    /// Push call state to all subscribed watches via BLE notification.
    private func notifyCallState(_ state: UInt8) {
        guard let char = callStateCharacteristic else { return }
        let data = Data([state])
        let sent = peripheralManager.updateValue(data, for: char, onSubscribedCentrals: nil)
        let stateStr: String
        switch state {
        case BLEConstants.callStateRinging: stateStr = "ringing"
        case BLEConstants.callStateActive: stateStr = "active"
        case BLEConstants.callStateEnded: stateStr = "ended"
        default: stateStr = "idle"
        }
        logger.info("Call state BLE notify: \(stateStr), sent=\(sent)")
    }

    // MARK: - Connected Watch Discovery

    /// Query the system for BLE peripherals currently connected that look like watches.
    /// Merges with persisted watches so offline ones still appear in the list.
    func refreshConnectedWatches() {
        guard centralManager.state == .poweredOn else { return }

        var found: [UUID: CBPeripheral] = [:]

        // Retrieve peripherals connected to the system with known services
        for serviceUUID in Self.probeServiceUUIDs {
            let peripherals = centralManager.retrieveConnectedPeripherals(withServices: [serviceUUID])
            for peripheral in peripherals {
                found[peripheral.identifier] = peripheral
            }
        }

        // Filter to likely watches by name
        var connectedWatchIds = Set<String>()
        var result: [DiscoveredWatch] = []

        for peripheral in found.values {
            guard let name = peripheral.name, !name.isEmpty else { continue }
            let lower = name.lowercased()
            let isWatch = Self.watchNamePatterns.contains { lower.contains($0) }
            let savedWatches = loadPersistedWatches()
            let wasPreviouslySeen = savedWatches.contains { $0.id == peripheral.identifier.uuidString }

            guard isWatch || wasPreviouslySeen else { continue }

            // Persist this watch
            savePersistedWatch(PersistedWatch(id: peripheral.identifier.uuidString, name: name))
            connectedWatchIds.insert(peripheral.identifier.uuidString)

            result.append(DiscoveredWatch(
                id: peripheral.identifier,
                name: name,
                isConnected: true,
                peripheral: peripheral
            ))
        }

        // Add offline (persisted but not currently connected) watches
        for saved in loadPersistedWatches() {
            if !connectedWatchIds.contains(saved.id), let uuid = UUID(uuidString: saved.id) {
                result.append(DiscoveredWatch(
                    id: uuid,
                    name: saved.name,
                    isConnected: false,
                    peripheral: nil
                ))
            }
        }

        DispatchQueue.main.async {
            // Connected watches first, then offline
            self.discoveredWatches = result.sorted { $0.isConnected && !$1.isConnected }
            let connected = result.filter { $0.isConnected }.count
            self.logger.info("Watches: \(connected) connected, \(result.count - connected) offline")
        }
    }

    // MARK: - Watch Persistence

    private static let persistedWatchesKey = "persistedWatches"

    private func loadPersistedWatches() -> [PersistedWatch] {
        guard let data = UserDefaults.standard.data(forKey: Self.persistedWatchesKey),
              let watches = try? JSONDecoder().decode([PersistedWatch].self, from: data) else {
            return []
        }
        return watches
    }

    private func savePersistedWatch(_ watch: PersistedWatch) {
        var watches = loadPersistedWatches()
        if let index = watches.firstIndex(where: { $0.id == watch.id }) {
            watches[index] = watch
        } else {
            watches.append(watch)
        }
        if let data = try? JSONEncoder().encode(watches) {
            UserDefaults.standard.set(data, forKey: Self.persistedWatchesKey)
        }
    }

    /// Start periodic scanning for connected watches
    func startWatchRefresh() {
        refreshConnectedWatches()
        scanTimer?.invalidate()
        scanTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            self?.refreshConnectedWatches()
        }
    }

    func stopWatchRefresh() {
        scanTimer?.invalidate()
        scanTimer = nil
    }

    // MARK: - Advertising

    func startAdvertising() {
        guard peripheralManager.state == .poweredOn else {
            logger.warning("Cannot advertise: Bluetooth not powered on")
            return
        }

        if peripheralManager.isAdvertising {
            isAdvertising = true
            return
        }

        peripheralManager.startAdvertising([
            CBAdvertisementDataLocalNameKey: BLEConstants.advertisingLocalName,
            CBAdvertisementDataServiceUUIDsKey: [BLEConstants.companionServiceUUID]
        ])

        logger.info("Started advertising as '\(BLEConstants.advertisingLocalName)'")
    }

    func stopAdvertising() {
        peripheralManager.stopAdvertising()
        isAdvertising = false
        logger.info("Stopped advertising")
    }

    private func setupServices() {
        guard !serviceAdded else { return }

        statusCharacteristic = CBMutableCharacteristic(
            type: BLEConstants.connectionStatusUUID,
            properties: [.read, .notify],
            value: nil,
            permissions: [.readable]
        )

        let quickReplyCharacteristic = CBMutableCharacteristic(
            type: BLEConstants.quickReplyCharacteristicUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )

        UIDevice.current.isBatteryMonitoringEnabled = true
        let replies = CBMutableCharacteristic(type: BLEConstants.repliesUUID, properties: [.read],
            value: nil, permissions: [.readEncryptionRequired])
        let info = CBMutableCharacteristic(type: BLEConstants.infoUUID, properties: [.read, .write, .notify],
            value: nil, permissions: [.readEncryptionRequired, .writeEncryptionRequired])
        infoCharacteristic = info
        WatchInfoProvider.shared.onChange = { [weak self] in self?.notifyInfoChanged() }
        let health = CBMutableCharacteristic(type: BLEConstants.healthUUID, properties: [.write],
            value: nil, permissions: [.writeEncryptionRequired])
        let tools = CBMutableCharacteristic(type: BLEConstants.toolsUUID, properties: [.read, .write],
                                            value: nil, permissions: [.readEncryptionRequired, .writeEncryptionRequired])
        let draft = CBMutableCharacteristic(type: BLEConstants.draftUUID, properties: [.write],
                                            value: nil, permissions: [.writeEncryptionRequired])
        callStateCharacteristic = CBMutableCharacteristic(
            type: BLEConstants.callStateCharacteristicUUID,
            properties: [.read, .notify],
            value: nil,
            permissions: [.readable]
        )

        companionService = CBMutableService(
            type: BLEConstants.companionServiceUUID,
            primary: true
        )
        companionService?.characteristics = [statusCharacteristic!, quickReplyCharacteristic, callStateCharacteristic!, draft, tools, health, replies, info]

        peripheralManager.add(companionService!)
        serviceAdded = true
        logger.info("Companion service added")
    }
}

// MARK: - CBPeripheralManagerDelegate
extension PeripheralManager: CBPeripheralManagerDelegate {

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        bluetoothState = peripheral.state
        logger.info("Peripheral Bluetooth state: \(peripheral.state.rawValue)")

        switch peripheral.state {
        case .poweredOn:
            setupServices()
            if settingsStore.autoAdvertise {
                startAdvertising()
            }
        case .poweredOff:
            isAdvertising = false
            discoveredWatches = []
        default:
            break
        }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            logger.error("Failed to start advertising: \(error.localizedDescription)")
            isAdvertising = false
        } else {
            isAdvertising = true
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        logger.info("Central subscribed: \(central.identifier)")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        logger.info("Central unsubscribed: \(central.identifier)")
    }

    private static func phoneSummary() -> Data {
        let battery = UIDevice.current.batteryLevel
        var lines = [battery < 0 ? "Battery unavailable" : "iPhone battery: \(Int((battery * 100).rounded()))%"]
        if UserDefaults.standard.bool(forKey: "shareAgenda"),
           EKEventStore.authorizationStatus(for: .event) == .fullAccess {
            let store = EKEventStore()
            let now = Date()
            let end = now.addingTimeInterval(86400)
            let predicate = store.predicateForEvents(withStart: now, end: end, calendars: nil)
            let events = store.events(matching: predicate).sorted { $0.startDate < $1.startDate }
            if events.isEmpty { lines.append("No events in the next 24 hours") }
            for event in events.prefix(3) {
                let time = event.isAllDay ? "All day" : event.startDate.formatted(date: .abbreviated, time: .shortened)
                lines.append("\(time) · \(String((event.title ?? "Event").prefix(32)))")
            }
        }
        var summary = lines.joined(separator: "\n")
        while summary.utf8.count > 500 { summary.removeLast() }
        return Data(summary.utf8)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        if request.characteristic.uuid == BLEConstants.toolsUUID {
            let key = request.central.identifier
            if request.offset == 0 { readSnapshots = [key: Self.phoneSummary()] }
            guard let data = readSnapshots[key], request.offset <= data.count else {
                peripheral.respond(to: request, withResult: .invalidOffset)
                return
            }
            request.value = Data(data.dropFirst(request.offset))
            peripheral.respond(to: request, withResult: .success)
        } else if request.characteristic.uuid == BLEConstants.infoUUID {
            let key = request.central.identifier
            if request.offset == 0 { infoSnapshots[key] = WatchInfoProvider.shared.payload() }
            guard let data = infoSnapshots[key], request.offset <= data.count else {
                peripheral.respond(to: request, withResult: .invalidOffset)
                return
            }
            request.value = Data(data.dropFirst(request.offset))
            peripheral.respond(to: request, withResult: .success)
        } else if request.characteristic.uuid == BLEConstants.repliesUUID {
            let key = request.central.identifier
            if request.offset == 0 { replySnapshots[key] = QuickReplyStore.watchPayload() }
            guard let data = replySnapshots[key], request.offset <= data.count else {
                peripheral.respond(to: request, withResult: .invalidOffset)
                return
            }
            request.value = Data(data.dropFirst(request.offset))
            peripheral.respond(to: request, withResult: .success)
        } else if request.characteristic.uuid == BLEConstants.connectionStatusUUID {
            request.value = Data([0x01])
            peripheral.respond(to: request, withResult: .success)
        } else if request.characteristic.uuid == BLEConstants.callStateCharacteristicUUID {
            request.value = Data([callStateObserver.currentState])
            peripheral.respond(to: request, withResult: .success)
        } else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let first = requests.first else { return }
        if first.characteristic.uuid == BLEConstants.infoUUID {
            guard requests.count == 1, first.offset == 0, let data = first.value,
                  let command = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let id = command["complete"] as? String else {
                peripheral.respond(to: first, withResult: .invalidAttributeValueLength)
                return
            }
            let done = WatchInfoProvider.shared.completeReminder(id: id)
            peripheral.respond(to: first, withResult: done ? .success : .unlikelyError)
            return
        }
        if first.characteristic.uuid == BLEConstants.healthUUID {
            handleHealthChunk(first, peripheral: peripheral, count: requests.count)
            return
        }
        if first.characteristic.uuid == BLEConstants.toolsUUID {
            guard requests.count == 1, first.offset == 0, let data = first.value, data.count == 1 else {
                peripheral.respond(to: first, withResult: .invalidAttributeValueLength)
                return
            }
            if data.first == 1, UserDefaults.standard.bool(forKey: "allowPing") {
                let content = UNMutableNotificationContent()
                content.title = "Ping from your Pixel Watch"
                content.sound = .default
                UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "watch-ping", content: content, trigger: nil)) { error in
                    DispatchQueue.main.async { peripheral.respond(to: first, withResult: error == nil ? .success : .unlikelyError) }
                }
            } else if data.first == 2 {
                DispatchQueue.main.async {
                    guard UIApplication.shared.applicationState == .active, let camera = WatchCamera.active,
                          camera.viewIfLoaded?.window != nil else {
                        peripheral.respond(to: first, withResult: .requestNotSupported)
                        return
                    }
                    WatchCamera.active = nil
                    camera.takePicture()
                    peripheral.respond(to: first, withResult: .success)
                }
            } else { peripheral.respond(to: first, withResult: .requestNotSupported) }
            return
        }
        if first.characteristic.uuid == BLEConstants.draftUUID {
            guard requests.count == 1, first.offset == 0, let bytes = first.value,
                  bytes.count <= 512, let draft = try? JSONDecoder().decode(WatchDraft.self, from: bytes),
                  !draft.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                peripheral.respond(to: first, withResult: .invalidAttributeValueLength)
                return
            }
            let saved = DraftInbox.shared.save(draft)
            peripheral.respond(to: first, withResult: saved ? .success : .unlikelyError)
            if saved {
                // One tap from the lock screen opens the composer with the reply filled in.
                let content = UNMutableNotificationContent()
                content.title = draft.sender.isEmpty ? "Reply from your watch" : "Reply to \(draft.sender)"
                content.body = draft.body
                content.sound = .default
                content.userInfo = ["draftID": draft.id.uuidString]
                UNUserNotificationCenter.current().add(UNNotificationRequest(
                    identifier: "draft-\(draft.id.uuidString)", content: content, trigger: nil))
            }
            return
        }
        for request in requests {
            if request.characteristic.uuid == BLEConstants.quickReplyCharacteristicUUID,
               let data = request.value,
               let payload = String(data: data, encoding: .utf8) {
                logger.info("Quick reply received")
                handleQuickReply(payload)
            }
            peripheral.respond(to: request, withResult: .success)
        }
    }

    /// Reassembles a health record sent as acknowledged chunks; the last chunk is
    /// acknowledged only once the record is stored, so the watch can then delete it.
    private func handleHealthChunk(_ request: CBATTRequest, peripheral: CBPeripheralManager, count: Int) {
        guard count == 1, request.offset == 0, let chunk = request.value, let flags = chunk.first else {
            peripheral.respond(to: request, withResult: .invalidAttributeValueLength)
            return
        }
        let key = request.central.identifier
        if flags & 0x01 != 0 {
            // Refuse early (before the whole record is sent) when Health sync is off.
            guard WatchHealthImporter.isEnabled else {
                healthBuffers[key] = nil
                peripheral.respond(to: request, withResult: .requestNotSupported)
                return
            }
            healthBuffers[key] = Data()
        }
        guard var buffer = healthBuffers[key] else {
            peripheral.respond(to: request, withResult: .unlikelyError)
            return
        }
        buffer.append(chunk.dropFirst())
        guard buffer.count <= 512 * 1024 else {
            healthBuffers[key] = nil
            peripheral.respond(to: request, withResult: .insufficientResources)
            return
        }
        if flags & 0x02 != 0 {
            healthBuffers[key] = nil
            let stored = WatchHealthImporter.shared.receive(buffer)
            peripheral.respond(to: request, withResult: stored ? .success : .unlikelyError)
        } else {
            healthBuffers[key] = buffer
            peripheral.respond(to: request, withResult: .success)
        }
    }

    /// One byte tells a subscribed watch to re-read the info characteristic.
    private func notifyInfoChanged() {
        guard let info = infoCharacteristic else { return }
        _ = peripheralManager.updateValue(Data([0x01]), for: info, onSubscribedCentrals: nil)
    }

    private func handleQuickReply(_ payload: String) {
        DispatchQueue.main.async {
            QuickReplyLauncher.shared.deliver(payload)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        logger.info("Restoring peripheral manager state")
        if let services = dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService] {
            if let service = services.first(where: { $0.uuid == BLEConstants.companionServiceUUID }),
               let characteristics = service.characteristics,
               characteristics.contains(where: { $0.uuid == BLEConstants.healthUUID }),
               characteristics.contains(where: { $0.uuid == BLEConstants.repliesUUID }),
               characteristics.contains(where: { $0.uuid == BLEConstants.infoUUID }),
               characteristics.contains(where: { $0.uuid == BLEConstants.toolsUUID }),
               characteristics.contains(where: { $0.uuid == BLEConstants.draftUUID }) {
                companionService = service
                statusCharacteristic = characteristics.first { $0.uuid == BLEConstants.connectionStatusUUID } as? CBMutableCharacteristic
                callStateCharacteristic = characteristics.first { $0.uuid == BLEConstants.callStateCharacteristicUUID } as? CBMutableCharacteristic
                infoCharacteristic = characteristics.first { $0.uuid == BLEConstants.infoUUID } as? CBMutableCharacteristic
                WatchInfoProvider.shared.onChange = { [weak self] in self?.notifyInfoChanged() }
                UIDevice.current.isBatteryMonitoringEnabled = true
                serviceAdded = true
            } else {
                services.forEach { peripheral.remove($0) }
                serviceAdded = false
            }
        }
        if peripheral.isAdvertising {
            isAdvertising = true
        }
    }
}

// MARK: - CBCentralManagerDelegate
extension PeripheralManager: CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        logger.info("Central Bluetooth state: \(central.state.rawValue)")

        if central.state == .poweredOn {
            startWatchRefresh()
        } else {
            stopWatchRefresh()
            DispatchQueue.main.async {
                self.discoveredWatches = []
            }
        }
    }
}

// MARK: - DiscoveredWatch Model
struct DiscoveredWatch: Identifiable {
    let id: UUID
    let name: String
    let isConnected: Bool
    let peripheral: CBPeripheral?
}

// MARK: - Persisted Watch (for offline display)
struct PersistedWatch: Codable {
    let id: String // UUID string
    let name: String
}
