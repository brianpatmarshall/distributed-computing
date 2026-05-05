# Understanding Colors

A working primer for picking and building color palettes — written in the
context of the bash color scripts in this directory, but the ideas
generalize.

## The three axes

Most color is best described on three axes. The **HSL/HSB cylinder** is the
clearest mental model:

```
        brightness (value/lightness)
           ↑ white
           |
           |     ← saturation (distance from center)
           |    /
           |   /
    ───────●─────── hue (angle around the cylinder)
           |
           |
           ↓ black
```

### Hue — *which* color

An angle, 0°–360°, around the color wheel:

```
   0° red → 60° yellow → 120° green → 180° cyan → 240° blue → 300° magenta → 360° red
```

Two colors with the same hue but different brightness are the "same color,
lighter or darker": navy and sky blue both sit at ~210°.

### Brightness — *how light* the color is

Independent of hue. Black at the bottom, white at the top, the pure hue
somewhere in the middle.

```
   black ──── dark blue ──── blue ──── light blue ──── white
   (V=0)       (V=0.3)      (V=0.6)     (V=0.85)      (V=1)
```

### Saturation — *how vivid*

Distance from the center axis of the cylinder. Center = gray; edge =
maximally vivid.

```
   gray ──── muted blue ──── blue ──── vivid blue
   (S=0)      (S=0.3)      (S=0.7)     (S=1)
```

A "dusty rose" is high-hue-but-low-saturation red. A neon sign is
high-saturation. Saturation is what distinguishes a corporate palette
(muted) from a kid's toy palette (vivid) at the same brightness.

## RGB → which is which

The three channels in `#RRGGBB` map back to the three axes loosely:

- **Hue** ≈ *which channel dominates* (high R = red-ish, high G = green-ish)
- **Brightness** ≈ *the overall sum* of R+G+B (all low = dark, all high = light)
- **Saturation** ≈ *the spread between channels* (R=G=B → gray; one channel much higher than the others → vivid)

So `0A 07 11` is dark (sum is small) and weakly blue-hued (B is highest, but
barely — low saturation). `FF 00 00` is bright and maximally saturated red.

### Hex vs. decimal — same value, different ergonomics

A channel value is one byte: 0–255 in decimal, `00`–`FF` in hex. They're
the *same number*, just different notations:

```
decimal   0    16    64    128    192    255
hex      00    10    40     80     C0     FF
```

Hex wins when you want to *read* a color: the eye can see at a glance that
`FF 00 00` is "all red, nothing else." Decimal wins when you want to
*compute* a color — `RANDOM % 256`, gradients, channel arithmetic — because
bash arithmetic is decimal.

This is why the scripts in this directory expose two interfaces:

- `colorBG FF 00 00` — hex, for when you're typing a known color.
- `colorBGDec 255 0 0` — decimal, for when a value came from a calculation.

The conversion is one line (`printf "%02X" "$n"`), factored out as
`dec2hex` so it can be reused anywhere a decimal channel value needs to
become hex (e.g. `dec2hex 200` → `C8`). The decimal wrapper just calls
`dec2hex` three times and forwards to the hex primitive — one source of
truth for the actual escape sequence.

## Keep the ladder, change the hue

When building parallel palettes (e.g. `colorGreenish` → `colorBlueish`), the
trick is: **keep the brightness ladder, rotate the hue.**

```
Green ladder:   dark green (28) → medium (41) → bright yellow-green (154) → cyan-green (49)
                 V≈0.3            V≈0.5         V≈0.9                       V≈0.7
Hue:             ~120°            ~140°         ~80°                        ~160°

Blue ladder:    dark blue (18)  → medium (33)  → light sky blue (117)     → cyan (45)
                 V≈0.3            V≈0.5         V≈0.9                       V≈0.7
Hue:             ~240°            ~210°         ~200°                       ~190°
```

Same vertical positions on the cylinder, rotated around the axis. Your eye
reads the brightness contrast (dark base, bright dir) the same way in both
— so the palettes "feel" parallel even though the hue family changed.

The `*ish` family in `colorBG` (`colorGreenish`, `colorBlueish`,
`colorRedish`, `colorPurpleish`) is exactly this pattern: one shared
brightness ladder, four hue rotations. Adding a new one is a matter of
picking 4 indices in the new hue family at the same V positions — no other
decisions to make. That's the payoff of separating the two axes: hue
becomes a parameter you can swap, brightness stays an invariant of the
"shape" of the prompt.

## The xterm 256-color palette

When you write `\e[38;5;Nm`, `N` is an index into a 256-entry palette laid
out in three blocks:

| Indices  | Block               | Notes                              |
|----------|---------------------|------------------------------------|
| 0–15     | System colors       | The original 16 ANSI colors.       |
| 16–231   | 6×6×6 RGB cube      | `N = 16 + 36·R + 6·G + B`, R/G/B ∈ 0–5 |
| 232–255  | 24-step grayscale   | Black-ish at 232, white-ish at 255 |

The cube formula is genuinely useful: to locate "dark blue", set R=0, G=0,
B=2 → `16 + 0 + 0 + 12 = 28`… wait, that's the same index as the green I
used. Indices collide because the cube is small. Run `colorsShow` (defined
in `colorPrompt`) to eyeball the palette directly.

## Contrast and readability

A prompt is only useful if you can read it. The relevant metric is
**contrast ratio** between text and background — defined by WCAG as a
function of *relative luminance*, not raw RGB difference:

```
luminance ≈ 0.2126·R + 0.7152·G + 0.0722·B    (with each channel gamma-corrected)
```

Notice the coefficients are wildly uneven: green carries ~71% of perceived
brightness, blue only ~7%. That's why pure blue text on black is hard to
read (low luminance) but pure green on black pops (high luminance), even
though both are "one channel maxed."

Practical rule of thumb for terminal prompts:
- Aim for contrast ratio ≥ 4.5:1 (WCAG AA for normal text).
- Bright blues (`#5599FF`) read better than pure blues (`#0000FF`) on dark
  backgrounds because adding green and red lifts luminance.

## Color harmonies

Ways to pick *multiple* hues that look intentional together. All defined as
angular relationships on the wheel:

| Harmony        | Angle        | Feel                              |
|----------------|--------------|-----------------------------------|
| Monochromatic  | 0° (same)    | Calm, unified                     |
| Analogous      | ±30°         | Natural, gradient-y               |
| Complementary  | 180°         | High tension, vibrating           |
| Split-comp.    | 180° ± 30°   | Complementary but softer          |
| Triadic        | 120° apart   | Balanced, lively                  |
| Tetradic       | 90° apart    | Rich, hard to balance             |

The `colorPrompt` slots (history, host, dir, cmdline) are essentially a
4-color palette, so triadic-plus-accent or analogous-plus-accent are both
reasonable templates.

## Perceptual vs. mathematical brightness

`(R + G + B) / 3` is *mathematical* brightness. It's wrong for human
perception — see the luminance coefficients above. Two colors with the
same RGB sum can look very different:

- `#FFFF00` (yellow): sum = 510, looks bright
- `#FF00FF` (magenta): sum = 510, looks darker

When you sort palette colors by "lightness" by eye and the math disagrees,
the math is what's wrong. Use HSL's L or WCAG luminance for perceptual
ordering.

## Gamma — the lurking gotcha

Display values aren't linear in light. `#80` isn't half as much light as
`#FF` — it's roughly 22%, because monitors apply a gamma curve (~2.2). If
you average `#000000` and `#FFFFFF` and expect gray, you'll get gray on
screen, but the *light energy* is much less than half.

Most of the time you can ignore this. It bites you when:
- Blending/dimming colors and the result looks darker than expected.
- Computing perceived brightness — you must gamma-correct first (the WCAG
  formula does).

## A short pickers' checklist

When choosing a palette for a terminal scheme:

1. **Pick the BG first.** Dark or light? It anchors everything else.
2. **Choose 1 dominant hue** for the scheme.
3. **Build a brightness ladder** of 3–4 steps from that hue (or close
   neighbors via analogous harmony).
4. **Add one accent** at a different hue (complementary or triadic) for the
   element that should jump — typically the cursor/cmdline or directory.
5. **Check contrast** of the lightest-foreground vs. background. If you
   can't comfortably read a long path, the dir color is too dim.
6. **View it on your actual terminal.** sRGB rendering varies; a palette
   that looks great in a swatch tool can feel muddy in iTerm vs. Alacritty
   vs. xterm.

---

## Appendix: the themed schemes

The `*ish` functions (`colorGreenish`, `colorBlueish`, etc.) follow one
rule: **shared brightness ladder, rotated hue**. They're systematic — each
one is the same scheme in a different key.

The themed schemes are different. They don't try to be parallel; each one
optimizes for a **mood**, and breaks the ladder rule when the mood demands
it. A useful way to read them is: BG sets the world, prompt colors set
what lives in it.

For all of these, the prompt indices map back to the 6×6×6 cube via
`N = 16 + 36·R + 6·G + B` with R/G/B ∈ 0–5. The cube coordinates often
explain the choice better than the index does.

### `colorCircus` — vivid primaries

```
BG:     0A 0A 0A         neutral very-dark gray
Prompt: 196 226 39 201   red, yellow, blue, magenta
```

The BG is deliberately *un*-tinted. Saturated primaries clash with any
hue underneath them, so a neutral dark frame lets all four colors pop
equally. Decoded:

- `196` = (5,0,0) — pure red
- `226` = (5,5,0) — pure yellow
- `39`  = (0,3,5) — pure cyan-blue (bumped from pure blue 21 because pure
  blue reads as too dim — recall blue contributes only 7% to luminance)
- `201` = (5,0,5) — pure magenta

This is the one scheme that *intentionally ignores the brightness ladder*.
Circus is about saturated variety, not progression — every slot screams
at the same volume.

### `colorSpace` — deep field with starlight

```
BG:     00 00 0A         near-black with the faintest blue
Prompt: 19 99 51 231     deep blue → nebula purple → cyan → starlight
```

The BG approximates the cosmic-microwave-background look: not pure black
(which feels flat) but the faintest channel-3 lift in B so the eye knows
"this is space, not a void."

The prompt is a true brightness ladder, but the hues *also* tell a story:

- `19`  = (0,0,3) — distant dark blue (history is far away)
- `99`  = (2,1,5) — purple-blue nebula (host has presence)
- `51`  = (0,5,5) — bright cyan (the directory glows)
- `231` = grayscale white (the cursor is the star you're aiming at)

Hue drifts cooler-then-warmer (blue→purple→cyan→white) while brightness
climbs — both axes pulling toward the cmdline.

### `colorSunset` — warm gradient

```
BG:     1F 07 14         dark plum twilight
Prompt: 88 208 220 213   dark red → orange → gold → pink
```

Sunset is a literal hue-walk along the warm side of the wheel. The BG is a
deep plum — not red, because the eye reads "after sunset" when the sky
goes purple. The prompt then steps *backwards through the day*:

- `88`  = (2,0,0) — last-light dark red on the horizon
- `208` = (5,2,0) — orange band above it
- `220` = (5,4,0) — gold higher up
- `213` = (5,2,5) — pink wash near zenith (the magenta accent that says
  "this is sunset, not noon")

The brightness ladder rises monotonically, but here the *hue* is the
storyteller — each step rotates ~30° around the wheel, an analogous
harmony in motion.

### `colorMorning` — the inverted scheme

```
BG:     E8 F0 FF         pale dawn sky — LIGHT background
Prompt: 24 94 22 88      dark teal, amber, dark green, dark red
```

The only light-BG scheme in the file, and the most constrained: every
prompt color must be dark enough to read on near-white. That forces all
four indices into the bottom of the cube — R/G/B values ≤ 2:

- `24` = (0,1,2) — dark teal (sky's reflection)
- `94` = (2,1,0) — dark amber (sunrise warmth)
- `22` = (0,1,0) — dark forest green (landscape)
- `88` = (2,0,0) — dark red (early sun on the horizon)

The hues are scattered (teal, amber, green, red) instead of laddered,
because the *story* of morning is "many things waking up at once" — the
unifying axis is brightness (all dark) rather than hue.

This is also a useful counterexample to the dark-BG default: when you
invert the background, every assumption about prompt colors flips, and
you must recompute readability. Pure red `196` would vanish on this BG;
dark red `88` reads cleanly.

### `colorMoon` — monochromatic silver

```
BG:     05 08 14         midnight blue-black
Prompt: 60 103 153 195   silver ladder, dim → bright
```

The strictest scheme: a single hue family (cool blue-gray) walked from
dim to bright. Decoded:

- `60`  = (1,1,2) — muted slate
- `103` = (2,2,3) — steel
- `153` = (3,4,5) — pale moonlight
- `195` = (4,5,5) — near-white silver

R/G/B all rise together and stay near each other — that's the cube
coordinate signature of a **monochromatic** palette (low saturation,
varying brightness). Compare to `colorCircus` where each color sits at a
*different corner* of the cube (one channel maxed, others zeroed) — that's
the signature of maximum saturation.

### Reading a scheme from its cube coordinates

A useful diagnostic: convert the four prompt indices to (R,G,B) and look
at the *shape* of the resulting set.

| Shape in the cube                          | Scheme type          | Examples                 |
|--------------------------------------------|----------------------|--------------------------|
| Points on one axis (e.g. all on R-axis)    | Monochromatic        | `colorMoon`              |
| Points on adjacent corners                 | Analogous            | `colorSunset`, `*ish`    |
| Points on opposite corners                 | Complementary        | (none here)              |
| Points on far-apart corners                | Triadic / primary    | `colorCircus`            |
| Points all near the origin                 | Light-BG-compatible  | `colorMorning`           |
| Points all near (5,5,5)                    | Faded-on-dark, risky | (avoid — low contrast)   |

That's the whole trick. Once you can read the cube coordinates, picking a
new scheme reduces to: decide the shape, then pick four points that fit it.
