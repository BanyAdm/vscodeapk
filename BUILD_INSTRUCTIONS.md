# VS Code Android - Build Instructions

## Overview

This app wraps VS Code's official web build inside a native Android WebView,
with a full bridge to Termux for terminal, filesystem, processes, git, and LSP servers.

**Offline support:** Once built and installed, the app works fully offline.
The VS Code web build is bundled inside the APK. Termux packages are installed
once during setup and work without internet after that.

---

## Prerequisites

- Android Studio (latest stable)
- JDK 17+
- Node.js 18+ and Yarn (for building VS Code web)
- Git
- ~3GB free disk space
- Android device with Termux installed from GitHub (not Play Store)

---

## Part 1 — Build VS Code Web (do this once)

```bash
# 1. Clone VS Code
git clone https://github.com/microsoft/vscode.git
cd vscode

# 2. Install dependencies
yarn

# 3. Build the web target
yarn gulp vscode-web

# 4. The output is in: ./out-vscode-web/
#    Copy it into your Android project assets:
cp -r out-vscode-web/* /path/to/VSCodeAndroid/app/src/main/assets/vscode/
```

The `assets/vscode/` folder should contain `index.html` and all VS Code web files.
This is what gets bundled into the APK and served offline.

---

## Part 2 — Set Up Termux Module

The terminal uses Termux's own `terminal-view` library for rendering.
You need to include it as a local module.

```bash
# 1. Clone termux-app (from GitHub, not Play Store version)
git clone https://github.com/termux/termux-app.git
cd termux-app

# 2. Copy the terminal-view and terminal-emulator modules into your project
cp -r terminal-view /path/to/VSCodeAndroid/
cp -r terminal-emulator /path/to/VSCodeAndroid/

# 3. Add to settings.gradle:
#    include ':terminal-view'
#    include ':terminal-emulator'

# 4. In app/build.gradle, replace the fileTree line with:
#    implementation project(':terminal-view')
#    implementation project(':terminal-emulator')
```

---

## Part 3 — Add Missing Drawable Resources

You need to create placeholder drawables for the icon references.
In `app/src/main/res/drawable/`, create these (can be simple vector drawables):

- `ic_launcher.png` — app icon (use VS Code's icon)
- `ic_add.xml` — plus/add icon
- `ic_close.xml` — X/close icon
- `ic_close_small.xml` — small X for terminal tabs
- `ic_maximize.xml` — maximize icon
- `ic_folder.xml` — folder icon
- `ic_file_generic.xml` — generic file icon
- `ic_file_java.xml`, `ic_file_js.xml`, `ic_file_python.xml` — language icons
- `ic_file_html.xml`, `ic_file_css.xml`, `ic_file_json.xml`
- `ic_file_markdown.xml`, `ic_file_shell.xml`, `ic_file_xml.xml`, `ic_file_image.xml`
- `ic_new_file.xml`, `ic_new_folder.xml`
- `ic_refresh.xml`, `ic_home.xml`
- `ic_terminal.xml`
- `file_item_selector.xml` — state list drawable for file row highlight
- `terminal_tab_bg.xml` — terminal tab background
- `terminal_tab_active_bg.xml` — active terminal tab background
- `scrollbar_thumb.xml` — scrollbar thumb
- `splash_background.xml` — splash screen background

All icons can be sourced from Material Design Icons or VS Code's icon theme.

---

## Part 4 — Build the APK

```bash
# In Android Studio:
# Build → Build Bundle(s) / APK(s) → Build APK(s)

# Or via command line:
cd VSCodeAndroid
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

---

## Part 5 — Install on Device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or transfer the APK to your device and install manually.

---

## Part 6 — First Run Setup on Device

1. Open the app
2. If Termux is not installed, you'll be guided to install it from GitHub
3. Once Termux is installed, tap **"Run Setup in Termux"**
4. In Termux, the setup script will:
   - Install `socat`, `git`, `nodejs`, `python`, etc.
   - Create the bridge server scripts
   - Start the bridge on ports 9999 (commands) and 10000 (terminal)
5. Come back to the app and tap **"Setup is done → Continue"**

---

## How It Works

```
VS Code Web (bundled in APK assets)
        ↕
    WebView (hardware accelerated)
        ↕
android-bridge.js (injected after VS Code loads)
  - Hooks terminal creation
  - Registers custom FileSystemProvider
  - Patches process.env with Termux paths
        ↕
JavascriptBridge.java (@JavascriptInterface)
  - Handles all calls from JS
        ↕
TermuxBridge.java
  - Port 9999: sync/async command execution
  - Port 10000: interactive terminal sessions
        ↕
Termux (bash, git, node, python, apt packages...)
```

---

## Offline Usage

After the first setup, everything works offline:

| Feature | Offline? |
|---|---|
| Code editing | ✅ Fully offline (bundled) |
| Syntax highlighting | ✅ Fully offline |
| File explorer | ✅ Fully offline |
| Terminal | ✅ Fully offline (Termux is local) |
| Git | ✅ Offline for local repos |
| npm/pip/apt packages | ❌ Need internet to install new packages |
| Extensions (web) | ✅ If already installed in VS Code |
| LSP / IntelliSense | ✅ After language servers installed once |

---

## Troubleshooting

**"Could not connect to Termux bridge"**
- Open Termux and run: `bash ~/.vscode-android/start.sh`
- Check logs: `cat ~/.vscode-android/logs/*.log`

**Terminal not appearing**
- Make sure Termux has the `com.termux.permission.RUN_COMMAND` permission enabled
- In Termux: Settings → Allow external apps

**VS Code not loading**
- Make sure `assets/vscode/index.html` exists
- Check WebView console (DevTools via `chrome://inspect`)

**Files not saving**
- Make sure the app has storage permission
- For Termux home dir, permissions should work automatically

---

## Notes on VS Code Build Size

The VS Code web build is large (~400-600MB uncompressed).
The final APK will be large. To reduce size:

```bash
# In vscode build, you can strip unused languages:
# Edit build/gulpfile.vscode.web.js to exclude language packs you don't need
```

Consider using Android App Bundles instead of APKs for distribution,
as they compress assets more aggressively.
