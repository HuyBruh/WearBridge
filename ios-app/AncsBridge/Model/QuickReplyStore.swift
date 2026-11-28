import Foundation
import Combine

class QuickReplyStore: ObservableObject {
    static let key = "quick_reply_messages"
    static let defaults: [String] = [
        "Can't talk right now.",
        "Hey! Can't talk rn, what's up?",
        "I'll call you back shortly.",
        "In a meeting, will call later.",
        "Driving right now, will call later."
    ]

    @Published var messages: [String] {
        didSet { save() }
    }

    init() {
        if let saved = UserDefaults.standard.stringArray(forKey: Self.key) {
            messages = saved
        } else {
            messages = Self.defaults
        }
    }

    func save() {
        UserDefaults.standard.set(messages, forKey: Self.key)
    }

    /// What the watch reads (A0E3D5B9): the current list as a JSON array.
    static func watchPayload() -> Data {
        let list = UserDefaults.standard.stringArray(forKey: key) ?? defaults
        let trimmed = list.map { String($0.prefix(160)) }.filter { !$0.isEmpty }.prefix(12)
        return (try? JSONSerialization.data(withJSONObject: Array(trimmed))) ?? Data("[]".utf8)
    }

    func add(_ message: String) {
        guard !message.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        messages.append(message)
    }

    func remove(at offsets: IndexSet) {
        messages.remove(atOffsets: offsets)
    }

    func update(at index: Int, with text: String) {
        guard messages.indices.contains(index) else { return }
        messages[index] = text
    }

    func resetToDefaults() {
        messages = Self.defaults
    }
}
