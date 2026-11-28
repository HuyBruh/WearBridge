import Foundation
import HealthKit
import CoreLocation
import UIKit
import os

/// Workouts, heart rate and steps recorded on the watch.
///
/// Records arrive over Bluetooth, are written to disk first (so the watch can delete its
/// copy), and are imported into HealthKit as soon as Health is writable: HealthKit is
/// unavailable while the iPhone is locked, so imports retry when it unlocks.
final class WatchHealthImporter: ObservableObject {
    static let shared = WatchHealthImporter()

    /// Kept for compatibility with earlier versions' toggle.
    static let enabledKey = "syncWorkouts"

    @Published private(set) var pendingCount = 0
    @Published private(set) var lastImport: Date?
    @Published private(set) var lastIssue: String?
    @Published private(set) var workoutsImported = 0

    private let store = HKHealthStore()
    private let logger = Logger(subsystem: "com.wearos.ancsbridge.companion", category: "WatchHealth")
    private let directory: URL
    private var importing = false
    private var importAgain = false

    static var shareTypes: Set<HKSampleType> {
        var types: Set<HKSampleType> = [HKObjectType.workoutType(), HKSeriesType.workoutRoute()]
        let ids: [HKQuantityTypeIdentifier] = [
            .heartRate, .activeEnergyBurned, .distanceWalkingRunning, .distanceCycling, .stepCount,
            .flightsClimbed, .restingHeartRate, .vo2Max, .basalEnergyBurned,
            .runningGroundContactTime, .runningVerticalOscillation, .runningStrideLength
        ]
        for id in ids { types.insert(HKQuantityType(id)) }
        types.insert(HKCategoryType(.sleepAnalysis))
        types.insert(HKCategoryType(.mindfulSession))
        return types
    }

    static var isEnabled: Bool { UserDefaults.standard.bool(forKey: enabledKey) }

    /// Asks again for types added in an update (such as Resting Energy) while sync is on.
    func requestNewTypesIfNeeded() async {
        guard Self.isEnabled, HKHealthStore.isHealthDataAvailable() else { return }
        let missing = Self.shareTypes.contains { store.authorizationStatus(for: $0) == .notDetermined }
        guard missing else { return }
        try? await store.requestAuthorization(toShare: Self.shareTypes, read: [])
    }

    private init() {
        directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("watch-health", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        lastImport = UserDefaults.standard.object(forKey: "watchHealthLastImport") as? Date
        workoutsImported = UserDefaults.standard.integer(forKey: "watchHealthWorkouts")
        refreshCount()

        let center = NotificationCenter.default
        center.addObserver(forName: UIApplication.protectedDataDidBecomeAvailableNotification,
                           object: nil, queue: .main) { [weak self] _ in self?.importPending() }
        center.addObserver(forName: UIApplication.didBecomeActiveNotification,
                           object: nil, queue: .main) { [weak self] _ in self?.importPending() }
    }

    // MARK: Receiving

    /// Stores one record received from the watch. Returning true tells the watch it may
    /// delete its copy, so only return true once the record is safely on disk.
    func receive(_ data: Data) -> Bool {
        guard Self.isEnabled, data.count <= 512 * 1024,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = object["id"] as? String,
              let type = object["type"] as? String, ["workout", "samples"].contains(type) else {
            return false
        }
        let safeID = String(id.filter { $0.isLetter || $0.isNumber || $0 == "-" }.prefix(64))
        guard !safeID.isEmpty else { return false }
        let stamp = Int(Date().timeIntervalSince1970 * 1000)
        let url = directory.appendingPathComponent("\(stamp)-\(type)-\(safeID).json")
        do {
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            logger.error("Could not store watch record: \(error.localizedDescription)")
            return false
        }
        refreshCount()
        importPending()
        return true
    }

    // MARK: Importing

    func importPending() {
        DispatchQueue.main.async {
            guard Self.isEnabled, HKHealthStore.isHealthDataAvailable(),
                  UIApplication.shared.isProtectedDataAvailable else { return }
            if self.importing { self.importAgain = true; return }
            self.importing = true
            Task {
                await self.importAll()
                DispatchQueue.main.async {
                    self.importing = false
                    if self.importAgain { self.importAgain = false; self.importPending() }
                }
            }
        }
    }

    private func pendingFiles() -> [URL] {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "json" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    private func refreshCount() {
        let count = pendingFiles().count
        DispatchQueue.main.async { self.pendingCount = count }
    }

    private func importAll() async {
        for url in pendingFiles() {
            guard let data = try? Data(contentsOf: url),
                  let record = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                try? FileManager.default.removeItem(at: url)
                continue
            }
            do {
                if record["type"] as? String == "workout" {
                    let saved = try await importWorkout(record)
                    if saved { noteWorkoutImported() }
                } else {
                    try await importSamples(record)
                }
                try? FileManager.default.removeItem(at: url)
                noteImport(issue: nil)
            } catch let error as HKError where error.code == .errorDatabaseInaccessible {
                // Locked: retried on protectedDataDidBecomeAvailable.
                break
            } catch let error as HKError where error.code == .errorAuthorizationDenied
                        || error.code == .errorAuthorizationNotDetermined {
                noteImport(issue: "Allow WearBridge to write Workouts, Heart Rate, Steps and Energy in the Health app.")
                break
            } catch let error as HKError where error.code == .errorInvalidArgument {
                logger.error("Dropping invalid watch record: \(error.localizedDescription)")
                try? FileManager.default.removeItem(at: url)
            } catch {
                noteImport(issue: error.localizedDescription)
                break
            }
        }
        refreshCount()
    }

    private func noteImport(issue: String?) {
        DispatchQueue.main.async {
            self.lastIssue = issue
            if issue == nil {
                let now = Date()
                self.lastImport = now
                UserDefaults.standard.set(now, forKey: "watchHealthLastImport")
            }
        }
    }

    private func noteWorkoutImported() {
        DispatchQueue.main.async {
            self.workoutsImported += 1
            UserDefaults.standard.set(self.workoutsImported, forKey: "watchHealthWorkouts")
        }
    }

    // MARK: Workouts

    private static let device = HKDevice(
        name: "Pixel Watch", manufacturer: "Google", model: "Pixel Watch",
        hardwareVersion: nil, firmwareVersion: nil, softwareVersion: nil,
        localIdentifier: "WearBridge", udiDeviceIdentifier: nil
    )

    private static func date(_ value: Any?) -> Date? {
        guard let ms = (value as? NSNumber)?.doubleValue, ms > 0 else { return nil }
        return Date(timeIntervalSince1970: ms / 1000)
    }

    static func activityType(_ name: String) -> HKWorkoutActivityType {
        switch name {
        case "walking": return .walking
        case "running": return .running
        case "cycling": return .cycling
        case "hiking": return .hiking
        case "traditionalStrengthTraining": return .traditionalStrengthTraining
        case "highIntensityIntervalTraining": return .highIntensityIntervalTraining
        case "yoga": return .yoga
        case "elliptical": return .elliptical
        case "rowing": return .rowing
        case "stairClimbing": return .stairClimbing
        case "socialDance": return .socialDance
        default: return .other
        }
    }

    static func distanceType(_ activity: HKWorkoutActivityType) -> HKQuantityType? {
        switch activity {
        case .walking, .running, .hiking: return HKQuantityType(.distanceWalkingRunning)
        case .cycling: return HKQuantityType(.distanceCycling)
        default: return nil
        }
    }

    private func workoutExists(syncIdentifier: String) async -> Bool {
        let predicate = HKQuery.predicateForObjects(
            withMetadataKey: HKMetadataKeySyncIdentifier, allowedValues: [syncIdentifier]
        )
        let descriptor = HKSampleQueryDescriptor(
            predicates: [.workout(predicate)], sortDescriptors: [], limit: 1
        )
        let results = try? await descriptor.result(for: store)
        return !(results ?? []).isEmpty
    }

    /// Returns false when the workout was already in Health (re-sent after a lost ack).
    private func importWorkout(_ record: [String: Any]) async throws -> Bool {
        guard let id = record["id"] as? String,
              let start = Self.date(record["start"]), let end = Self.date(record["end"]), end > start else {
            throw HKError(.errorInvalidArgument)
        }
        let syncIdentifier = "wearbridge.workout.\(id)"
        if await workoutExists(syncIdentifier: syncIdentifier) { return false }

        let activity = Self.activityType(record["activity"] as? String ?? "other")
        let indoor = record["indoor"] as? Bool ?? false
        let configuration = HKWorkoutConfiguration()
        configuration.activityType = activity
        configuration.locationType = indoor ? .indoor : .outdoor

        let builder = HKWorkoutBuilder(healthStore: store, configuration: configuration, device: Self.device)
        try await builder.beginCollection(at: start)

        var samples: [HKSample] = []
        if let kcal = (record["kcal"] as? NSNumber)?.doubleValue, kcal > 0 {
            samples.append(HKQuantitySample(
                type: HKQuantityType(.activeEnergyBurned),
                quantity: HKQuantity(unit: .kilocalorie(), doubleValue: kcal),
                start: start, end: end, device: Self.device, metadata: nil))
        }
        if let meters = (record["distance"] as? NSNumber)?.doubleValue, meters > 0,
           let type = Self.distanceType(activity) {
            samples.append(HKQuantitySample(
                type: type, quantity: HKQuantity(unit: .meter(), doubleValue: meters),
                start: start, end: end, device: Self.device, metadata: nil))
        }
        if let steps = (record["steps"] as? NSNumber)?.doubleValue, steps > 0 {
            samples.append(HKQuantitySample(
                type: HKQuantityType(.stepCount), quantity: HKQuantity(unit: .count(), doubleValue: steps),
                start: start, end: end, device: Self.device, metadata: nil))
        }
        let bpmUnit = HKUnit.count().unitDivided(by: .minute())
        for point in record["hr"] as? [[NSNumber]] ?? [] where point.count >= 2 {
            let time = Date(timeIntervalSince1970: point[0].doubleValue / 1000)
            guard time >= start, time <= end else { continue }
            samples.append(HKQuantitySample(
                type: HKQuantityType(.heartRate), quantity: HKQuantity(unit: bpmUnit, doubleValue: point[1].doubleValue),
                start: time, end: time, device: Self.device, metadata: nil))
        }
        // Running form averages over the whole workout, as Apple Watch records them.
        let form: [(String, HKQuantityTypeIdentifier, HKUnit)] = [
            ("gct", .runningGroundContactTime, HKUnit.secondUnit(with: .milli)),
            ("vosc", .runningVerticalOscillation, HKUnit.meterUnit(with: .centi)),
            ("stride", .runningStrideLength, HKUnit.meter()),
        ]
        for (key, identifier, unit) in form {
            if let value = (record[key] as? NSNumber)?.doubleValue, value > 0 {
                samples.append(HKQuantitySample(
                    type: HKQuantityType(identifier), quantity: HKQuantity(unit: unit, doubleValue: value),
                    start: start, end: end, device: Self.device, metadata: nil))
            }
        }
        if !samples.isEmpty { try await builder.addSamples(samples) }

        var events: [HKWorkoutEvent] = []
        for pause in record["pauses"] as? [[NSNumber]] ?? [] where pause.count == 2 {
            let paused = Date(timeIntervalSince1970: pause[0].doubleValue / 1000)
            let resumed = Date(timeIntervalSince1970: pause[1].doubleValue / 1000)
            guard paused >= start, resumed <= end, resumed >= paused else { continue }
            events.append(HKWorkoutEvent(type: .pause, dateInterval: DateInterval(start: paused, duration: 0), metadata: nil))
            events.append(HKWorkoutEvent(type: .resume, dateInterval: DateInterval(start: resumed, duration: 0), metadata: nil))
        }
        // Kilometre splits as laps, user-marked segments as segments (both show in Fitness).
        var previous = start
        for split in record["splits"] as? [[NSNumber]] ?? [] where split.count >= 1 {
            let time = Date(timeIntervalSince1970: split[0].doubleValue / 1000)
            guard time > previous, time <= end else { continue }
            events.append(HKWorkoutEvent(type: .lap, dateInterval: DateInterval(start: previous, end: time), metadata: nil))
            previous = time
        }
        previous = start
        for mark in record["segments"] as? [NSNumber] ?? [] {
            let time = Date(timeIntervalSince1970: mark.doubleValue / 1000)
            guard time > previous, time <= end else { continue }
            events.append(HKWorkoutEvent(type: .segment, dateInterval: DateInterval(start: previous, end: time), metadata: nil))
            previous = time
        }
        if !events.isEmpty { try await builder.addWorkoutEvents(events) }

        try await builder.addMetadata([
            HKMetadataKeySyncIdentifier: syncIdentifier,
            HKMetadataKeySyncVersion: 1,
            HKMetadataKeyIndoorWorkout: indoor,
        ])
        try await builder.endCollection(at: end)
        guard let workout = try await builder.finishWorkout() else {
            throw HKError(.errorInvalidArgument)
        }

        let locations: [CLLocation] = (record["route"] as? [[NSNumber]] ?? []).compactMap { point in
            guard point.count >= 3 else { return nil }
            let coordinate = CLLocationCoordinate2D(latitude: point[1].doubleValue, longitude: point[2].doubleValue)
            guard CLLocationCoordinate2DIsValid(coordinate) else { return nil }
            let hasAltitude = point.count >= 4
            return CLLocation(
                coordinate: coordinate,
                altitude: hasAltitude ? point[3].doubleValue : 0,
                horizontalAccuracy: 5,
                verticalAccuracy: hasAltitude ? 10 : -1,
                timestamp: Date(timeIntervalSince1970: point[0].doubleValue / 1000))
        }
        if let vo2 = (record["vo2max"] as? NSNumber)?.doubleValue, vo2 > 0 {
            let sample = HKQuantitySample(
                type: HKQuantityType(.vo2Max),
                quantity: HKQuantity(unit: HKUnit(from: "ml/kg*min"), doubleValue: vo2),
                start: end, end: end, device: Self.device,
                metadata: [HKMetadataKeySyncIdentifier: "wearbridge.vo2.\(id)", HKMetadataKeySyncVersion: 1])
            do { try await store.save(sample) } catch { logger.error("VO2 max not saved: \(error.localizedDescription)") }
        }
        if locations.count >= 2 {
            // A missing route must not lose the workout itself.
            do {
                let routeBuilder = HKWorkoutRouteBuilder(healthStore: store, device: Self.device)
                try await routeBuilder.insertRouteData(locations)
                _ = try await routeBuilder.finishRoute(with: workout, metadata: nil)
            } catch {
                logger.error("Route not saved: \(error.localizedDescription)")
            }
        }
        return true
    }

    // MARK: All-day samples

    private func importSamples(_ record: [String: Any]) async throws {
        let bpmUnit = HKUnit.count().unitDivided(by: .minute())
        var objects: [HKObject] = []
        for point in record["hr"] as? [[NSNumber]] ?? [] where point.count >= 2 {
            let ms = point[0].int64Value
            let time = Date(timeIntervalSince1970: Double(ms) / 1000)
            objects.append(HKQuantitySample(
                type: HKQuantityType(.heartRate), quantity: HKQuantity(unit: bpmUnit, doubleValue: point[1].doubleValue),
                start: time, end: time, device: Self.device,
                metadata: [HKMetadataKeySyncIdentifier: "wearbridge.hr.\(ms)", HKMetadataKeySyncVersion: 1]))
        }
        for interval in record["steps"] as? [[NSNumber]] ?? [] where interval.count >= 3 {
            let startMs = interval[0].int64Value, endMs = interval[1].int64Value
            guard endMs > startMs, interval[2].doubleValue > 0 else { continue }
            objects.append(HKQuantitySample(
                type: HKQuantityType(.stepCount), quantity: HKQuantity(unit: .count(), doubleValue: interval[2].doubleValue),
                start: Date(timeIntervalSince1970: Double(startMs) / 1000),
                end: Date(timeIntervalSince1970: Double(endMs) / 1000), device: Self.device,
                metadata: [HKMetadataKeySyncIdentifier: "wearbridge.steps.\(startMs)-\(endMs)", HKMetadataKeySyncVersion: 1]))
        }
        for interval in record["sleep"] as? [[NSNumber]] ?? [] where interval.count >= 2 {
            let startMs = interval[0].int64Value, endMs = interval[1].int64Value
            guard endMs > startMs else { continue }
            objects.append(HKCategorySample(
                type: HKCategoryType(.sleepAnalysis),
                value: HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue,
                start: Date(timeIntervalSince1970: Double(startMs) / 1000),
                end: Date(timeIntervalSince1970: Double(endMs) / 1000), device: Self.device,
                metadata: [HKMetadataKeySyncIdentifier: "wearbridge.sleep.\(startMs)", HKMetadataKeySyncVersion: 1]))
        }
        // Breathe sessions from the watch → Mindful Minutes.
        for interval in record["mindful"] as? [[NSNumber]] ?? [] where interval.count >= 2 {
            let startMs = interval[0].int64Value, endMs = interval[1].int64Value
            guard endMs > startMs else { continue }
            objects.append(HKCategorySample(
                type: HKCategoryType(.mindfulSession),
                value: HKCategoryValue.notApplicable.rawValue,
                start: Date(timeIntervalSince1970: Double(startMs) / 1000),
                end: Date(timeIntervalSince1970: Double(endMs) / 1000), device: Self.device,
                metadata: [HKMetadataKeySyncIdentifier: "wearbridge.mindful.\(startMs)", HKMetadataKeySyncVersion: 1]))
        }
        for point in record["resting"] as? [[NSNumber]] ?? [] where point.count >= 2 {
            let ms = point[0].int64Value
            let time = Date(timeIntervalSince1970: Double(ms) / 1000)
            objects.append(HKQuantitySample(
                type: HKQuantityType(.restingHeartRate), quantity: HKQuantity(unit: bpmUnit, doubleValue: point[1].doubleValue),
                start: time, end: time, device: Self.device,
                metadata: [HKMetadataKeySyncIdentifier: "wearbridge.resting.\(ms)", HKMetadataKeySyncVersion: 1]))
        }
        // Daily distance and floors arrive as interval deltas, like steps.
        let intervalTypes: [(String, HKQuantityTypeIdentifier, HKUnit)] = [
            ("distance", .distanceWalkingRunning, .meter()),
            ("floors", .flightsClimbed, .count()),
            // Active Energy feeds the Move ring in the iPhone Fitness app; Resting Energy completes the total.
            ("active", .activeEnergyBurned, .kilocalorie()),
            ("basal", .basalEnergyBurned, .kilocalorie()),
        ]
        for (key, identifier, unit) in intervalTypes {
            for interval in record[key] as? [[NSNumber]] ?? [] where interval.count >= 3 {
                let startMs = interval[0].int64Value, endMs = interval[1].int64Value
                guard endMs > startMs, interval[2].doubleValue > 0 else { continue }
                objects.append(HKQuantitySample(
                    type: HKQuantityType(identifier), quantity: HKQuantity(unit: unit, doubleValue: interval[2].doubleValue),
                    start: Date(timeIntervalSince1970: Double(startMs) / 1000),
                    end: Date(timeIntervalSince1970: Double(endMs) / 1000), device: Self.device,
                    metadata: [HKMetadataKeySyncIdentifier: "wearbridge.\(key).\(startMs)-\(endMs)", HKMetadataKeySyncVersion: 1]))
            }
        }
        if !objects.isEmpty { try await store.save(objects) }
    }
}
