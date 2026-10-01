# Building Read Me

Requirements: Node >= 22.11, JDK 21, Android SDK with `platforms;android-37.0`,
`build-tools;37.0.0`, `build-tools;36.0.0` (the Android Gradle plugin's own default),
`ndk;27.1.12297006`, `cmake;3.22.1`.

```bash
yes | sdkmanager --licenses >/dev/null
sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "build-tools;36.0.0" "ndk;27.1.12297006" "cmake;3.22.1"
npm ci
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > android/local.properties
npm run build:release
# output: android/app/build/outputs/apk/release/app-release.apk
```

Gates: see AGENTS.md "Quality gates". Script tests: `npm run test:scripts`. F-Droid-style checks: `scripts/fdroid-scan.sh`.
