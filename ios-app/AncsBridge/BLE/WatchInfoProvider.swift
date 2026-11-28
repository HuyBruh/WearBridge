import Foundation
import CoreLocation
import EventKit
import UIKit
import os

/// Weather, today's calendar and reminders for the watch's Today screen, tile and
/// complications. The watch reads it (A0E3D5BA); a one-byte notify tells it to re-read.
final class WatchInfoProvider: NSObject, CLLocationManagerDelegate {
    static let shared = WatchInfoProvider()

    static let weatherKey = "shareWeather"
    static let agendaKey = "shareAgenda"
    static let remindersKey = "shareReminders"

    /// Called on the main queue when something the watch shows has changed.
    var onChange: (() -> Void)?

    private let logger = Logger(subsystem: "com.wearos.ancsbridge.companion", category: "WatchInfo")
    private let locationManager = CLLocationManager()
    private let eventStore = EKEventStore()
    private var weather: [String: Any]?
    private var weatherFetchedAt: Date?
    private var reminders: [[String: Any]] = []
    private var remindersFetchedAt: Date?
    private var fetchingWeather = false

    private override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyKilometer
        if let cached = UserDefaults.standard.dictionary(forKey: "watchWeather") {
            weather = cached
        }
        NotificationCenter.default.addObserver(forName: .EKEventStoreChanged, object: eventStore, queue: .main) { [weak self] _ in
            self?.remindersFetchedAt = nil
            self?.onChange?()
        }
    }

    // MARK: Permissions

    func requestLocation() {
        locationManager.requestWhenInUseAuthorization()
        locationManager.requestLocation()
    }

    func requestReminders() async -> Bool {
        (try? await eventStore.requestFullAccessToReminders()) == true
    }

    // MARK: Payload

    /// The JSON the watch reads. Also refreshes anything stale in the background.
    func payload() -> Data {
        refreshIfStale()
        var root: [String: Any] = ["at": Int(Date().timeIntervalSince1970 * 1000)]
        if UserDefaults.standard.bool(forKey: Self.weatherKey), let weather { root["weather"] = weather }
        if UserDefaults.standard.bool(forKey: Self.agendaKey) { root["events"] = events() }
        if UserDefaults.standard.bool(forKey: Self.remindersKey) { root["reminders"] = reminders }
        let data = (try? JSONSerialization.data(withJSONObject: root)) ?? Data("{}".utf8)
        return data
    }

    private func refreshIfStale() {
        if UserDefaults.standard.bool(forKey: Self.weatherKey),
           Date().timeIntervalSince(weatherFetchedAt ?? .distantPast) > 30 * 60 {
            refreshWeather()
        }
        if UserDefaults.standard.bool(forKey: Self.remindersKey),
           Date().timeIntervalSince(remindersFetchedAt ?? .distantPast) > 5 * 60 {
            refreshReminders()
        }
    }

    private static func ms(_ date: Date?) -> Int? { date.map { Int($0.timeIntervalSince1970 * 1000) } }

    private func events() -> [[String: Any]] {
        guard EKEventStore.authorizationStatus(for: .event) == .fullAccess else { return [] }
        let now = Date()
        let end = Calendar.current.date(byAdding: .hour, value: 36, to: now) ?? now
        let predicate = eventStore.predicateForEvents(withStart: Calendar.current.startOfDay(for: now), end: end, calendars: nil)
        return eventStore.events(matching: predicate)
            .filter { $0.endDate > now }
            .sorted { $0.startDate < $1.startDate }
            .prefix(8)
            .map { event in
                var item: [String: Any] = [
                    "title": String((event.title ?? "Event").prefix(60)),
                    "start": Self.ms(event.startDate) ?? 0,
                    "end": Self.ms(event.endDate) ?? 0,
                    "allDay": event.isAllDay,
                ]
                if let location = event.location, !location.isEmpty { item["location"] = String(location.prefix(40)) }
                if let color = event.calendar?.cgColor, let hex = Self.hex(color) { item["color"] = hex }
                return item
            }
    }

    private static func hex(_ color: CGColor) -> String? {
        guard let rgb = color.converted(to: CGColorSpace(name: CGColorSpace.sRGB)!, intent: .defaultIntent, options: nil),
              let c = rgb.components, c.count >= 3 else { return nil }
        return String(format: "#%02X%02X%02X", Int(c[0] * 255), Int(c[1] * 255), Int(c[2] * 255))
    }

    // MARK: Reminders

    private func refreshReminders() {
        guard EKEventStore.authorizationStatus(for: .reminder) == .fullAccess else { return }
        remindersFetchedAt = Date()
        let endOfToday = Calendar.current.date(byAdding: .day, value: 1, to: Calendar.current.startOfDay(for: Date()))
        let predicate = eventStore.predicateForIncompleteReminders(withDueDateStarting: nil, ending: endOfToday, calendars: nil)
        eventStore.fetchReminders(matching: predicate) { [weak self] found in
            let items: [[String: Any]] = (found ?? [])
                .sorted { ($0.dueDateComponents?.date ?? .distantFuture) < ($1.dueDateComponents?.date ?? .distantFuture) }
                .prefix(10)
                .map { reminder in
                    var item: [String: Any] = ["id": reminder.calendarItemIdentifier, "title": String((reminder.title ?? "Reminder").prefix(60))]
                    if let due = reminder.dueDateComponents?.date { item["due"] = Self.ms(due) ?? 0 }
                    return item
                }
            DispatchQueue.main.async {
                guard let self else { return }
                let changed = (items as NSArray) != (self.reminders as NSArray)
                self.reminders = items
                if changed { self.onChange?() }
            }
        }
    }

    /// The watch ticked a reminder off.
    func completeReminder(id: String) -> Bool {
        guard EKEventStore.authorizationStatus(for: .reminder) == .fullAccess,
              let reminder = eventStore.calendarItem(withIdentifier: id) as? EKReminder else { return false }
        reminder.isCompleted = true
        do {
            try eventStore.save(reminder, commit: true)
            reminders.removeAll { $0["id"] as? String == id }
            return true
        } catch {
            logger.error("Could not complete reminder: \(error.localizedDescription)")
            return false
        }
    }

    // MARK: Weather (Open-Meteo, no account needed)

    func refreshWeather() {
        guard !fetchingWeather else { return }
        guard let coordinate = locationManager.location?.coordinate ?? Self.savedCoordinate() else {
            locationManager.requestLocation()
            return
        }
        fetchingWeather = true
        var components = URLComponents(string: "https://api.open-meteo.com/v1/forecast")!
        components.queryItems = [
            URLQueryItem(name: "latitude", value: String(format: "%.3f", coordinate.latitude)),
            URLQueryItem(name: "longitude", value: String(format: "%.3f", coordinate.longitude)),
            URLQueryItem(name: "current", value: "temperature_2m,weather_code,is_day"),
            URLQueryItem(name: "hourly", value: "temperature_2m,weather_code"),
            URLQueryItem(name: "daily", value: "temperature_2m_max,temperature_2m_min"),
            URLQueryItem(name: "forecast_days", value: "2"),
            URLQueryItem(name: "timeformat", value: "unixtime"),
            URLQueryItem(name: "timezone", value: "auto"),
        ]
        URLSession.shared.dataTask(with: components.url!) { [weak self] data, _, error in
            guard let self else { return }
            defer { DispatchQueue.main.async { self.fetchingWeather = false } }
            guard let data, error == nil,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let current = json["current"] as? [String: Any] else {
                self.logger.error("Weather fetch failed: \(error?.localizedDescription ?? "bad response")")
                return
            }
            var result: [String: Any] = [
                "temp": (current["temperature_2m"] as? NSNumber)?.doubleValue ?? 0,
                "code": (current["weather_code"] as? NSNumber)?.intValue ?? 0,
                "day": ((current["is_day"] as? NSNumber)?.intValue ?? 1) == 1,
                "at": Int(Date().timeIntervalSince1970 * 1000),
            ]
            if let daily = json["daily"] as? [String: Any] {
                if let hi = (daily["temperature_2m_max"] as? [NSNumber])?.first { result["hi"] = hi.doubleValue }
                if let lo = (daily["temperature_2m_min"] as? [NSNumber])?.first { result["lo"] = lo.doubleValue }
            }
            if let hourly = json["hourly"] as? [String: Any],
               let times = hourly["time"] as? [NSNumber],
               let temps = hourly["temperature_2m"] as? [NSNumber],
               let codes = hourly["weather_code"] as? [NSNumber] {
                let now = Date().timeIntervalSince1970
                let upcoming = times.indices.filter { times[$0].doubleValue >= now - 1800 }.prefix(6)
                result["hourly"] = upcoming.map { [times[$0].intValue * 1000, temps[$0].doubleValue, codes[$0].intValue] as [Any] }
            }
            CLGeocoder().reverseGeocodeLocation(CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)) { places, _ in
                if let place = places?.first?.locality ?? places?.first?.administrativeArea { result["place"] = place }
                DispatchQueue.main.async {
                    self.weather = result
                    self.weatherFetchedAt = Date()
                    UserDefaults.standard.set(result, forKey: "watchWeather")
                    self.onChange?()
                }
            }
        }.resume()
    }

    private static func savedCoordinate() -> CLLocationCoordinate2D? {
        let defaults = UserDefaults.standard
        guard defaults.object(forKey: "watchWeatherLat") != nil else { return nil }
        return CLLocationCoordinate2D(latitude: defaults.double(forKey: "watchWeatherLat"),
                                      longitude: defaults.double(forKey: "watchWeatherLon"))
    }

    // MARK: CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        UserDefaults.standard.set(location.coordinate.latitude, forKey: "watchWeatherLat")
        UserDefaults.standard.set(location.coordinate.longitude, forKey: "watchWeatherLon")
        if weather == nil || Date().timeIntervalSince(weatherFetchedAt ?? .distantPast) > 30 * 60 { refreshWeather() }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        logger.error("Location failed: \(error.localizedDescription)")
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways {
            manager.requestLocation()
        }
    }
}
