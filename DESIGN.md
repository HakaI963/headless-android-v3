# Design

The grid screen was designed with the [unslop](https://github.com/heydamir/unslop)
method: find a direction from the product, then fix spacing, color, icons, copy,
type, layout, details, visuals and motion in that order. This file is that
direction and the record of each pass.

## Direction

**Product.** Headless Droid runs up to nine real phone apps on hidden virtual
displays, in a 3×3 portrait grid, and lets an AI drive them over MCP.

**Character.** Instrument panel. Calm, exact, unhurried, a little warm.

**World.** A rack of test equipment in a dark room: graphite panels, hairline
borders, one sodium-amber signal lamp, monospace readouts. Not a phone launcher,
not a gaming app, not a "modern AI product".

**Moments.** Two, and they are both the product's own content, not decoration:
the nine live app screens inside their graphite cells, and the status strip's
readouts (slot count, endpoint, token) in monospace on quiet space.

**Signature.** The status dot on every tile — 6dp, amber when the app is
rendering, dim grey when it is not, red on error — plus the frame counter in
monospace on the focused tile. Nothing else in the interface carries color.

## Palette

One neutral ramp tinted slightly cool, plus one accent, plus three text roles.
Everything else is derived.

| Token | Value | Use |
|---|---|---|
| `bg` | `#121417` | the screen |
| `surface` | `#191C1F` | cards, tile cells |
| `raised` | `#20242A` | buttons, chips, the action bar |
| `pressed` | `#262B31` | touch feedback |
| `hairline` | `#2A2F35` | every border |
| `hairline_strong` | `#39404A` | the divider in the status strip |
| `text_primary` | `#E6E9EC` | headings, app labels |
| `text_secondary` | `#A0A8B0` | body, button labels |
| `text_muted` | `#6B747C` | captions, slot numbers, state readouts |
| `accent` | `#E0A458` | the dot when live, the endpoint text, selected tile border |
| `on_accent` | `#1A1206` | text on the accent surface (9.3:1 on `accent`) |
| `live` / `idle` / `stopped` / `danger` | `#E0A458` `#4E5761` `#6B747C` `#C96A5E` | the dot's four states |

The accent is used as text on `bg` at 8.6:1 and as a fill with `on_accent` text
at 9.3:1, so it passes 4.5:1 both ways. Backgrounds and surfaces stay neutral:
the color lives inside the tiles, where the real apps are.

## Type

One family — the system UI font — with monospace for anything numeric. Four
sizes, two weights, no tracked uppercase, no italics.

| Role | Size | Weight |
|---|---|---|
| Screen title | 20sp | 400 |
| Body, app labels | 15sp | 400 |
| Secondary, chips | 13sp | 400 |
| Micro, mono | 11sp | 400 |

Hierarchy comes from size and color. The slot number and the frame counter are
monospace so the eye lands on them as data, not text.

## Icons

Eighteen hand-written vector drawables at 24dp, one stroke (1.5), round caps
and joins, no fills, tinted with the text role they sit next to. One accent
exception: the launcher icon, where one cell of the six is filled with the
accent — slot 1 running, which is the grid this app is about.

## The nine passes

**1. Spacing.** Everything on a 4dp scale: 8dp inside a chip, 12dp around a
tile, 16dp page edges, 12dp radius on a cell, 10dp on a control. The app bar is
the densest part of the screen (40dp buttons, 8dp between them) and the tiles
carry the widest gaps, so the grid reads as the object and the chrome reads as
the frame. Equal things are equal: every row in the app picker is one height,
every chip is one height, every cell in a row is one aspect ratio.

**2. Color.** Graphite ramp above; one accent; three text roles. The status dot
is the only colored thing outside the tiles. No gradient, no glow, no colored
shadow, no neon border anywhere: depth comes from surface steps and hairlines,
which is what a rack of equipment does.

**3. Icons.** Emojis are gone. The picker's plus, the action bar's camera,
record, key, restart, close and the three navigation glyphs are one set at one
stroke. They take the color of their label, so the action bar reads as one thing
instead of eight.

**4. Copy.** Headlines name the context: the title is the product name, the
subtitle is "3 of 9 slots", the status strip is "Shell" and "MCP :8765". No
exclamation marks anywhere. Buttons are nouns (`Shot`, `Record`, `Close`) and
the empty state says what to do: "Nothing running. Add an app to the grid."
The states are one word: `live`, `idle`, `stopped`, `error`.

**5. Type.** Sentence case everywhere — no `NAVIGATION`, no `PAID THIS MONTH`.
Numbers in `Drawable`-monospace, one format (`540x720`, `:8765`, `128f`), small
text at 11sp is as small as anything gets, with no negative tracking.

**6. Layout.** One screen, portrait-only, `singleTop`. The grid scrolls
vertically because nine portrait cells cannot fit one phone screen; the action
bar appears at the bottom only for the selected slot, so the grid itself is the
screen. The empty state centers vertically in the scroll view rather than
hanging at the top, and the single primary action (add an app) sits under the
explanation.

**7. Details.** Radii nest: 12dp on a cell, 12dp on the frame placeholder inside
it, 10dp on the chips inside the frame, hairline 1dp and 2dp for the selection.
Selected state is a 2dp accent border on a surface step — not a gradient, not a
glow. Rows in the status strip are tappable with the platform's own subtle
feedback, and touch feedback everywhere else comes from a ripple over the same
surface color.

**8. Visuals.** No generated images, no decorative blobs, no orbs. The product
*is* the visual: nine live app screens. The launcher icon is flat: a dark plate,
six cells in stroke, one filled.

**9. Motion.** Nothing loops. Nothing breaths. The tile only redraws when its
frame actually changed, and the pump is throttled to one redraw per 60ms
instead of one per animation frame. Transitions are the platform's ripple and
the 2dp border appearing on selection.

## Checklist

- [x] Direction written before editing; every pass follows it
- [x] All spacing on the 4dp scale; equal things equal size
- [x] Icons, labels and numbers each in one aligned column (the action bar and
      the status strip share one vertical rhythm)
- [x] One tinted neutral ramp, three text roles, one accent at 4.5:1 both ways
- [x] No hue gradients, gradient text, glows, colored shadows, blobs
- [x] No emoji in the interface
- [x] One icon set, one size, one stroke
- [x] Weights 400 only, no tracked uppercase, no italics, no negative tracking
- [x] No exclamation marks, no trailing periods on short strings, no greeting
- [x] Numbers in one format with monospace figures
- [x] Nested radii add up (12 → 10, and the placeholder inside a tile is 8)
- [x] Selected / focused / idle read apart by border and surface
- [x] The empty state has one primary action
- [x] The first screen is the product, with no dead empty band
- [x] Nothing animates on its own; a tile redraws only when the app changed
- [x] Template check: swap the product name and the screen stops fitting — the
      grid of real apps and the slot counter are this product's own