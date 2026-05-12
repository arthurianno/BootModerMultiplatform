package org.bootmoder.kmp

import android.app.Application
import org.bootmoder.kmp.shared.di.androidSharedModule
import org.bootmoder.kmp.shared.di.sharedModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * Application-класс для инициализации Koin DI.
 *
 * Подключение в AndroidManifest.xml:
 *   android:name=".BootModerApplication"
 */
class BootModerApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger(Level.DEBUG)
            androidContext(this@BootModerApplication)
            modules(
                sharedModule,        // use cases (commonMain)
                androidSharedModule, // репозитории + settings (androidMain)
                appModule            // ViewModel + SystemChecker (androidMain)
            )
        }
    }
}

