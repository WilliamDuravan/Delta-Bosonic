# Boson Thermal

Camera app for a FLIR Boson 320 (60 Hz) thermal core on Android over USB (UVC, Y16).

- Opens straight to the live view. Photo / Video modes (swipe or tap), a SAVE button cycling
  STD -> STD+RAW -> RAW, a TONE button cycling MIN/MAX -> CURVE -> AUTO, FFC and palette on the top bar.
- Settings (gear): Image, Camera, About, and a Diagnostics submenu (log recording, USB report,
  serial console, FPN calibration). Volume keys also work as the shutter.
- Tone modes:
  - **MIN/MAX** - linear stretch of the 0.1%..99.95% range.
  - **CURVE** - the same range through a hand-adjustable three-handle curve.
  - **AUTO** - MIN/MAX until a hot/cold object crowds the rest of the scene into a thin band, then a
    noise-limited histogram curve blends in (see `AutoMapper.kt`).

PC tools for the raw recordings (GUI + command line, same processing as the phone) are in `tools/`; see `tools/README.md`.

Build: push to GitHub; the Actions run produces `BosonThermal-<version>.apk` in the `BosonThermal-apk` artifact.

---
Original v0.1 notes below (the USB report is now Settings > Diagnostics > USB report).

# USB descriptor probe (Level 0)

Tiny diagnostic app: lists USB devices, requests permission, and dumps the full
descriptor tree (interfaces, endpoints, UVC formats/frames/fps, extension units,
CDC/vendor serial interfaces). It tells us whether Y16 is advertised and how the
Boson's control channel is exposed.

## Build (GitHub Actions)
1. Create a new GitHub repo and push this folder to it.
2. Open the Actions tab -> "Build debug APK" -> wait for green.
3. Download the `BosonThermal-apk` artifact, unzip, install the APK
   (allow "install unknown apps" for your browser/file manager).

## Build (Android Studio)
Open the folder, let it sync, Run. (Gradle wrapper is not included; Studio makes one.)

## Use
1. Plug the Boson in via the Samsung EE-UN930 adapter, open the app.
2. Tap **Grant USB permission** and accept.
3. Tap **Claim test** (also tries every alt setting on the video interface).
4. Tap **Copy** or **Share** and send the report back.

## Raw video (v0.7)
"Raw rec" writes lossless Y16 to Download/BosonThermal/ as `<name>.y16` (concatenated
little-endian 16-bit frames, no headers) plus `<name>.csv` (timestamps, drop info).
~590 MB/minute at 60 fps. Convert on a PC with `tools/y16_tools.py` (info, mkv, preview, tiff),
or directly: `ffmpeg -f rawvideo -pixel_format gray16le -video_size 320x256 -framerate 60 -i REC.y16 -c:v ffv1 out.mkv`

## Tone curve (v0.8)
"Curve: on" shows a panel with the live histogram and three handles on the current min..max range:
T (end of the dark tail), M (input value shown as mid-grey), S (start of the bright tail).
Tails roll off smoothly instead of clipping. Double-tap the panel or press "Reset curve" to reset.
Applies to preview, snapshot PNG and MP4; raw data is never altered. Settings persist.
