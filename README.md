# ZX Tape Loader (Android)

Android app that wraps the single-file **ZX Spectrum Mobile Tape & Image Loader / ASM Converter v1.5.2.9** web app in a WebView.
Everything runs offline on the device, and no internet permission is requested.

## Features
- Full web app (Spectrumizer, BASIC Editor, ASM Converter, Tape Play, WAV Decode, Extras, Manual) from `app/src/main/assets/index.html`
- `.TAP` / `.TZX` / `.WAV` / listing downloads are saved to the phone's **Downloads** folder
- File pickers work for images, `.tap` and `.wav` files
- Custom launcher icon and full-screen splash image
- UI sounds and Web Audio tape playback work without an extra tap

## Build the APK on GitHub (no Android Studio needed)
1. Create a new GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab. **Build Android APK** runs on every push, or choose **Run workflow**.
3. When it finishes, open the run and download the **ZXTapeLoader-debug-apk** artifact. Unzip it and install `app-debug.apk` on your phone.

You may need to allow "Install unknown apps" for your browser or file manager.

## Build in Android Studio
1. **File > Open**, then pick this folder and let Gradle sync.
2. **Run** on a device or emulator, or use **Build > Build APK(s)**.

Needs JDK 17. The project uses Gradle 8.9, Android Gradle Plugin 8.7.3 and Kotlin 2.0.21.

## Supported Android versions
- **Minimum SDK: 21** (Android 5.0 Lollipop)
- **Target SDK: 27** (Android 8.1 Oreo), the newest version the app is tuned for
- Android does not enforce a maximum SDK: the installer ignores `maxSdkVersion` in `<uses-sdk>`, so the APK will still install on newer phones (Android 9 and up), where it is expected to work but is not the tested target.
- On Android 6-9 the app asks for storage permission the first time you save a file. On Android 10 and up no permission is needed.
- Needs an up-to-date Android System WebView (updated through Google Play); on Android 5 this is the usual cause of display problems. Android Studio may offer to upgrade the plugin versions.

## Updating the web app
Replace `app/src/main/assets/index.html` with a newer build of the HTML file (keep the name `index.html`). Bump `versionCode` and `versionName` in `app/build.gradle.kts`.

## Icons and splash
- Launcher icons: `app/src/main/res/mipmap-*/ic_launcher.png` (and `_round`). A 512x512 version for the Play Store is in `play-store-icon-512.png`.
- Splash image: `app/src/main/res/drawable-nodpi/splash.png`. It shows for about 1.4 seconds, then fades out.

## Release signing
The workflow builds a **debug** APK, which is signed with the standard debug key and installs fine. For Google Play you would add a release keystore and signing config (and keep the keystore out of the repo).
