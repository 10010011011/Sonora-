# Sonora: GitHub upload bundle

Everything in this archive belongs at the root of the repository (flat layout).

1. Extract the archive and upload its contents to the repository root.
2. Create `.github/workflows/android.yml` from the separate `android.yml` file.
3. Open Actions and run "Build Android APK", or push to main.
4. Download the `sonora-debug-apk` artifact; it contains `Sonora-v<version>-debug.apk`.
