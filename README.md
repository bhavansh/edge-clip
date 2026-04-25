# 🛡️ EdgeClip - Secure & Smart Clipboard Manager

EdgeClip is a powerful, privacy-first clipboard manager for Android that puts your history just a swipe away. Access text, images, and smart content instantly through a sleek edge overlay, all while keeping your data locally encrypted.

---

## ✨ Key Features

### 🚀 Instant Edge Access
Swipe the customizable edge handle from any app to access your history. No more switching apps just to find that link you copied 5 minutes ago.

### 🧠 Smart Content Detection
EdgeClip automatically recognizes what you copy:
- **🔗 URLs:** Open links directly in your browser.
- **📞 Phone Numbers:** One-tap to dial.
- **🔑 OTPs:** Automatically isolated and highlighted in **Orange** for quick 2FA.
- **🖼️ Images:** Previews and copies image clips seamlessly.

### 🛡️ Uncompromising Privacy
- **Encrypted Storage:** Everything is stored in an AES-256 encrypted database (SQLCipher) protected by the Android Keystore.
- **Offline by Design:** No Internet permission required. Your data never leaves your device.
- **Stealth Mode:** Automatically hides the edge handle in banking apps, games, or fullscreen videos.
- **Pause Mode:** Toggle clipboard monitoring with a single tap when handling sensitive info.

### 📊 Advanced Management
- **Search & Filter:** Find clips instantly using the search bar or category filters (Links, Images, OTPs).
- **Storage Insights:** Monitor how much space your history is using.
- **Custom Retention:** Automatically cleanup old clips based on your preferred time or count limits.

---

## 📸 Screenshots

| 1. Edge Panel Grid | 2. Smart History | 3. Secure Settings |
|:---:|:---:|:---:|
| ![Edge Panel](https://via.placeholder.com/300x600?text=Edge+Panel+Grid) | ![History](https://via.placeholder.com/300x600?text=Smart+History+Filters) | ![Settings](https://via.placeholder.com/300x600?text=Privacy+Settings) |

---

## 🛠️ Installation & Setup

EdgeClip requires specific permissions to function correctly on modern Android versions:

1. **Install the APK.**
2. **Enable Restricted Settings:** Go to *Settings > Apps > EdgeClip*, tap the three-dot menu (top-right), and select **"Allow restricted settings"**.
3. **Grant Permissions:**
   - **Accessibility:** To monitor clipboard events securely.
   - **Display Over Other Apps:** To show the floating edge handle.
   - **Notifications:** To ensure the service remains active in the background.

---

## 🏗️ Build Instructions

To build a release version:
1. Ensure you have a `keystore.properties` in the root directory.
2. Run: `./gradlew assembleRelease`
3. Find your signed APK in `app/build/outputs/apk/release/`.

---

## 👨‍💻 Author
**Bhavansh Gupta**

---
*Developed with a focus on security, usability, and speed.*
