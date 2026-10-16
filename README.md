# Truth or Dare — Kotlin Android app

The Kotlin rewrite of the Truth or Dare Android app.

## Requirements

- JDK 17
- Android SDK with API 36
- Gradle 8.13+

## Build

```bash
gradle assembleDebug
```

For a signed release, provide the release keystore and set `TORD_STORE_PASSWORD` before running `gradle assembleRelease` or `gradle bundleRelease`.

The app source is in `src/main/kotlin/game/truthordare/MainActivity.kt` and its prompt deck is in `src/main/assets/truth_or_dare_prompts.json`.