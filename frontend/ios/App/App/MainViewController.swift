import Foundation
import Capacitor
import UIKit
import Lottie

class MainViewController: CAPBridgeViewController {

    // Paints synchronously in viewDidLoad to fill the gap between iOS LaunchScreen
    // disappearing and Capacitor's SplashScreen plugin (which dispatches async) painting.
    private var bridgeLoadingOverlay: UIView?

    override func capacitorDidLoad() {
        bridge?.registerPluginInstance(NativePasskeyPlugin())
        bridge?.registerPluginInstance(KeychainSyncPlugin())
        bridge?.registerPluginInstance(LoadingOverlayPlugin())
        bridge?.webView?.isOpaque = false
        bridge?.webView?.backgroundColor = .white
        bridge?.webView?.scrollView.backgroundColor = .white
        // Chat UX: scrolling the conversation must NOT dismiss the keyboard.
        // Dismissal is handled in-app: a drag that travels down past half the
        // keyboard's height (Snapchat-style) calls Keyboard.hide().
        bridge?.webView?.scrollView.keyboardDismissMode = .none
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        // Backup: ensures the bridge VC view paints white even before subviews lay out.
        view.backgroundColor = .white

        // Frame-based (not constraint-based) so it has correct geometry on first paint
        // without waiting for a layout pass.
        let screen = UIScreen.main.bounds
        let overlay = UIView(frame: screen)
        overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        overlay.backgroundColor = .white
        overlay.isUserInteractionEnabled = false

        let animationView = LottieAnimationView(name: "coin-drop")
        animationView.contentMode = .scaleAspectFill
        animationView.frame = screen
        animationView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        animationView.loopMode = .loop
        animationView.play()
        overlay.addSubview(animationView)

        view.addSubview(overlay)
        bridgeLoadingOverlay = overlay
    }

    func removeBridgeLoadingOverlay() {
        bridgeLoadingOverlay?.removeFromSuperview()
        bridgeLoadingOverlay = nil
    }
}
