import SwiftUI
import UIKit
import MessageUI

struct WatchDraft: Codable, Identifiable {
    let id: UUID
    let sender: String
    let body: String
    /// Bundle ID of the app the alert came from (nil or Messages → Messages composer).
    var app: String? = nil

    /// Third-party chat apps can't be sent to from here: open them with the text copied.
    var chatApp: (name: String, url: URL)? {
        guard let app, app.lowercased() != "com.apple.mobilesms" else { return nil }
        let known: [String: (String, String)] = [
            "com.facebook.messenger": ("Messenger", "fb-messenger://"),
            "net.whatsapp.whatsapp": ("WhatsApp", "whatsapp://"),
            "net.whatsapp.whatsappsmb": ("WhatsApp Business", "whatsapp://"),
            "vn.com.vng.zingalo": ("Zalo", "zalo://"),
            "org.telegram.telegram": ("Telegram", "tg://"),
            "ph.telegra.telegraph": ("Telegram", "tg://"),
            "org.whispersystems.signal": ("Signal", "sgnl://"),
            "com.burbn.instagram": ("Instagram", "instagram://direct-inbox"),
            "jp.naver.line": ("LINE", "line://"),
            "com.viber": ("Viber", "viber://"),
            "com.hammerandchisel.discord": ("Discord", "discord://"),
            "com.tinyspeck.chatlyio": ("Slack", "slack://open"),
            "com.slack.slack": ("Slack", "slack://open"),
            "com.microsoft.skype.teams": ("Teams", "msteams://"),
            "com.kakao.talk": ("KakaoTalk", "kakaotalk://"),
            "com.tencent.xin": ("WeChat", "weixin://"),
        ]
        guard let entry = known[app.lowercased()], let url = URL(string: entry.1) else { return nil }
        return (entry.0, url)
    }

    /// Copies the reply and opens the chat app. Main thread.
    func openInChatApp(completion: @escaping (Bool) -> Void) {
        guard let target = chatApp else { completion(false); return }
        UIPasteboard.general.string = body
        UIApplication.shared.open(target.url) { completion($0) }
    }
}

// Both the peripheral delegate and the views use the main queue.
final class DraftInbox: ObservableObject {
    static let shared = DraftInbox()
    @Published private(set) var drafts: [WatchDraft] = []
    /// Set when the user taps a "Reply to …" notification; ContentView opens the composer.
    @Published var pendingOpen: UUID?
    private let file = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("watch-drafts.json")

    init() { try? reload() }

    private func reload() throws {
        if FileManager.default.fileExists(atPath: file.path) {
            drafts = try JSONDecoder().decode([WatchDraft].self, from: Data(contentsOf: file))
        }
    }

    private func commit(_ items: [WatchDraft]) throws {
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(items).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        drafts = items
    }

    func save(_ draft: WatchDraft) -> Bool {
        do {
            try reload()
            if drafts.contains(where: { $0.id == draft.id }) { return true }
            guard drafts.count < 50 else { return false }
            try commit([draft] + drafts)
            return true
        } catch { return false }
    }

    func remove(_ id: UUID) throws {
        try reload()
        try commit(drafts.filter { $0.id != id })
    }
}

struct ContentView: View {
    @ObservedObject private var inbox = DraftInbox.shared
    @State private var composing: WatchDraft?
    @State private var issue: String?
    var body: some View {
        TabView {
            StatusView().tabItem { Label("System", systemImage: "antenna.radiowaves.left.and.right") }
            NavigationStack {
                List {
                    if inbox.drafts.isEmpty {
                        Text("Replies drafted from Messages alerts on your watch appear here.")
                    }
                    ForEach(inbox.drafts) { draft in
                        Button {
                            if let chat = draft.chatApp {
                                draft.openInChatApp { opened in
                                    if opened { remove(draft.id) }
                                    else { issue = "\(chat.name) isn't installed. The reply is copied." }
                                }
                            } else if MFMessageComposeViewController.canSendText() { composing = draft }
                            else { issue = "Messages is unavailable on this iPhone." }
                        } label: {
                            VStack(alignment: .leading) {
                                Text(draft.sender.isEmpty ? (draft.chatApp?.name ?? "Messages") : draft.sender).font(.headline)
                                Text(draft.body)
                                Text(draft.chatApp.map { "Copy and open \($0.name)" } ?? "Choose recipient and send").font(.caption)
                            }
                        }
                        .swipeActions { Button("Delete", role: .destructive) { remove(draft.id) } }
                    }
                }.navigationTitle("Watch drafts")
            }.tabItem { Label("Drafts", systemImage: "square.and.pencil") }.badge(inbox.drafts.count)
            SettingsView().tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .sheet(item: $composing) { draft in
            DraftComposer(draft: draft) { result in
                composing = nil
                if result == .sent { remove(draft.id) }
                if result == .failed { issue = "Messages could not send. Your draft is saved." }
            }
        }
        .onReceive(inbox.$pendingOpen) { id in
            guard let id else { return }
            inbox.pendingOpen = nil
            guard let draft = inbox.drafts.first(where: { $0.id == id }) else { return }
            if let chat = draft.chatApp {
                draft.openInChatApp { opened in
                    if opened { remove(draft.id) }
                    else { issue = "\(chat.name) isn't installed. The reply is copied, paste it anywhere." }
                }
            } else if MFMessageComposeViewController.canSendText() { composing = draft }
            else { issue = "Messages is unavailable on this iPhone." }
        }
        .alert("Watch drafts", isPresented: Binding(get: { issue != nil }, set: { if !$0 { issue = nil } })) {
            Button("OK") { issue = nil }
        } message: { Text(issue ?? "") }
    }

    private func remove(_ id: UUID) {
        do { try inbox.remove(id) }
        catch { issue = "Could not remove the saved draft." }
    }
}

private struct DraftComposer: UIViewControllerRepresentable {
    let draft: WatchDraft
    let finish: (MessageComposeResult) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(finish) }

    func makeUIViewController(context: Context) -> MFMessageComposeViewController {
        let controller = MFMessageComposeViewController()
        controller.messageComposeDelegate = context.coordinator
        controller.body = draft.body
        // ANCS supplies a display title, not a verified destination or group ID.
        return controller
    }

    func updateUIViewController(_ controller: MFMessageComposeViewController, context: Context) {}

    final class Coordinator: NSObject, MFMessageComposeViewControllerDelegate {
        let finish: (MessageComposeResult) -> Void
        init(_ finish: @escaping (MessageComposeResult) -> Void) { self.finish = finish }
        func messageComposeViewController(_ controller: MFMessageComposeViewController, didFinishWith result: MessageComposeResult) {
            finish(result)
        }
    }
}
