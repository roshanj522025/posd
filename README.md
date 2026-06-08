# ProotDroid

An Android application that runs **Alpine Linux** inside [proot](https://github.com/proot-me/proot)
with a built-in terminal emulator and a VNC viewer for graphical desktop access —
no root required.

```
┌─────────────────────────────────┐
│          ProotDroid App         │
│  ┌──────────┐  ┌─────────────┐  │
│  │ Terminal │  │  VNC Viewer │  │
│  │  (shell) │  │  (desktop)  │  │
│  └────┬─────┘  └──────┬──────┘  │
│       │  stdin/stdout │  RFB    │
│  ┌────▼───────────────▼──────┐  │
│  │       ProotService        │  │
│  │   (foreground service)    │  │
│  └────────────┬──────────────┘  │
│               │                 │
│  ┌────────────▼──────────────┐  │
│  │     proot (static bin)    │  │
│  │   Alpine Linux rootfs     │  │
│  │   Xvnc :1 / port 5901    │  │
│  │   Openbox window manager  │  │
│  └───────────────────────────┘  │
└─────────────────────────────────┘
```

---

## Features

| Feature | Details |
|---|---|
| **Distro** | Alpine Linux 3.19 minirootfs |
| **Container** | proot (no kernel module, no root) |
| **GUI** | Xvnc (TigerVNC) + Openbox WM |
| **Terminal** | Built-in scrollable terminal with quick-keys |
| **VNC** | RFB 3.8 client, VNC Auth, raw encoding |
| **ABI support** | arm64-v8a · armeabi-v7a · x86_64 |
| **Min Android** | API 24 (Android 7.0) |

---

## Project Structure

```
ProotDroid/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/prootdroid/
│       │   ├── proot/
│       │   │   ├── BootstrapManager.kt   ← download + extract Alpine
│       │   │   ├── ProotSession.kt       ← builds proot command, spawns process
│       │   │   └── ProotService.kt       ← foreground service keeping proot alive
│       │   ├── terminal/
│       │   │   └── TerminalFragment.kt   ← terminal UI ↔ shell stdin/stdout
│       │   ├── vnc/
│       │   │   └── VncFragment.kt        ← RFB 3.8 client → ImageView
│       │   └── ui/
│       │       ├── SplashActivity.kt
│       │       ├── SetupActivity.kt      ← first-run installer
│       │       ├── MainActivity.kt       ← tab host (Terminal | VNC)
│       │       └── AboutDialog.kt
│       └── res/
│           ├── layout/   ← XML layouts
│           ├── drawable/ ← vector icons
│           ├── menu/     ← options menu
│           └── values/   ← colors, themes, strings
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## Building

### Prerequisites

- **Android Studio Hedgehog** (2023.1.1) or newer
- **JDK 17** (bundled with Android Studio)
- Android SDK with API 34 installed
- NDK (for proot ABI detection — not compiled from source here)

### Steps

```bash
# 1. Clone / copy this project
git clone https://github.com/yourname/ProotDroid
cd ProotDroid

# 2. Open in Android Studio
# File → Open → select the ProotDroid folder

# 3. Sync Gradle
# Android Studio will prompt to sync; click "Sync Now"

# 4. Build debug APK
./gradlew assembleDebug

# 5. Install on device / emulator
./gradlew installDebug
```

The debug APK will be at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## First-Run Flow

```
Launch app
    │
    ▼
SplashActivity
    │
    ├─ rootfs installed? ──Yes──▶ MainActivity
    │
    No
    ▼
SetupActivity
    │  Downloads proot binary (~1 MB)
    │  Downloads Alpine minirootfs (~5 MB)  
    │  Extracts tar.gz into <filesDir>/alpine-rootfs/
    │  Writes /etc/resolv.conf, /etc/hosts
    │  Creates /init-prootdroid.sh
    │
    ▼
MainActivity  (tab layout)
    ├─ Tab 1: Terminal  → ProotService.openShell()
    └─ Tab 2: VNC       → connects to 127.0.0.1:5901
```

---

## First VNC Connection

On first VNC connect, `init-prootdroid.sh` runs inside Alpine and
**installs the GUI stack** via apk:

```
xvnc · openbox · xterm · xfce4-terminal · dbus · ttf-dejavu
```

This requires an internet connection and takes ~60–120 seconds.
Subsequent launches skip this step (guarded by `/var/.gui_installed`).

**VNC password:** `prootdroid`  
**Display:** `:1` (port 5901, localhost only)  
**Resolution:** 1280×720

---

## Key Classes Explained

### `BootstrapManager`
Downloads the correct Alpine minirootfs tarball and proot static binary
for the device's ABI. Extracts using `/system/bin/tar` (available API 24+)
with a pure-Java fallback. Writes initial configuration files and the
`init-prootdroid.sh` startup script.

### `ProotSession`
Builds and launches the proot command line:
```
proot \
  --rootfs=<filesDir>/alpine-rootfs \
  --bind=/dev --bind=/dev/pts --bind=/proc --bind=/sys \
  --bind=<tmp>:/tmp \
  --kill-on-exit \
  --link2symlink \
  --change-id=0:0 \
  /bin/sh /init-prootdroid.sh
```
`openShell()` opens a separate interactive `/bin/sh -l` for the terminal.

### `ProotService`
Android foreground service that hosts the ProotSession so the Linux
environment keeps running when the user switches apps. Binds to
`MainActivity` via `ProotBinder`.

### `VncFragment`
Implements RFB protocol 3.8 from scratch:
- Version handshake → security negotiation (VNC Auth DES) → ClientInit
- ServerInit parses framebuffer dimensions
- Raw encoding: each update decoded to `IntArray` → `Bitmap` → `ImageView`
- Runs in a coroutine, auto-reconnects on error

### `TerminalFragment`
Attaches `OutputStreamWriter` / `BufferedReader` to the shell process
streams. Quick-key bar sends TAB, Ctrl+C, Ctrl+D, cursor keys.
For full VT100 support, replace the `TextView` with the
[Termux TerminalView](https://github.com/termux/termux-app).

---

## Upgrading the VNC Client

The built-in `VncFragment` implements raw encoding only.
For Tight / ZRLE / ZLIB encodings (much faster), replace with:

```kotlin
// build.gradle.kts
implementation("com.github.bVNC:bVNC:3.8.0")
```

Then wrap `bVNC`'s `VncCanvas` inside the fragment.

---

## Customising Alpine

Edit `BootstrapManager.configureAlpine()` to change:
- **Default packages** installed on first GUI boot
- **VNC resolution** (change `-geometry 1280x720`)
- **Window manager** (swap `openbox-session` for `xfce4-session`, `i3`, etc.)
- **VNC password** (change the `vncpasswd` heredoc)

---

## Limitations & Notes

| Item | Note |
|---|---|
| **No kernel modules** | proot uses ptrace; some syscalls may fail |
| **Performance** | ptrace overhead; GUI apps run slower than native |
| **Audio** | Not bridged; add PulseAudio TCP bridge if needed |
| **Clipboard** | Not synced between Android and VNC |
| **SELinux** | On some devices SELinux may block proot; test on real hardware |
| **x86 emulator** | Works on x86_64 AVD; proot x86_64 binary required |

---

## License

MIT — do whatever you like, attribution appreciated.
Alpine Linux, proot, and Openbox are under their respective open-source licenses.
