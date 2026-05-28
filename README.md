# SimpleLector

SimpleLector is a Kotlin Multiplatform reader for Android, Windows, and Linux built with Compose Multiplatform.

It is designed as a lightweight personal library app for books, documents, and comics, with a shared reading experience across platforms.

## Downloads

[![Android on Google Play](https://img.shields.io/badge/Android-Google_Play-34A853?style=for-the-badge&logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=com.lextrack.simplelector)
[![Windows and Linux on itch.io](https://img.shields.io/badge/Windows_%26_Linux-itch.io-FA5C5C?style=for-the-badge&logo=itchdotio&logoColor=white)](https://lextrack.itch.io/simple-lector)

Windows and Linux builds are available on itch.io.

## Features

- Library import from folders
- Reading support for `PDF`, `EPUB`, `TXT`, `Markdown`, `CBZ`, and `CBR`
- Reading progress and last-opened-book restore
- Bookmarks and reader preferences
- Library browsing, search, and folder navigation
- Comic and manga reading with Android-native image acceleration for heavy `CBZ` / `CBR` pages

## Platforms

- Android
- Windows
- Linux

## Screenshots

<p align="center">
  <img src="Captures/1001.png" alt="SimpleLector screenshot 1" width="48%" />
  <img src="Captures/1002.png" alt="SimpleLector screenshot 2" width="48%" />
</p>

<p align="center">
  <img src="Captures/1003.png" alt="SimpleLector screenshot 3" width="48%" />
  <img src="Captures/1004.png" alt="SimpleLector screenshot 4" width="48%" />
</p>

## Requirements

### Android

- Android Studio
- Android SDK
- Android NDK
- CMake

The Android build includes native code for some image-heavy reading paths, so `NDK` and `CMake` should be installed from Android Studio's SDK Manager.

### Desktop

- JDK 11 or newer
- A system supported by Compose Desktop

## Run The Project

### Android

Open the project in Android Studio, connect a device or start an emulator, and run the `androidApp` configuration with the normal `Run` / `Play` button.

You can also build from the terminal:

```bash
./gradlew :androidApp:assembleDebug
```

On Windows:

```powershell
.\gradlew.bat :androidApp:assembleDebug
```

### Desktop

Run on Linux:

```bash
./gradlew :composeApp:run
```

Run on Windows:

```powershell
.\gradlew.bat :composeApp:run
```

## Notes

- Android is the most optimized target right now for heavy comic and manga reading.
- `CBR` support depends on the archive being readable by the bundled RAR library.
- iOS is not configured in this repository.

## Project Structure

- `androidApp`: Android launcher app
- `composeApp`: shared app code for Android and Desktop

## License

This project is licensed under the MIT License. See the `LICENSE` file for details.
