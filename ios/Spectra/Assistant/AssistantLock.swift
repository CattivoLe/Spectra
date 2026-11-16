import SwiftUI
import LocalChatKit

extension View {
    /// Closes the whole app behind Face ID / passcode when the user turned the lock on in the Assistant's privacy settings,
    /// and hides it in the app switcher when asked.
    func assistantLock(_ model: AssistantModel) -> some View {
        modifier(AssistantLockModifier(lock: model.lock))
    }
}

private struct AssistantLockModifier: ViewModifier {
    let lock: AppLock
    @Environment(\.scenePhase) private var phase
    @State private var overlay = AssistantOverlayWindow()

    func body(content: Content) -> some View {
        content
            // While the app is locked nothing is visible or tappable under the cover.
            .opacity(lock.isLocked ? 0 : 1)
            .allowsHitTesting(!lock.isLocked)
            .task {
                overlay.sync(lock: lock)
                if lock.isLocked {
                    try? await Task.sleep(for: .milliseconds(400))
                    await lock.unlock()
                }
            }
            .onChange(of: phase) { _, new in
                switch new {
                case .background:
                    lock.appDidEnterBackground()
                case .active:
                    lock.appDidBecomeActive()
                    if lock.isLocked {
                        Task {
                            try? await Task.sleep(for: .milliseconds(400))
                            await lock.unlock()
                        }
                    }
                default:
                    break
                }
                overlay.sync(lock: lock)
                // The scene can become active a little after the notification arrives: check once more.
                Task {
                    try? await Task.sleep(for: .milliseconds(300))
                    overlay.sync(lock: lock)
                }
            }
            .onChange(of: lock.isLocked) { overlay.sync(lock: lock) }
    }
}

private struct AssistantCoverView: View {
    let lock: AppLock
    /// Safety net: if the app is already in front and the cover is somehow still up, a tap removes it.
    let onTapWhenUnlocked: () -> Void

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            VStack(spacing: 16) {
                Image(systemName: lock.isLocked ? "lock.fill" : "antenna.radiowaves.left.and.right")
                    .font(.system(size: 56)).foregroundStyle(.secondary)
                if lock.isLocked {
                    Text("Spectra is locked").font(.title3.bold())
                    Button {
                        Task { await lock.unlock() }
                    } label: {
                        Label("Unlock with \(lock.methodName)", systemImage: "faceid")
                    }
                    .buttonStyle(.borderedProminent).disabled(lock.isAuthenticating)
                }
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { if !lock.isLocked { onTapWhenUnlocked() } }
    }
}

/// The cover lives in its own window above everything, sheets included:
/// otherwise the app switcher would keep a snapshot with a chat in it.
@MainActor
private final class AssistantOverlayWindow {
    private var window: UIWindow?

    private var scene: UIWindowScene? {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
    }

    func sync(lock: AppLock) {
        let active = scene?.activationState == .foregroundActive
        set(visible: lock.isLocked || (!active && lock.hideInSwitcher), lock: lock)
    }

    private func set(visible: Bool, lock: AppLock) {
        if visible {
            guard window == nil, let scene else { return }
            let overlay = UIWindow(windowScene: scene)
            overlay.windowLevel = .alert + 1
            overlay.rootViewController = UIHostingController(rootView: AssistantCoverView(lock: lock, onTapWhenUnlocked: { [weak self] in
                guard let self, scene.activationState == .foregroundActive else { return }
                set(visible: false, lock: lock)
            }))
            overlay.makeKeyAndVisible()
            window = overlay
        } else if let overlay = window {
            overlay.isHidden = true
            window = nil
            scene?.windows.first?.makeKey()
        }
    }
}
