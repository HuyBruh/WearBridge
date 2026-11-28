import Foundation
import HealthKit
import UserNotifications

/// What the installed signature allows. Sideloaded builds (Sideloadly, AltStore…) expire:
/// 7 days with a free Apple ID, a year with a paid developer account.
enum SigningInfo {
    struct Profile {
        let expires: Date?
        let hasHealthKit: Bool
        let teamName: String?
    }

    /// Parsed once from embedded.mobileprovision (absent for App Store / TestFlight builds).
    static let profile: Profile? = {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url) else { return nil }
        return parse(data)
    }()

    /// The profile is a signed CMS envelope around a plain XML plist.
    static func parse(_ data: Data) -> Profile? {
        guard let start = data.range(of: Data("<?xml".utf8)),
              let end = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex) else { return nil }
        let xml = data.subdata(in: start.lowerBound..<end.upperBound)
        guard let plist = try? PropertyListSerialization.propertyList(from: xml, format: nil) as? [String: Any] else {
            return nil
        }
        let entitlements = plist["Entitlements"] as? [String: Any] ?? [:]
        return Profile(
            expires: plist["ExpirationDate"] as? Date,
            hasHealthKit: entitlements["com.apple.developer.healthkit"] as? Bool ?? false,
            teamName: plist["TeamName"] as? String
        )
    }

    static var expires: Date? { profile?.expires }

    /// "on", "off" (turned off in the app or not allowed), "missing" (signature has no HealthKit).
    static var healthState: String {
        if let profile, !profile.hasHealthKit { return "missing" }
        guard WatchHealthImporter.isEnabled, HKHealthStore.isHealthDataAvailable() else { return "off" }
        let status = HKHealthStore().authorizationStatus(for: HKObjectType.workoutType())
        return status == .sharingAuthorized ? "on" : "off"
    }

    /// Reminds the user to re-sign a day and two hours before the app stops opening.
    static func scheduleExpiryReminders() {
        let center = UNUserNotificationCenter.current()
        let ids = ["wearbridge.expiry.day", "wearbridge.expiry.hours"]
        center.removePendingNotificationRequests(withIdentifiers: ids)
        guard let expires else { return }
        let reminders: [(String, TimeInterval, String)] = [
            (ids[0], 24 * 3600, "WearBridge expires tomorrow"),
            (ids[1], 2 * 3600, "WearBridge expires in 2 hours"),
        ]
        for (id, before, title) in reminders {
            let fire = expires.addingTimeInterval(-before)
            guard fire > Date() else { continue }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = "Re-sign it with Sideloadly so your watch keeps getting notifications. Watch data waits until then."
            content.sound = .default
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: fire.timeIntervalSinceNow, repeats: false)
            center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
        }
    }
}
