import AVFoundation
import SwiftUI

/// Read a pubky from a QR code with the camera.
///
/// Android reaches Google's bundled ML Kit scanner, which runs in its own activity and needs no
/// camera permission. iOS has no such loan: `AVCaptureSession` runs in *this* process, so the app
/// asks for the camera itself and `NSCameraUsageDescription` is not optional.
///
/// Only QR is read, and only the first code — the sheet closes on the first decode rather than
/// firing repeatedly at 30fps while the code stays in frame.
struct QrScannerSheet: View {
    var onScanned: (String) -> Void
    var onClose: () -> Void

    @State private var denied = false

    var body: some View {
        NavigationStack {
            Group {
                if denied {
                    // A dead camera view with no explanation is the worst version of this screen.
                    VStack(spacing: 10) {
                        Image(systemName: "camera.fill")
                            .font(.system(size: 32))
                            .foregroundStyle(LoopkyColor.foregroundMuted)
                        Text("permission_camera_denied")
                            .font(.system(size: 14))
                            .foregroundStyle(LoopkyColor.foregroundSecondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                    }
                } else {
                    QrCameraView(onScanned: onScanned, onDenied: { denied = true })
                        .ignoresSafeArea(edges: .bottom)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(LoopkyColor.surfacePrimary)
            .navigationTitle(Text("search_scan_qr"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("settings_cancel", action: onClose)
                }
            }
        }
    }
}

/// The capture session itself, as a `UIViewControllerRepresentable` — SwiftUI has no camera view.
private struct QrCameraView: UIViewControllerRepresentable {
    let onScanned: (String) -> Void
    let onDenied: () -> Void

    func makeUIViewController(context: Context) -> QrCaptureController {
        let controller = QrCaptureController()
        controller.onScanned = onScanned
        controller.onDenied = onDenied
        return controller
    }

    func updateUIViewController(_ controller: QrCaptureController, context: Context) {}
}

final class QrCaptureController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScanned: ((String) -> Void)?
    var onDenied: (() -> Void)?

    private let session = AVCaptureSession()
    private var delivered = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            DispatchQueue.main.async {
                guard let self else { return }
                if granted { self.configure() } else { self.onDenied?() }
            }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // Off the main thread: stopping a running session blocks until the last buffer drains.
        if session.isRunning {
            DispatchQueue.global(qos: .userInitiated).async { [session] in session.stopRunning() }
        }
    }

    private func configure() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            onDenied?()
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            onDenied?()
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.frame = view.layer.bounds
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)

        DispatchQueue.global(qos: .userInitiated).async { [session] in session.startRunning() }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        // The first code only: the delegate fires for every frame the code stays in.
        guard !delivered,
              let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = object.stringValue else { return }
        delivered = true
        onScanned?(value)
    }
}
