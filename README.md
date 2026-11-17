# Headless Droid

Runs real, installed Android apps on hidden virtual displays — nine at once, in
a 3×3 portrait grid — and hands control of them to an AI over an MCP endpoint.

There is **no clone**. Headless Droid does not repackage an app, does not
re-sign it, does not install a second copy and does not touch its data. The
phone's own app is started, by the phone, on a virtual display that the phone
itself creates, and then driven from the grid or from MCP.

This is [headless-android](https://github.com/HakaI963/headless-android)'s
successor. That app shipped an app *cloner*; this one drops cloning entirely.

## What is different

| | headless-android (v2) | Headless Droid |
|---|---|---|
| How apps run | repackaged clone with a new package name and a new signature | the original app, as installed, on a virtual display |
| How many at once | one | up to nine, 3×3 grid, portrait cells |
| AI control | broadcast intents with a token read from `/data/local/tmp` | a real MCP endpoint over HTTP/SSE with token auth |
| Screenshots, video | JPEG snapshots | JPEG/PNG shots plus MP4 recording per app, saved to Downloads |
| UI | a clone manager with a dark card list | one screen: the grid, a status strip, an action bar |

## Features

- **Three slots per row, nine apps at once.** Every slot is a portrait display
  of its own; the grid is 1, 2 or 3 columns wide and the app picks the cell
  geometry from the current screen preset.
- **Live preview.** Each tile shows the app's own screen, captured from the
  display's `ImageReader` at roughly 20 fps, with a status dot and a frame
  counter for the slot you are looking at.
- **Input.** Tap, long press, swipe, Back / Home / Recents and text entry are
  addressed to one slot's display, so a tap on tile 3 never lands on the phone's
  own screen.
- **Efficient.** A virtual display is created when a slot starts and released
  when it closes; nothing is running when the grid is empty.
- **MCP endpoint.** `POST /mcp` speaks JSON-RPC 2.0 (initialize, tools/call,
  resources/read, prompts/list) and `GET /mcp/sse` streams server-sent events.
  One token in the `X-Droid-Token` header gates everything.
- **Recording.** `MediaCodec` H.264 plus `MediaMuxer` write an MP4 per slot to
  `Download/HeadlessDroid/videos/`, with the frame rate you asked for.
- **Screenshots.** JPEG or PNG, per app or a composite of the whole grid, into
  `Download/HeadlessDroid/shots/`.

## Requirements

- Android 10 or later (tested on Android 16 / HyperOS).
- [Shizuku](https://shizuku.rikka.app/) running and its permission granted to
  Headless Droid. Shizuku supplies the shell identity that starts an activity
  on another display and injects input addressed to one display.
- The phone screen stays on while the grid is running. HyperOS suspends virtual
  displays together with the phone screen, so Headless Droid holds a wake lock
  while any app is running. There is no way around a secure keyguard.

## Install

```sh
# From a release, or build it yourself:
./scripts/build-apk.sh          # or: ./gradlew assembleDebug
adb install -r build/local/headless-droid-debug.apk
```

Then open **Headless Droid**, tap the status strip where it says
*Shizuku required*, and grant the permission. When the dot turns green the
grid is ready.

## Use

1. Tap **+** (or the *Add app* button on the empty grid) and pick an app.
2. The app opens on its own display, and its screen appears in the next free
   slot. Add up to nine.
3. Tap a tile to select it; the action bar below the grid appears:
   **Back**, **Home**, **Recents**, **Type**, **Shot**, **Record**,
   **Relaunch**, **Close**.
4. The columns button in the app bar cycles the grid between 1, 2 and 3 columns.
5. The status strip shows the shell state and the MCP endpoint; tap either to
   see the token or grant Shizuku.

## MCP

The endpoint comes up with the app and listens on all interfaces, port 8765 by
default (changeable in Settings, together with a new token).

```sh
ENDPOINT=http://<phone-ip>:8765
TOKEN=<shown in the app or the Settings screen>

# initialize + tools list
curl -s "$ENDPOINT/mcp" -H "X-Droid-Token: $TOKEN" -H 'Content-Type: application/json' -d '{
  "jsonrpc":"2.0","id":1,"method":"tools/list"
}'
```

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
  "name":"launch_app","arguments":{"package":"com.android.settings"}}}
```

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
  "name":"screenshot","arguments":{"slot":1,"scale":0.5,"save":true}}}
```

| Tool | What it does |
|---|---|
| `grid_status` | every slot: package, state, display id, frame count |
| `list_apps` | the phone's launchable apps |
| `launch_app` | start an app in the next free slot (or a given slot) |
| `close_app` / `close_all` / `restart_app` | close a slot, empty the grid, relaunch a slot |
| `tap` / `long_press` / `swipe` / `type_text` / `key` | input, addressed to one slot |
| `screenshot` | a frame as a base64 data URI, optionally saved to Downloads |
| `screenshot_grid` | one composite image of every running app |
| `record_video` | `start` / `stop` an MP4 of one slot |
| `wait_for_change` | block until a slot renders something new |
| `list_files`, `grid_layout`, `app_status` | saved captures, columns, device facts |

Resources: `screenshot://{slot}`, `grid://preview`, `status://grid`,
`files://shots`, `files://videos`, `shizuku://status`.

Point your client at `http://<phone-ip>:8765/mcp` — for example, any MCP client
that speaks Streamable HTTP (Claude Code, Cursor, a Python `mcp` client), or
`adb forward tcp:8765 tcp:8765` and use `http://127.0.0.1:8765/mcp`.
Full protocol details are in [docs/MCP.md](docs/MCP.md).

## Build

Two paths, same artifact:

```sh
./gradlew assembleDebug

# or, on the phone itself (no Gradle distribution available there):
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 \
KOTLIN_JAVA=/usr/lib/jvm/java-17-openjdk-arm64 \
KOTLIN_LIB=/opt/kotlin-lib \
ANDROID_JAR=/path/to/android-36/android.jar \
BUILD_TOOLS=/path/to/build-tools/35.0.0 \
SYSROOT=/tmp/x86root \
./scripts/build-apk.sh
```

The script runs aapt2 → javac → kotlinc → d8 → zipalign → apksigner. Three
details that matter on the development device:

- **Kotlin compiler jars.** `KOTLIN_LIB` must hold `kotlin-compiler-embeddable`,
  `kotlin-daemon-embeddable`, `kotlin-stdlib`, `kotlin-script-runtime`,
  `kotlinx-coroutines-core-jvm`, `trove4j` and `org.jetbrains:annotations` from
  Maven Central, all the same version (2.0.21 is what d8 35 can read).
- **qemu.** The Android build tools are x86-64 binaries, so on an arm64 host
  they run under `qemu-user` with an amd64 runtime extracted into `SYSROOT`
  (`libc6`, `libgcc-s1`, `libstdc++6`, `zlib1g` from archive.ubuntu.com).
- **JDK.** javac uses `JAVA_HOME` (17+); kotlinc uses `KOTLIN_JAVA` (17–23,
  because JDK 25 is too new for it).

The script downloads the four Shizuku AARs from Maven and signs the APK with a
throw-away debug key.

## Repository layout

The app is Kotlin (application, activities and views); the engine, the shell
bridge, the HTTP and MCP endpoint and the recorder are Java, so the two halves
are compiled in one javac pass and one kotlinc pass.

```
app/src/main/java/com/bliss/headlessdroid/
  App.kt / .java                 process split: UI process vs the shell process
  MainActivity.kt                the grid screen
  SettingsActivity.kt            endpoint, columns, screen preset, wake lock
  AppsSheet.kt                   the app picker
  GridView.kt / TileView.kt      the 3x3 grid and one cell
  Ui.kt                          the design tokens as view helpers
  Engine.java                    the grid: slots, launches, input, frame pump
  Tile.java                      one slot = one running app
  DisplayStack.java              a virtual display plus its ImageReader
  GridService.java               the foreground service that keeps the grid alive
  McpServer.java / HttpServer.java   the MCP endpoint and its HTTP server
  Recorder.java                  H.264 + MediaMuxer MP4 recording per slot
  Captures.java                  writes shots and videos to Downloads
  Cmds.java / Inputs.java         privileged work: am start, display-scoped input
  Shell.java / ShellService.java  Shizuku user service and its client
  Network.java                    which address the endpoint advertises
  Prefs.java, Droid.java
scripts/build-apk.sh
docs/ARCHITECTURE.md      how the two processes and the grid fit together
docs/MCP.md               the endpoint protocol
docs/VIRTUAL_DISPLAY.md   displays, flags, IME policy, wake locks
DESIGN.md                 the UI direction and what changed where
```

## Limitations

- **Shizuku is required** for launching on a display and for every input action.
  Without it the grid is unusable (the UI says so instead of failing silently).
- **Only launchable apps** appear in the picker: widgets and background
  services have nothing to render on a display.
- **Nine slots** is a hard cap, and it is a practical one: each slot is a real
  composed display, so nine portrait apps is what a phone keeps comfortable.
- **Some apps refuse to run off-screen.** Banking apps with
  activity-level `FLAG_SECURE` or `setSecure` windows are captured as black
  frames, and the same applies to DRM video surfaces: the display renders, but
  the pixels are not readable.
- **HyperOS sleeps displays with the screen.** The wake lock keeps them awake;
  a secure keyguard does not stop displays, but the screen must be on.
- **Recording is a screen capture**, not a camera: 10 to 15 fps per slot is the
  honest number, and more slots means fewer frames each.

## Credits

The virtual-display engine, the shell launch path and the display-scoped input
bridge are derived from
[ShadowAuto](https://github.com/HakaI963/ShadowAuto) (Apache-2.0), the same
extraction the previous headless-android used — minus the cloner.