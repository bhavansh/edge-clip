# EdgePanel - Project Context

EdgePanel is a secure Android clipboard manager that provides a floating "Edge Panel" overlay for quick access to clipboard history, including both text and images. It features encrypted storage and specialized techniques to ensure reliable clipboard access across various Android distributions (including MIUI).

## Project Overview

- **Core Functionality:**
    - **Clipboard Monitoring:** Captures text and image clips in the background using an Accessibility Service.
    - **Edge Panel Overlay:** A draggable handle at the edge of the screen that expands into a scrollable history panel.
    - **Secure Storage:** All clipboard items are stored in an encrypted Room database.
    - **Image Support:** Captures and displays image clips, storing them as compressed JPEGs in internal storage.
    - **MIUI Compatibility:** Uses a "Focus Window" hack to gain temporary window focus for clipboard access on restricted systems.

## Architecture

- **UI Layer:**
    - `MainActivity.kt`: Settings and configuration UI built with **Jetpack Compose**.
    - `EdgePanelService.kt`: Manages the system overlay window using traditional **Android Views** and a custom `GestureScrollView`.
    - `ClipboardProxyActivity.kt`: A transparent activity used for certain clipboard operations if needed.
- **Service Layer:**
    - `EdgePanelService`: The primary foreground service managing the overlay and the "Focus Window".
    - `ClipboardAccessibilityService`: Monitors clipboard events and captures content.
- **Data Layer:**
    - `ClipDatabase`: Room database encrypted with **SQLCipher**.
    - `ClipRepository`: Orchestrates data operations between the services and the DAO.
    - `ClipEntity`: Represents a single clipboard item (Text or Image).
- **Communication:**
    - Uses Kotlin **Flow** to observe database changes and update the overlay UI in real-time.

## Key Technologies

- **Language:** Kotlin
- **UI Framework:** Jetpack Compose (Settings) & Custom Views (Overlay)
- **Database:** Room with SQLCipher (Encryption)
- **Security:** AndroidX Security Crypto (for storing DB passphrases)
- **Background Tasks:** Kotlin Coroutines & Flow
- **Android APIs:** Accessibility Service, WindowManager (System Overlay)

## Building and Running

- **Build APK:** `./gradlew assembleDebug`
- **Install on Device:** `./gradlew installDebug`
- **Run Tests:** `./gradlew test` (Unit tests) or `./gradlew connectedAndroidTest` (Instrumentation tests)
- **Release Build:** Requires `keystore.properties` to be configured (see `app/build.gradle.kts`).

## Development Conventions

- **Overlay UI:** The overlay is built using programmatic view construction in `EdgePanelService` for performance and precise control over the `WindowManager`.
- **Data Encryption:** Never store sensitive information in plain text. Always use the `ClipRepository` which handles encryption via SQLCipher.
- **Clipboard Access:** On modern Android versions, clipboard access from the background is restricted. Always use `ClipboardAccessibilityService` in conjunction with the "Focus Window" strategy in `EdgePanelService`.
- **Memory Management:** Image clips are capped at 50 items by default. The `ClipRepository` handles the eviction of old files and database entries.

## Important Learnings & Troubleshooting

### 1. Release Build & Minification (R8/ProGuard)
- **Problem:** Release builds with `isMinifyEnabled = true` would crash or fail to install because R8 stripped critical Room and SQLCipher classes.
- **Solution:** Added explicit `-keep` rules for Room, SQLCipher, and AndroidX Security. Specifically, JNI handles in SQLCipher (`nativeHandle`) and Room's generated implementation classes must be preserved.
- **Verification:** Always verify release artifacts with `jarsigner -verify` to ensure they are actually signed. If `keystore.properties` path is incorrect, the build might succeed but produce an unsigned (uninstallable) APK.

### 2. Android 13+ "Restricted Settings"
- **Problem:** Sideloaded APKs (e.g., via WhatsApp or File Manager) have sensitive permissions like "Accessibility" grayed out by default.
- **Solution:** Users must manually "Allow restricted settings" for the app.
    1. Open **Settings** > **Apps** > **EdgePanel**.
    2. Tap the **three-dot menu** (top right).
    3. Select **Allow restricted settings**.
    4. Go back to Accessibility settings to enable the service.

### 3. Service Persistence
- **Foreground Service:** To prevent the system from killing the overlay when swiped from recent apps, the service must be a Foreground Service with a persistent notification and return `START_STICKY` in `onStartCommand`.

