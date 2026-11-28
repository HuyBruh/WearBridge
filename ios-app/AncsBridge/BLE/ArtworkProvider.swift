import Foundation
import UIKit
import os

/// Album artwork for the watch's Now Playing. Apple Media Service only shares the title and
/// artist, so the artwork is looked up in the iTunes catalog (works for Apple Music, Spotify,
/// YouTube Music… as long as the song is in the catalog), scaled down and sent in chunks.
final class ArtworkProvider {
    static let shared = ArtworkProvider()

    /// Bytes of JPEG per BLE read, after the 2-byte chunk count.
    static let chunkSize = 480
    private static let pixels: CGFloat = 200

    private let logger = Logger(subsystem: "com.wearos.ancsbridge.companion", category: "Artwork")
    private let cache = NSCache<NSString, NSData>()
    private var task: Task<Void, Never>?
    private let lock = NSLock()
    private var current = Data()

    /// Called on the main queue with true when artwork is ready, false when none was found.
    var onReady: ((Bool) -> Void)?

    func request(title: String, artist: String) {
        let key = "\(title)\u{0}\(artist)" as NSString
        task?.cancel()
        if let cached = cache.object(forKey: key) {
            setCurrent(cached as Data)
            DispatchQueue.main.async { self.onReady?(true) }
            return
        }
        task = Task { [weak self] in
            guard let self else { return }
            let data = await self.lookup(title: title, artist: artist)
            if Task.isCancelled { return }
            if let data { self.cache.setObject(data as NSData, forKey: key) }
            self.setCurrent(data ?? Data())
            DispatchQueue.main.async { self.onReady?(data != nil) }
        }
    }

    /// [count hi, count lo] + one slice of the current JPEG.
    func chunk(_ index: Int) -> Data {
        lock.lock(); defer { lock.unlock() }
        let count = max(1, (current.count + Self.chunkSize - 1) / Self.chunkSize)
        var out = Data([UInt8((count >> 8) & 0xff), UInt8(count & 0xff)])
        let start = index * Self.chunkSize
        if start < current.count {
            out.append(current.subdata(in: start..<min(start + Self.chunkSize, current.count)))
        }
        return out
    }

    private func setCurrent(_ data: Data) {
        lock.lock(); current = data; lock.unlock()
    }

    private func lookup(title: String, artist: String) async -> Data? {
        let cleanTitle = Self.clean(title)
        guard !cleanTitle.isEmpty else { return nil }
        var components = URLComponents(string: "https://itunes.apple.com/search")!
        components.queryItems = [
            URLQueryItem(name: "term", value: "\(artist) \(cleanTitle)".trimmingCharacters(in: .whitespaces)),
            URLQueryItem(name: "media", value: "music"),
            URLQueryItem(name: "entity", value: "song"),
            URLQueryItem(name: "limit", value: "5"),
            URLQueryItem(name: "country", value: Locale.current.region?.identifier ?? "US"),
        ]
        do {
            guard let url = components.url else { return nil }
            let (body, _) = try await URLSession.shared.data(from: url)
            guard let json = try JSONSerialization.jsonObject(with: body) as? [String: Any],
                  let results = json["results"] as? [[String: Any]], !results.isEmpty else { return nil }
            let wanted = cleanTitle.lowercased()
            let best = results.first { ($0["trackName"] as? String).map { Self.clean($0).lowercased() == wanted } ?? false }
                ?? results.first { ($0["trackName"] as? String)?.lowercased().contains(wanted) ?? false }
                ?? results[0]
            guard var link = best["artworkUrl100"] as? String else { return nil }
            link = link.replacingOccurrences(of: "100x100bb", with: "300x300bb")
            guard let imageURL = URL(string: link) else { return nil }
            let (imageData, _) = try await URLSession.shared.data(from: imageURL)
            guard let image = UIImage(data: imageData) else { return nil }
            let size = CGSize(width: Self.pixels, height: Self.pixels)
            let format = UIGraphicsImageRendererFormat.default()
            format.scale = 1
            let scaled = UIGraphicsImageRenderer(size: size, format: format).image { _ in
                image.draw(in: CGRect(origin: .zero, size: size))
            }
            return scaled.jpegData(compressionQuality: 0.72)
        } catch {
            logger.info("Artwork lookup failed: \(error.localizedDescription)")
            return nil
        }
    }

    /// Drops "(feat. …)", "[Remastered]", " - Live" so catalog matching works more often.
    static func clean(_ title: String) -> String {
        var text = title
        for pattern in [#"\s*[\(\[][^\)\]]*[\)\]]"#, #"\s+-\s+.*$"#] {
            text = text.replacingOccurrences(of: pattern, with: "", options: .regularExpression)
        }
        return text.trimmingCharacters(in: .whitespaces)
    }
}
