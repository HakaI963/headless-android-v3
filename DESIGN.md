# Design direction

## Audit of the previous screen

- The first screen was a diagnostic checklist, not an app; it opened before the product.
- The main screen was three thin strips of 11sp mono text, a hairline and a centered placeholder — no hierarchy, no primary surface.
- Tiles used a 4:3 landscape cell for a 540×720 portrait app, so every app appeared letterboxed inside a small box.
- The status dot, slot number, frame count, labels and meta all competed at 11–13sp.
- The empty state was two lines of text and a button floating in a scroll view with no product visual.
- The action bar was a horizontal strip of unlabeled icons; controls had no names.
- The app picker used a generic phone glyph for every installed app instead of its real icon.
- Every screen used the same 20/15/13/11sp type scale, which read as tiny on a phone.
- Font sizes, paddings and icon boxes did not form one aligned column.

## Direction

**Product.** Headless Android runs an installed app on a private virtual display and lets an AI agent drive it through one built-in endpoint.

**Character.** Precise, tactile, instrument-grade.

**World.** A device lab bench: graphite panels, hairline dividers, one amber signal lamp, labelled ports.

**Moments.** The live app screens inside their display frames, and the session card that names the shell and endpoint.

**Signature.** A display-id badge on every tile (`D 042`) beside one signal dot — the two facts that prove this is a real virtual display, not a screenshot.

## Passes

Spacing, color, icons, copy, type, layout, details, visuals, motion were all applied across the whole app. The product's own live frames are the visual; there is no generated decoration.

## Checklist

- [x] Direction written before editing; every pass follows it
- [x] All spacing on the 4dp scale; equal things equal size
- [x] Icons, labels and numbers each in one aligned column
- [x] One tinted neutral ramp, three text roles, one accent at 4.5:1 both ways
- [x] No hue gradients, gradient text, glows, colored shadows or blobs
- [x] No emoji in the interface
- [x] One icon set, one size, one stroke
- [x] Weights 400 only where available, no tracked uppercase, no italics
- [x] No exclamation marks, no trailing periods on short strings, no greeting headline
- [x] Numbers in one format with monospace figures
- [x] Nested radii add up
- [x] Active and selected states read apart by surface step and hairline
- [x] The empty state has one primary action and a product visual
- [x] Nothing loops or animates on its own
- [x] Template check: the display-id badge, live frames and endpoint card only fit this product
