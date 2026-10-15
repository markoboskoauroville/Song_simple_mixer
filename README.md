# Song simple mixer

An Android app by Mantra Productions: **one vocal, one backing track, two faders, export the mix.**

- **Load** a vocal and a backing track — anything Android can read (mp3, wav, flac, m4a, ogg/opus). Both are
  decoded and brought to 48 kHz stereo (44.1 kHz files are resampled with cubic interpolation).
- **Level** each with its fader (−30 dB = off … +12 dB), a 0 dB reset and a mute. Levels and the timing nudge are
  remembered.
- **Nudge** the vocal earlier or later in 1, 10 and 50 ms steps, so it sits on the beat.
- **Play / pause / seek** the live mix; faders move while it plays.
- **Export** the mix as a 48 kHz 16-bit WAV (TPDF dither) wherever you choose. A peak limiter (ceiling −1 dBFS)
  sits on the master in playback and export alike; the export reports how hard it had to work.

Made for the remix workflow in MANTRA_MANIFEST (`modules/remix-workflow.md`): the parts in
`SUNO_SONGS/remixes/<song>/parts/` (a chopped solo vocal and its backing track at the same tempo) are exactly the
two files this app takes.

## Build

APKs are built only by GitHub Actions (`.github/workflows/build-apk.yml`), signed with the app's one permanent key
(repository secrets `MIXER_KEYSTORE`, `MIXER_KEYSTORE_PASSWORD`), and published as releases (the two newest
are kept). Code that does the arithmetic (`Mix.kt`) imports nothing from Android and is tested on a desk
(`app/src/test`, Test 1); `scripts/verify.py` keeps it that way.

Package `community.auroville.mantra.mixer`, minSdk 26 (Android 8), target 35. No libraries.
