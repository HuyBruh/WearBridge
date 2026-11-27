import SwiftUI
import HealthKit
import EventKit
import Photos
import AVFoundation

struct SettingsView: View {
    @EnvironmentObject var viewModel: BLEViewModel
    @State private var liveCallStatusEnabled: Bool = false

    @AppStorage("allowPing") private var allowPing = false
    @AppStorage("shareAgenda") private var shareAgenda = false
    @AppStorage("syncWorkouts") private var syncWorkouts = false
    @State private var cameraOpen = false
    @State private var toolsMessage = ""
    @State private var healthIssue: String?
    @State private var requestingHealth = false

    var body: some View {
        NavigationStack {
            List {
                Section("Health") {
                    Button {
                        Task { await updateHealth() }
                    } label: {
                        Label {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("Apple Health").foregroundStyle(.primary)
                                Text(requestingHealth ? "Requesting access…" : (syncWorkouts ? "Workout sync on · tap to pause" : "Connect to save workouts"))
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: "heart.fill").foregroundStyle(.pink)
                        }
                    }
                    .disabled(requestingHealth)
                    if let healthIssue {
                        Label(healthIssue, systemImage: "info.circle")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                Section("iPhone controls") {
                    Toggle(isOn: $allowPing) {
                        Label("Ping iPhone", systemImage: "bell")
                    }
                    Button(shareAgenda ? "Stop sharing calendar" : "Share next 24 hours of calendar") {
                        if shareAgenda { shareAgenda = false }
                        else { Task {
                            shareAgenda = (try? await EKEventStore().requestFullAccessToEvents()) == true
                            if !shareAgenda { toolsMessage = "Calendar access is required to share your agenda." }
                        } }
                    }
                    Button("Open remote camera") {
                        Task {
                            guard UIImagePickerController.isSourceTypeAvailable(.camera),
                                  await AVCaptureDevice.requestAccess(for: .video),
                                  await PHPhotoLibrary.requestAuthorization(for: .addOnly) == .authorized else {
                                toolsMessage = "Allow Camera and Add Photos in Settings."
                                return
                            }
                            cameraOpen = true
                        }
                    }
                    if !toolsMessage.isEmpty { Text(toolsMessage) }
                }
                .fullScreenCover(isPresented: $cameraOpen) {
                    WatchCamera { message in
                        toolsMessage = message
                        cameraOpen = false
                    }.ignoresSafeArea()
                }
                Section("Calls") {
                    NavigationLink {
                        QuickReplySettingsView()
                    } label: {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Quick Reply Messages")
                                Text("Decline calls with a preset message")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        } icon: {
                            Image(systemName: "text.bubble")
                                .foregroundColor(.blue)
                        }
                    }

                    Toggle(isOn: $liveCallStatusEnabled) {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Live Call Status")
                                Text("Push real-time call state to watch via BLE. Requires this app to be running.")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        } icon: {
                            Image(systemName: "phone.connection")
                                .foregroundColor(.green)
                        }
                    }
                    .onChange(of: liveCallStatusEnabled) { newValue in
                        viewModel.peripheralManager.settingsStore.liveCallStatus = newValue
                        viewModel.peripheralManager.updateCallStateObserving()
                    }
                }

                // About
                Section {
                    NavigationLink {
                        HowItWorksView()
                    } label: {
                        Label("How it Works", systemImage: "questionmark.circle")
                    }

                    HStack {
                        Text("Version")
                        Spacer()
                        Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                            .foregroundColor(.secondary)
                    }
                } header: {
                    Text("About")
                } footer: {
                    Text("WearBridge uses Apple's ANCS to forward notifications over Bluetooth. All data stays local.")
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                liveCallStatusEnabled = viewModel.peripheralManager.settingsStore.liveCallStatus
            }
        }
    }
    @MainActor
    private func updateHealth() async {
        healthIssue = nil
        guard HKHealthStore.isHealthDataAvailable() else {
            healthIssue = "Apple Health is unavailable on this device."
            return
        }

        let store = HKHealthStore()
        let workout = HKObjectType.workoutType()
        let status = store.authorizationStatus(for: workout)
        if status == .sharingAuthorized {
            syncWorkouts.toggle()
            return
        }

        syncWorkouts = false
        requestingHealth = true
        defer { requestingHealth = false }
        do {
            try await store.requestAuthorization(toShare: [workout], read: [])
            syncWorkouts = store.authorizationStatus(for: workout) == .sharingAuthorized
            if !syncWorkouts {
                healthIssue = "In Apple Health, open your profile, find WearBridge under Apps and Services, and allow Workouts."
            }
        } catch {
            let failure = error as NSError
            if failure.localizedDescription.localizedCaseInsensitiveContains("entitlement") {
                healthIssue = "This installation lacks HealthKit in its signature. Reinstall using a provisioning profile with HealthKit enabled for this app."
            } else {
                healthIssue = "\(failure.localizedDescription) (\(failure.domain) \(failure.code))"
            }
        }
    }
}


struct WatchCamera: UIViewControllerRepresentable {
    // Camera exists only while its visible iPhone sheet is open.
    static weak var active: UIImagePickerController?
    let finish: (String) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(finish) }
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.showsCameraControls = false
        let overlay = UIView(frame: picker.view.bounds)
        overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        let close = UIButton(type: .system)
        close.configuration = .filled()
        close.setTitle("Close camera", for: .normal)
        close.addAction(UIAction { [weak picker] _ in
            guard let picker else { return }
            picker.delegate?.imagePickerControllerDidCancel?(picker)
        }, for: .touchUpInside)
        overlay.addSubview(close)
        close.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            close.centerXAnchor.constraint(equalTo: overlay.centerXAnchor),
            close.bottomAnchor.constraint(equalTo: overlay.safeAreaLayoutGuide.bottomAnchor, constant: -20)
        ])
        picker.cameraOverlayView = overlay
        picker.delegate = context.coordinator
        Self.active = picker
        return picker
    }
    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let finish: (String) -> Void
        init(_ finish: @escaping (String) -> Void) { self.finish = finish }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { finish("") }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            guard let image = info[.originalImage] as? UIImage else { finish("No photo captured."); return }
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.creationRequestForAsset(from: image)
            }) { saved, _ in DispatchQueue.main.async { self.finish(saved ? "Photo saved." : "Could not save photo.") } }
        }
    }
}
