import AppKit

/// MANTRA REMOTE (v136): the camera's remote control on a Mac. Marko, 9.10.2026: "create a Swift app which is going
/// to use the same protocol as my Android app for remote controlling this camera ... on my MacBook Pro."
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var controller: RemoteWindowController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let menu = NSMenu()
        let appItem = NSMenuItem()
        menu.addItem(appItem)
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "Quit Mantra Remote", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu
        NSApp.mainMenu = menu

        let c = RemoteWindowController()
        c.window?.center()
        c.showWindow(nil)
        controller = c
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
