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

    var body: some View {
        NavigationStack {
            List {
                Section("Pixel Watch tools") {
                    Button(syncWorkouts ? "Disable workout import" : "Allow Apple Health workouts") {
                        if syncWorkouts { syncWorkouts = false }
                        else { Task {
                            guard HKHealthStore.isHealthDataAvailable() else { toolsMessage = "HealthKit unavailable."; return }
                            let store = HKHealthStore()
                            let type = HKObjectType.workoutType()
                            do {
                                try await store.requestAuthorization(toShare: [type], read: [])
                                syncWorkouts = store.authorizationStatus(for: type) == .sharingAuthorized
                                if !syncWorkouts { toolsMessage = "Allow workout writes in Health." }
                            } catch { toolsMessage = "Health authorization unavailable for this installation." }
                        } }
                    }
                    Toggle("Allow ping notifications", isOn: $allowPing)
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
                // Features
                Section {
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
            .onAppear {
                liveCallStatusEnabled = viewModel.peripheralManager.settingsStore.liveCallStatus
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
