# Virtual displays

How nine real apps get nine real screens, and what the phone does to make that
work.

## One display per slot

Every slot owns a
[`DisplayStack`](../app/src/main/java/com/bliss/headlessandroid/DisplayStack.java):
one `VirtualDisplay` plus one `ImageReader` whose surface the display renders
into. The app process can create a public virtual display without any special
permission, which is why the whole capture path lives in the app and only the
launch and the input need shell identity.

Sizes come from a small preset table, always portrait, because the grid is
portrait:

| Preset | Pixels | Density | Window in dp |
|---|---|---|---|
| 0 | 360 × 480 | 160 | 216 × 288 |
| 1 (default) | 540 × 720 | 240 | 324 × 432 |
| 2 | 720 × 960 | 320 | 324 × 432 |

Density matters more than pixel count: an app lays itself out against it, so a
higher preset is not just crisper, it is the same layout at more detail. Nine
slots at preset 1 is nine composed displays of roughly a third of a tablet
each — what a phone keeps comfortable.

## Flags

The richest set is requested first and rejected sets are tried downwards, which
is what makes this work across ROMs:

```
SUPPORTS_TOUCH | OWN_FOCUS | TRUSTED      # best case: touch and focus control
SUPPORTS_TOUCH | OWN_FOCUS                # no TRUSTED
SUPPORTS_TOUCH
PUBLIC | PRESENTATION | OWN_CONTENT_ONLY  # the fallback that always works
```

`SUPPORTS_TOUCH` is hidden in the SDK and read reflectively, like the other two.
A display created without `SUPPORTS_TOUCH` still renders, but input injection to
it behaves differently, and that is one of the things the flags exist to avoid.

`OWN_CONTENT_ONLY` keeps an app's own content (not a mirror of the phone screen)
on the display, and `PUBLIC` is what makes the display visible to the system
services that start an activity on it.

## The OFF state

Some ROMs create the display in `STATE_OFF`, in which case nothing is composed
and the app's window is never drawn. Detaching and re-attaching the surface
turns it back on:

```java
display.setSurface(null);
SystemClock.sleep(120);
display.setSurface(reader.getSurface());
SystemClock.sleep(300);
```

`DisplayStack.ensureOn()` does exactly this and reports the resulting state, and
the slot only reports itself *live* once a real frame has been captured.

## Frames

`ImageReader.poll(timeout)` acquires the latest image, copies the buffer into a
staging bitmap sized to the producer's padded row stride, and publishes a cropped
copy as the tile's `latest`. The padding matters: the producer pads rows, and
copying a padded buffer into a width-sized bitmap silently skews the image.

The staging bitmap is allocated once per display; the published bitmap is one
allocation per frame and is never recycled while the slot lives, because the UI
thread may be drawing it. Frames are dropped rather than queued — a preview is
a latest-value view, and nine apps at once means the difference between 2 fps
and 20 fps of preview is memory pressure, not quality of capture.

## Input

Every input event carries the display id it is addressed to:

```java
InputEvent.setDisplayId(tile.displayId);                     // hidden API
InputManager.injectInputEvent(event, INJECT_WAIT_FOR_FINISH); // hidden API
```

That call needs `INJECT_EVENTS`, which a plain app does not hold, so it happens
in the shell process (`Inputs`). Coordinates are converted from the UI's
normalized 0..1 scale to the slot's pixels there, so a tap at the middle of tile
4 is always the middle of that app's screen.

## Wake locks

HyperOS suspends virtual displays together with the phone screen. While any
app is on the grid, Headless Android holds a `FULL_WAKE_LOCK` with its own
reference counting: it is taken in `launch` and released when the last slot
closes (or when the wake-lock preference is turned off). There is no way to keep
a virtual display alive through a screen-off without keeping the screen on,
and no way to wake the display while the phone is locked behind a secure
keyguard — both are the system's rules, not the app's.

That is also why the grid lives behind a foreground service
([`GridService`](../app/src/main/java/com/bliss/headlessandroid/GridService.java)):
the notification's job is to stop the process from being frozen while nine apps
are being driven, including by an AI that is connected from another machine.

## Screens that stay legible, and those that do not

Two things are impossible on any Android device and are called out in the
README for honesty:

- **`FLAG_SECURE` windows.** Apps that mark their window secure are protected
  by the system: the display renders, but the `ImageReader` receives black
  frames. Banking apps, the Play Store purchase flow and DRM video behave this
  way. The slot still shows *live*, because the app is running and events
  still arrive; only the pixels are withheld.
- **Protected surfaces.** Video that goes out through DRM-protected surfaces is
  not visible to an app-owned capture, for the same reason.

Neither is a bug in the display, and nothing in Headless Android tries to work
around them.