# Research: the cost of sanitizing and verifying fonts

Measurements taken on 2026-10-19 while `vtfx-font` was written ([decisions/0040](../decisions/0040-sanitized-user-fonts.md)). They record what was measured at that date and are not kept up to date; the rules that they led to are in sections 4 and 6 of [fonts.md](../design/fonts.md).

## 1. Building messages of checks

The verifier and the conversion run for each of the millions of points of a CJK font. Building the message of every check, whether it failed or not, made checking take most of the time of `sanitize` and of `fromCanonical`. With the messages built only on failure, and the conversion reusing one pen and its arrays for every glyph and looping over charstring arguments without ranges that allocate, Noto Sans CJK on HotSpot went:

| Measurement | Before | After |
|---|---|---|
| `sanitize` | 2.4 s | 1.1 s |
| `sanitize`, with only the interpreter | 146 s | 38 s |

## 2. The verifier on Android

With the arrays of the points of a glyph reused for every glyph, and the reads of one and two bytes made small enough for Android's runtime to inline (32 code units of dex by default), `fromCanonical` of face 0 of Noto Sans CJK (19.5 MB) on an x86_64 Chromebook with Android 17 went:

| Measurement | Before | After |
|---|---|---|
| Time | about 212 ms | about 148 ms |
| Allocated | 146 MB | 40 MB |

The 40 MB are two copies of the font: the one checked, so that the caller cannot change the bytes after they are checked, and the direct buffer that the platform takes.
