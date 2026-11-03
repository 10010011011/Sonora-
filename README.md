# Sonora: GitHub upload bundle

Everything in this archive belongs at the root of the repository (flat, no sub-folders).

1. Extract the archive and upload its contents to the repository root.
2. Add the workflow separately: in GitHub choose Add file > Create new file, name it `.github/workflows/android.yml`, and paste in the contents of `android.yml` (delivered next to this archive).
3. Open Actions and run "Build Android APK", or push to main.
4. Download the `sonora-debug-apk` artifact from the run; it contains `Sonora-<branch>-debug.apk`.
