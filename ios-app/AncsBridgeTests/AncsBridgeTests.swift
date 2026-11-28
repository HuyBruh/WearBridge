import XCTest
import HealthKit
@testable import AncsBridge

final class AncsBridgeTests: XCTestCase {

    func testWatchDraftPreservesVietnameseAndPunctuation() throws {
        let json = #"{"id":"12345678-1234-1234-1234-123456789012","sender":"Huy | Minh","body":"Đến rồi 👍"}"#
        let draft = try JSONDecoder().decode(WatchDraft.self, from: Data(json.utf8))
        XCTAssertEqual(draft.sender, "Huy | Minh")
        XCTAssertEqual(draft.body, "Đến rồi 👍")
    }

    func testWatchWorkoutActivitiesMapToHealthKit() {
        XCTAssertEqual(WatchHealthImporter.activityType("running"), .running)
        XCTAssertEqual(WatchHealthImporter.activityType("traditionalStrengthTraining"), .traditionalStrengthTraining)
        XCTAssertEqual(WatchHealthImporter.activityType("unknown"), .other)
        XCTAssertEqual(WatchHealthImporter.distanceType(.cycling), HKQuantityType(.distanceCycling))
        XCTAssertNil(WatchHealthImporter.distanceType(.yoga))
    }

    func testWatchHealthRecordsAreRefusedWhileSyncIsOff() {
        let previous = UserDefaults.standard.bool(forKey: WatchHealthImporter.enabledKey)
        defer { UserDefaults.standard.set(previous, forKey: WatchHealthImporter.enabledKey) }
        UserDefaults.standard.set(false, forKey: WatchHealthImporter.enabledKey)
        let record = #"{"type":"workout","id":"abc","start":1000,"end":2000}"#
        XCTAssertFalse(WatchHealthImporter.shared.receive(Data(record.utf8)))
        UserDefaults.standard.set(true, forKey: WatchHealthImporter.enabledKey)
        XCTAssertFalse(WatchHealthImporter.shared.receive(Data("not json".utf8)))
        XCTAssertFalse(WatchHealthImporter.shared.receive(Data(#"{"type":"other","id":"x"}"#.utf8)))
    }

    // MARK: - Quick reply URL

    func testQuickReplyURLEncodesQuerySeparators() throws {
        let url = try XCTUnwrap(QuickReplyLauncher.shortcutURL(for: "Mom & Dad|On my way = 5 min + traffic?"))
        XCTAssertEqual(url.absoluteString,
                       "shortcuts://run-shortcut?name=WearBridge%20Reply&input=text&text=Mom%20%26%20Dad%7COn%20my%20way%20%3D%205%20min%20%2B%20traffic%3F")
        let items = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertEqual(items.map(\.name), ["name", "input", "text"])
        XCTAssertEqual(items.last?.value, "Mom & Dad|On my way = 5 min + traffic?")
    }

    func testQuickReplyURLKeepsUnicode() throws {
        let url = try XCTUnwrap(QuickReplyLauncher.shortcutURL(for: "Léa|J'arrive 🚗"))
        let items = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertEqual(items.last?.value, "Léa|J'arrive 🚗")
    }

    // MARK: - Call state

    private let ringing = CallStateObserver.CallInfo(isOutgoing: false, hasConnected: false, hasEnded: false)
    private let answered = CallStateObserver.CallInfo(isOutgoing: false, hasConnected: true, hasEnded: false)
    private let ended = CallStateObserver.CallInfo(isOutgoing: false, hasConnected: true, hasEnded: true)
    private let outgoing = CallStateObserver.CallInfo(isOutgoing: true, hasConnected: true, hasEnded: false)

    func testIncomingCallLifecycle() {
        let idle = BLEConstants.callStateIdle
        XCTAssertEqual(CallStateObserver.state(of: [ringing], previous: idle), BLEConstants.callStateRinging)
        XCTAssertEqual(CallStateObserver.state(of: [answered], previous: BLEConstants.callStateRinging), BLEConstants.callStateActive)
        XCTAssertEqual(CallStateObserver.state(of: [ended], previous: BLEConstants.callStateActive), BLEConstants.callStateEnded)
        XCTAssertEqual(CallStateObserver.state(of: [], previous: BLEConstants.callStateEnded), BLEConstants.callStateEnded)
    }

    func testCallWaitingKeepsTheRingingCall() {
        // Ending the active call while a second one rings must not dismiss the ringing one.
        XCTAssertEqual(CallStateObserver.state(of: [ended, ringing], previous: BLEConstants.callStateRinging),
                       BLEConstants.callStateRinging)
        XCTAssertEqual(CallStateObserver.state(of: [answered, ringing], previous: BLEConstants.callStateActive),
                       BLEConstants.callStateRinging)
        // Declining the waiting call goes back to the active one.
        XCTAssertEqual(CallStateObserver.state(of: [answered, ended], previous: BLEConstants.callStateRinging),
                       BLEConstants.callStateActive)
    }

    func testOutgoingCallsAreIgnored() {
        XCTAssertEqual(CallStateObserver.state(of: [outgoing], previous: BLEConstants.callStateIdle), BLEConstants.callStateIdle)
        XCTAssertEqual(CallStateObserver.state(of: [outgoing, ringing], previous: BLEConstants.callStateIdle),
                       BLEConstants.callStateRinging)
        XCTAssertEqual(CallStateObserver.state(of: [outgoing, ended], previous: BLEConstants.callStateRinging),
                       BLEConstants.callStateEnded)
    }
}
