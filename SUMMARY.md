# PHASE — Game & Build Summary

A complete, working Android game called **PHASE** with full source and a
GitHub Actions workflow that produces a signed, installable APK.

---

## The Game

**PHASE** is a 2D dimension-shifting arcade game.

- The world exists in two parallel planes: **LIGHT** (cyan/purple) and
  **SHADOW** (red/orange).
- You can only touch and be touched by entities in your **current** plane.
- Switch planes with a quick tap to dodge enemies, collect essence, and
  survive the rhythm.
- The world has a **procedural beat** that gets faster as you level up.
- On every Nth beat, the world **forces a phase shift** — you must adapt.

### Controls
- **Drag** — move the player (direct positional control).
- **Quick tap** — phase shift.
- The forced rhythm shifts happen on a timer visible in the screen edge glow.

### Features
- Two fully-simulated parallel dimensions
- Procedural platforms, enemies, and orbs (different per level)
- Particle system (phase burst, trails, explosions, orb pickups)
- Procedurally-synthesized sound effects via `AudioTrack`
  - Beat tick, phase-shift sweep, orb pluck, level-up arpeggio, game-over arpeggio
- Haptic feedback on phase shift / orb pickup / dash / level up / death
- Persistent high score and mute toggle (in `SharedPreferences`)
- Edge vignette that pulses with the beat, tinted to the current phase
- Trail effect, glow effects, dimensional ribbons, animated stars
- Combo system (visible in HUD with timer bar)

---

## Project Layout

```
AndroidApp/
├── README.md
├── SUMMARY.md                    # this file
├── build.gradle.kts              # top-level Gradle
├── settings.gradle.kts
├── gradle.properties
├── gradlew, gradlew.bat
├── gradle/wrapper/               # Gradle wrapper (committed)
├── workflow/
│   ├── README.md                 # instructions to move build.yml into .github/workflows/
│   └── build.yml                 # the CI workflow
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/phase/game/
        │   ├── MainActivity.kt   # 41 lines — Activity hosting the game view
        │   ├── GameView.kt       # 1168 lines — entire game engine
        │   └── SoundEngine.kt    # 169 lines — procedural audio
        └── res/
            ├── drawable/         # launcher assets
            ├── mipmap-*/         # launcher icons
            ├── values/           # colors, strings, themes
            └── xml/              # backup rules
```

---

## How to Build the APK

### Option A: Let GitHub Actions do it (recommended)

1. The integration token used by the AI agent does not have `workflows`
   scope, so the workflow file was committed in `workflow/build.yml` instead
   of `.github/workflows/build.yml`. **You need to move it.**

2. On your local checkout (or via the GitHub web UI), move the file:
   ```bash
   git mv workflow/build.yml .github/workflows/build.yml
   git commit -m "ci: enable PHASE Android build workflow"
   git push origin main
   ```

3. Every push to `main` and every tag will trigger the workflow, which will:
   - Set up JDK 17 and the Android SDK
   - Generate a fresh signing keystore
   - Build a signed release APK
   - Upload it as a workflow artifact named `PHASE-APK`
   - Create a draft GitHub Release with the APK attached

4. Download the APK from the **Actions** tab → run → **Artifacts**, or from
   the **Releases** page once you publish the draft.

### Option B: Build locally

1. Install **Android Studio Hedgehog** (or newer).
2. Open the project and let it sync.
3. From the menu: **Build → Generate Signed Bundle / APK** → choose **APK**,
   follow the wizard, and you're done.

Or from the command line:
```bash
./gradlew assembleRelease
# APK lands at app/build/outputs/apk/release/app-release.apk
```

The release config will sign with whatever keystore is found in
`keystore.properties` at the project root. If that file doesn't exist, the
build falls back to the debug signing config so it still produces a
runnable APK for local testing.

---

## Tech Stack

- **Language:** Kotlin 1.9.24
- **UI:** Pure Android Canvas, custom `View`, no game engine
- **Audio:** `android.media.AudioTrack` (procedural PCM synthesis)
- **Min SDK:** 21 (Android 5.0 Lollipop)
- **Target SDK:** 34 (Android 14)
- **Build:** Gradle 8.7, Android Gradle Plugin 8.5.2
- **Dependencies:** AndroidX core-ktx, appcompat, Material Components

No external game engine, no OpenGL, no 3D math, no asset files.
Everything is generated programmatically.

---

## License

Provided as-is. Have fun.
