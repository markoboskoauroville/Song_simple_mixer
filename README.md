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

## Opening files without the picker

Share audio to **Mantra Mixer** from Files, Drive or a music app. Sharing two files at once fills both slots: a name containing *vocal*, *chops* or *acapella* goes to VOCAL and the other goes to BACKING. A single shared file goes to the slot its name suggests. If the phone has no document picker, *Load* says so instead of crashing, and *Export* saves straight into **Downloads**.

## Emulator test (Pixel 7 AVD, Android 11 x86_64, 2026-10-16)

The Scooter vocal (FLAC, 4:08) and the Scooter instrumental (MP3, 4:09) were loaded by share intent. Then:
- Play advanced the clock.
- The nudge was set to +11 ms.
- The fader was dragged to −11 dB, then reset to 0 dB.
- Mute toggled.
- The mix was exported to Downloads.

The exported WAV is 48 kHz/16-bit, as long as the backing, with sample peak −1.01 dBFS (the limiter ceiling). It equals an independent numpy mix of the same files with the vocal 11 ms late to within −33 dB residual; with no offset the residual is −5 dB.
