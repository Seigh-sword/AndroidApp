# PHASE — A Mind-Bending Dimensional Android Game

> **PHASE** is a unique two-dimensional Android game where the world exists in two
> parallel planes — **LIGHT** and **SHADOW**. You can only see and touch one at a
> time. Tap to **phase-shift** between them, dodge enemies that only exist in your
> current plane, collect shimmering essence, and survive an ever-accelerating
> procedural rhythm.

## 🎮 How to Play

- **Drag your finger** to move your character.
- **Tap the LEFT third** of the screen to **dash** in that direction (with brief
  invulnerability).
- **Tap the CENTER** to **phase-shift** between LIGHT and SHADOW dimensions.
  (This costs a bit of phase energy, which refills over time and on orb pickup.)
- **Survive** as long as possible. Every level speeds up the rhythm and
  forces more frequent dimensional shifts. Orbs in the matching dimension refill
  your energy and pump your combo.
- The world has a **procedural beat** — you'll feel it pulsing through the
  visuals and through your device's haptic feedback.

## ✨ Features

- 🌌 **Two fully-simulated parallel dimensions** with their own platform sets,
  enemies, and collectible orbs.
- 🎼 **Procedural rhythm engine** — the game gets faster and more intense
  the longer you survive, with forced phase shifts on certain beats.
- 💥 **Particle system** — dimensional shifts, dashes, orb pickups, and death
  all spawn unique particle effects.
- 📳 **Haptics** — light haptic feedback on phase shift, dash, orb, and death.
- 🌟 **Polished visuals** — animated stars, dimensional ribbons, glowing
  platforms, dual-themed character.
- 💾 **Persistent high score** with `SharedPreferences`.
- 🔇 **Mute toggle** (haptics + persisted).
- 📱 **Lightweight native Android** (Kotlin + Canvas, no game engine, no
  OpenGL, no external dependencies beyond AndroidX/Material).

## 🏗️ Building

This is a **vanilla Android Kotlin project**. The `app/` module compiles to a
single APK; no game engine is required.

### Build locally
1. Install **Android Studio Hedgehog (2023.1.1)** or newer.
2. Open the project in Android Studio and let it sync.
3. Press **Run** to install on a device or emulator (minSdk 21, targetSdk 34).

### Build from the command line
```bash
./gradlew assembleRelease
```

### CI / automatic APK
Every push to `main`, every tag, and every pull request triggers
`.github/workflows/build.yml`, which:

1. Sets up JDK 17 + Android SDK 34.
2. Generates a fresh signing keystore.
3. Runs `./gradlew assembleDebug` (sanity check) and `assembleRelease`
   (signed APK).
4. Uploads the resulting `PHASE-*.apk` as a workflow artifact.
5. Creates a **draft GitHub Release** with the APK attached.

You can grab the latest APK from the Actions run page (artifact `PHASE-APK`)
or from the Releases page of the repository.

## 🗂️ Project Layout

```
app/
  src/main/
    AndroidManifest.xml
    java/com/phase/game/
      MainActivity.kt        # Activity that hosts the game view
      GameView.kt            # All gameplay + rendering lives here
    res/
      drawable/              # Launcher background + foreground
      mipmap-*/              # Launcher icons
      values/                # strings, colors, themes
      xml/                   # backup rules
build.gradle.kts             # top-level Gradle
settings.gradle.kts          # project name + repos
gradle/wrapper/              # Gradle wrapper
.github/workflows/build.yml  # CI build → signed APK
```

## 📜 License

This game source code is provided as-is for the PHASE project. Have fun.
