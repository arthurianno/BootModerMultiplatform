# BootModer Multiplatform

Kotlin Multiplatform (KMP) версия приложения BootModer для управления BLE-устройствами.

## Целевые платформы

| Платформа | Статус |
|-----------|--------|
| Android   | ✅ Полностью поддерживается |
| iOS       | 🔄 В разработке (Этап 8) |

## Структура проекта

```
BootModerMultiplatform/
├── composeApp/          ← UI-слой (Compose Multiplatform)
│   └── src/
│       ├── androidMain/ ← MainActivity, BootModerApplication (Koin init)
│       ├── commonMain/  ← Compose UI (App.kt и будущие экраны)
│       └── iosMain/     ← MainViewController для iOS
│
├── shared/              ← Общая бизнес-логика (Clean Architecture)
│   └── src/
│       ├── commonMain/  ← Domain: entities, repositories, use cases, Logger
│       ├── androidMain/ ← Android-реализации + Koin Android модуль
│       └── iosMain/     ← iOS-реализации + Koin iOS модуль
│
└── iosApp/              ← Xcode-проект (точка входа для iOS)
```

## Архитектура shared-модуля

```
commonMain/
├── domain/entity/
│   ├── BleDevice          — BLE-устройство (address, name, rssi)
│   ├── DeviceInfo         — Информация об устройстве (fw/hw version, battery)
│   ├── ScanMode           — Режим сканирования (LOW_POWER / BALANCED / LOW_LATENCY)
│   ├── ConnectionState    — Состояние GATT-соединения
│   └── DfuProgress        — Прогресс обновления прошивки
├── domain/repository/
│   ├── BleRepository      — Интерфейс BLE-операций
│   └── DeviceWorkingRepository — Интерфейс работы с данными устройства
├── domain/usecase/
│   ├── ScanDevicesUseCase
│   ├── StopScanUseCase
│   ├── ConnectDeviceUseCase
│   ├── DisconnectDeviceUseCase
│   ├── ObserveConnectionStateUseCase
│   ├── SendCommandUseCase
│   ├── GetDeviceInfoUseCase
│   ├── SaveDevicePasswordUseCase
│   ├── GetDevicePasswordUseCase
│   ├── StartDfuUseCase
│   └── ObserveDfuProgressUseCase
├── util/Logger            — expect/actual логгер
└── di/SharedModule        — Koin-модуль use cases

androidMain/
├── data/repository/
│   ├── AndroidBleRepository         ← TODO Этап 7 (Nordic BLE SDK)
│   └── AndroidDeviceWorkingRepository
└── di/AndroidSharedModule

iosMain/
├── data/repository/
│   ├── IosBleRepository             ← TODO Этап 8 (CoreBluetooth)
│   └── IosDeviceWorkingRepository
└── di/IosSharedModule + initKoin()
```

## DI (Koin)

### Android
```kotlin
// BootModerApplication.kt (инициализируется автоматически)
startKoin {
    androidContext(this@BootModerApplication)
    modules(sharedModule, androidSharedModule)
}
```

### iOS (Swift)
```swift
// iOSApp.swift
import Shared
IosSharedModuleKt.initKoin()
```

## Зависимости

| Библиотека | Версия | Область |
|-----------|--------|---------|
| Kotlin | 2.3.20 | all |
| Compose Multiplatform | 1.10.3 | commonMain |
| Koin | 4.0.0 | commonMain + androidMain |
| kotlinx-coroutines | 1.9.0 | commonMain |
| multiplatform-settings | 1.2.0 | commonMain |
| Nordic BLE SDK | 2.7.5 | androidMain only |
| Nordic DFU SDK | 2.4.1 | androidMain only |

## Сборка

### Android APK
```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :composeApp:assembleDebug
```

### iOS Framework
```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :shared:assemble
```

> **Совет:** Чтобы не указывать `DEVELOPER_DIR` каждый раз:
> ```bash
> sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
> ```

## Дорожная карта миграции

| Этап | Задача | Статус |
|------|--------|--------|
| 1 | Обновление Gradle / libs.versions.toml | ✅ Готово |
| 2 | Создание модуля shared | ✅ Готово |
| 3 | Перенос домена в commonMain | ✅ Готово |
| 4 | expect/actual для Logger | ✅ Готово |
| 5 | Хранилище (multiplatform-settings) | ✅ Готово |
| 6 | Замена Hilt → Koin | ✅ Готово |
| 7 | Android BLE (Nordic SDK) реализация | 🔜 Неделя 2 |
| 8 | iOS BLE (CoreBluetooth) реализация | 🔜 Неделя 3 |
| 9 | ViewModel → KMP lifecycle | 🔜 Неделя 4 |
| 10 | Compose Multiplatform UI | 🔜 Неделя 4-5 |
| 11 | iOS DFU (iOSDFULibrary) | 🔜 Неделя 6 |
