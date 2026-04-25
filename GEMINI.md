# EdgeClip - Project Context

EdgeClip is a secure, privacy-focused Android clipboard manager. It provides a floating "Edge Clip" overlay for quick access to clipboard history, including text, images, and smart content types.

## Core Features & Logic

- **Smart Clipboard Monitoring:** Captures text and image clips in the background via `ClipboardAccessibilityService`.
- **Content Intelligence:** Automatically detects and categorizes content:
    - **URL:** Web links (Action: Open in browser).
    - **PHONE:** Phone numbers with support for spaces/dashes (Action: Dial).
    - **OTP:** 4-8 digit codes (Action: Copy digits only). OTPs are visually highlighted in **Bold Orange (#FF9500)**.
- **Privacy & Stealth:**
    - **Pause Mode:** Disable monitoring globally.
    - **App Blacklist:** Automatically hides the sidebar handle when blacklisted apps (e.g., banking, password managers) are in focus.
    - **Stealth Mode:** Automatically hides during fullscreen apps (detected via window focus and status bar visibility).
- **Dual UI Access:**
    - **Edge Panel:** A 2x3 button grid at the top of the sidebar for instant filtering (ALL, PAUSE, URL, IMAGE, OTP, PHONE).
    - **History Screen:** Full management UI with search, storage insights (MB used), and horizontal filter chips.
- **Secure Storage:** All data is encrypted with **SQLCipher** and **Room**, using keys stored in the **Android Keystore**.

## Architecture

- **UI Layer:**
    - `MainActivity.kt`: Settings and configuration built with **Jetpack Compose**.
    - `EdgeClipService.kt`: Manages the system overlay using traditional **Android Views** and `PanelUIManager`.
- **Service Layer:**
    - `EdgeClipService`: Foreground service holding the overlay state and "Focus Window" hack.
    - `ClipboardAccessibilityService`: Monitors clipboard and window focus events.
- **Data Layer:**
    - `ClipDatabase`: Version 3. Encrypted Room DB.
    - `ClipRepository`: Handles business logic for content detection and "Evict-on-Insert" cleanup.

## Key Technologies

- **Language:** Kotlin
- **UI:** Jetpack Compose & Custom Programmatic Views
- **Database:** Room + SQLCipher + AndroidX Security
- **Concurrency:** Kotlin Coroutines & Flow

## Important Technical Learnings

### 1. Clipboard Access (Android 10+)
To access the clipboard from the background, the app uses an Accessibility Service combined with a "Focus Window" hack in `EdgeClipService`. The system must think the app has window focus to allow clipboard reads.

### 2. Room Schema (v3)
The schema was updated to include `subtype` for smart actions. Destructive migration is enabled for dev simplicity, but versioning must be strictly incremented.

### 3. Minification (R8)
Release builds require specific ProGuard rules for SQLCipher JNI and Room's generated classes. See `app/proguard-rules.pro`.

### 4. Restricted Settings
Sideloaded APKs must have "Allow restricted settings" enabled in System App Info before Accessibility can be turned on.
