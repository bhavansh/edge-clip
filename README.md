# EdgePanel - Secure Clipboard Manager

EdgePanel is an Android utility that provides quick access to your clipboard history through a floating edge overlay. It captures both text and images securely using a background accessibility service and stores them in an encrypted database.

## Features

- **Floating Edge Handle:** Access your clipboard from any app with a simple swipe.
- **Text & Image Support:** Automatically captures text and images copied to the clipboard.
- **Encrypted Storage:** All data is stored locally using **SQLCipher** and **Room**, protected by **Android Keystore**.
- **Privacy First:** No internet permission, no data leaves your device.
- **Modern UI:** Built with Jetpack Compose for the main settings and optimized views for the overlay.

## Installation (Sideloading)

Due to Android's security measures for sideloaded apps, you must follow these steps after installing the APK:

1. **Install** the APK on your device.
2. Go to **Settings** > **Apps** > **EdgePanel**.
3. Tap the **three-dot menu** in the top-right corner.
4. Select **"Allow restricted settings"**.
5. Open the app and grant the **Accessibility** and **Display over other apps** permissions.

## Permissions

- **Accessibility Service:** Required to monitor clipboard changes in the background across apps.
- **Display Over Other Apps:** Required to show the edge handle and panel overlay.
- **Notification Permission:** Required (on Android 13+) for the persistent service that prevents the app from being killed by the system.

## Build Instructions

To build the APK yourself:

1. Create a `keystore.properties` in the root directory.
2. Run `./gradlew assembleRelease`.
3. The APK will be available in `app/build/outputs/apk/release/`.

## Author
Bhavansh Gupta
