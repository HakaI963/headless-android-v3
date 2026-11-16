# Architecture

Headless Droid is two processes and one screen.

```
┌─────────────────────────────┐          ┌──────────────────────────────┐
│  com.bliss.headlessdroid    │          │ com.bliss.headlessdroid:shell│
│                             │          │                              │
│  MainActivity (the grid)    │          │  ShellService (Binder)       │
│  Engine        slots, pump  │          │  Cmds         am start       │
│  DisplayStack  virtual disp │◄────────►│  Inputs       injectInputEvent│
│  Recorder      H.264/MP4    │  JSON    │                              │
│  McpServer/HttpServer :8765 │ over one │  runs with shell identity    │
│  Captures      Downloads    │   parcel │  (shizuku user service)      │
└─────────────────────────────┘          └──────────────────────────────┘
            │
            ▼
   /sdcard/Download/HeadlessDroid/{shots,videos}
```

## Why the split

A virtual display can be created by any app: `DisplayManager.createVirtualDisplay`
with `VIRTUAL_DISPLAY_FLAG_PUBLIC` needs no permission. Capturing it and drawing
it is no more privileged. So the app process owns the displays, the frames, the
recorder and the HTTP server.

Two things cannot be done from an app (uid ~10xxx):

1. **starting an activity on another display** — `am start --display <id>` is a
   shell command, and
2. **injecting input addressed to a display** — `InputEvent.setDisplayId()`
   plus `InputManager.injectInputEvent()` need `INJECT_EVENTS`, a signature
   permission held by the `android.uid.shell` group.

Both are performed in the user-service process, which Shizuku starts with shell
identity. Nothing else moves into that process: it holds no display, no socket,
no state.

## App process

### Engine

[`Engine`](../app/src/main/java/com/bliss/headlessdroid/Engine.java) is the only
object with authority over the grid. It is a singleton created in
[`App.onCreate`](../app/src/main/java/com/bliss/headlessdroid/App.java), owns a
single `HandlerThread`, and never touches the UI thread.

- **Slots.** A fixed array of 9 `Tile`s. `launch(pkg)` allocates the first free
  slot, builds a `DisplayStack`, then asks the shell process to start the app on
  that display. The slot's state goes `starting → live` the moment the first
  frame is captured, so the UI shows *live* only when there is something to see.
- **Frame pump.** One loop every 45ms walks the live tiles and calls
  `ImageReader.acquireLatestImage()`. Frames are decoded into a single staging
  bitmap, copied into a new bitmap, published as the tile's `latest`, and handed
  to the recorder when that slot is recording. Dropping frames is correct here:
  a grid of nine apps is a preview, not a recording.
- **Listeners.** The engine pings listeners at most 20 times per second; the UI
  also runs its own 60ms ticker so tiles that changed only by a frame still
  repaint. A tile repaints only when its frame counter or state actually
  changed.

### Threads

| Thread | What it does |
|---|---|
| main | draws, handles taps, any view work |
| `HeadlessDroid` | slot lifecycle, `am start` results, frame pump |
| per connection | one thread per HTTP/MCP connection, all daemons |
| per recording | one thread owns its `MediaCodec` |
| `:shell` | the Shizuku user service process |

Locks: one `Engine.lock` guards the slot array and the caches, `ImageReader` is
single-consumer and only touched by the worker thread, and codec state is
confined to its own thread.

## Shell process

[`ShellService`](../app/src/main/java/com/bliss/headlessdroid/ShellService.java)
is a plain `Binder` (Shizuku 13 declares no `IUserService` in its jar, so the
protocol is ours). Two transactions: `TX_EXEC=1` takes one JSON command string
and replies with a JSON string; `TX_PING=2` answers `pong`.

```json
{"cmd":"start","component":"com.android.settings/.Settings","display":3}
{"cmd":"tap","display":3,"x":270,"y":480}
```

The command set is deliberately small — start, stop, tap, long press, swipe,
key, text, info — because everything else in the app can be done by the app
itself. [`Cmds`](../app/src/main/java/com/bliss/headlessdroid/Cmds.java) runs the
shell command, [`Inputs`](../app/src/main/java/com/bliss/headlessdroid/Inputs.java)
does the reflection injection; both report failure rather than throwing, so a
wrong ROM answers "ok: false" instead of a crash.

## Launching an app, precisely

1. `Engine.launch(pkg)` resolves the launch component from the `PackageManager`
   (no shell involved) and allocates a slot.
2. `DisplayStack.create` asks the system for a public virtual display of the
   slot's preset size. If the ROM rejects the richest flag set, the next set is
   tried: `SUPPORTS_TOUCH | OWN_FOCUS | TRUSTED`, then fewer, down to plain
   `PUBLIC | PRESENTATION | OWN_CONTENT_ONLY`.
3. `am start --user 0 --display <id> -f 0x18000000 -W -n <component>`
   — `NEW_TASK` plus `RESET_TASK_IF_NEEDED` give the app its own task on that
   display.
4. The app renders into the display's `ImageReader` surface. The first captured
   frame flips the slot to `live`.
5. Input arrives as normalized coordinates in the UI and is multiplied by the
   slot's geometry in the shell process, so a tap at (0.5, 0.5) always hits the
   middle of that app's screen, whatever its density.

There is a fallback: if Shizuku is unavailable the engine tries
`ActivityOptions.setLaunchDisplayId()` from its own process, which some ROMs
refuse. Nothing is ever done silently — the status strip says the shell is
missing instead.

## Shutdown

Closing a slot releases the display, force-stops the app, aborts any recording
and frees the slot. When the last slot closes the wake lock is released, the
foreground service stops itself, and the MCP server stays up so a client can
still ask `grid_status` and get an empty grid.