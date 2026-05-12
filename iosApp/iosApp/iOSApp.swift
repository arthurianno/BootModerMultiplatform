import SwiftUI
import ComposeApp

@main
struct iOSApp: App {

    init() {
        // Инициализация Koin DI для iOS:
        // регистрирует sharedModule + iosSharedModule + iosAppModule (ScanViewModel, IosSystemChecker)
        KoinIosKt.doInitKoinIos()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}