# Project agent instructions

## LSPosed module deployment

- Disable Android Studio deployment optimization when installing this module.
- Prefer `.\gradlew.bat :app:installDebug` for device installation so LSPosed receives a complete updated APK.
- Do not treat an Android Studio optimized deployment as proof that the installed LSPosed module was updated.
- After installing an updated module APK, restart the selected LSPosed scope processes before device validation.
