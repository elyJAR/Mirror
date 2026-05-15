# Development Notes

## Environment Constraints

- **Java SDK/JDK**: Not installed locally. **DO NOT** attempt to build the Android project locally using `gradlew` or similar tools.
- **Builds**: Strictly performed on GitHub via GitHub Actions to Github releases (see `.github/workflows`).
- **Android Debugging**: Use `C:\platform-tools\adb.exe` for debugging.
- **Path to ADB**: `C:\platform-tools` is available in the environment.

## Project Structure

- `app/`: Main Android application.
- `mirror-stream/`: Core streaming logic library.
- `receiver-pc/`: PC-side receiver (Electron/TypeScript).
