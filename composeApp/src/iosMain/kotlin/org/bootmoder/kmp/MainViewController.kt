package org.bootmoder.kmp

import androidx.compose.ui.window.ComposeUIViewController
import org.bootmoder.kmp.presentation.AppState
import org.koin.mp.KoinPlatform
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject

/**
 * Позволяет выбрать .zip файл прошивки через UIDocumentPickerViewController.
 * Koin-зависимость берётся через GlobalContext (нельзя мешать NSObject с KoinComponent).
 */
private class IosFirmwarePicker : NSObject(), UIDocumentPickerDelegateProtocol {

    private val appState: AppState get() = KoinPlatform.getKoin().get()

    fun present() {
        val zipType = UTType.typeWithIdentifier("public.zip-archive")
            ?: UTType.typeWithIdentifier("com.pkware.zip-archive")
        val types: List<UTType> = listOfNotNull(zipType)

        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = types,
            asCopy = true
        )
        picker.delegate = this
        picker.allowsMultipleSelection = false
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        val path = url.path ?: return
        val name = url.lastPathComponent ?: path.substringAfterLast("/")
        appState.setFirmware(path, name)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        // пользователь отменил — ничего не делаем
    }

    @Suppress("DEPRECATION")
    private fun topViewController(): UIViewController? {
        var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (vc?.presentedViewController != null) {
            vc = vc.presentedViewController
        }
        return vc
    }
}

private val firmwarePicker = IosFirmwarePicker()

fun MainViewController() = ComposeUIViewController {
    App(
        onPickFirmware = { firmwarePicker.present() }
    )
}
