# Boson Thermal PC tools

Process the phone app's raw recordings (`*.y16` + `*.csv`, saved in `Download/BosonThermal/`) on a PC,
rendered **exactly** the way the phone renders its normal video: MIN/MAX, CURVE or AUTO tone mapping, then a
White-hot, Black-hot or Ironbow palette. (The Python code is a port of the phone's Kotlin; it produces
bit-identical frames when a clip is processed from its first frame.)

## Install

    pip install -r requirements.txt        # numpy, plus PySide6 for the GUI
    # MP4 output also needs ffmpeg on PATH (e.g. `winget install ffmpeg`, `brew install ffmpeg`)

## GUI: `y16_gui.py`

    python y16_gui.py [recording.y16]      # or File > Open, or drop a .y16 on the window

- Drag the timeline (or Space / arrow keys) to scrub; the preview updates live as you change the options.
- **Tone mapping**: MIN/MAX, CURVE (Toe / Mid / Shoulder sliders) or AUTO (Gentle / Normal / Strong), plus
  the minimum range.
- **Palettes**: tick any combination (at least one stays ticked). The preview tiles every selected palette.
- **Export**: start/end frame ("Here" takes the current frame), size 1-4x (2x = the phone's 640x512),
  MP4 or PNG sequence. One output file per selected palette.
  To reproduce what the phone recorded, leave the start at frame 0.
- Jumping to a frame re-runs the previous 60 frames to settle the tone state (as the phone's smoothing
  would have); while you drag the slider a shorter warm-up is used and the frame is refined when you let go.

## Command line: `y16_tools.py`

    python y16_tools.py info    REC.y16
    python y16_tools.py preview REC.y16 --tone auto --palette white,iron        # MP4 per palette
    python y16_tools.py preview REC.y16 --tone curve --curve 0.2,0.55,0.8 --palette all --start 300 --end 900
    python y16_tools.py png     REC.y16 --frame 120 --tone minmax --palette iron
    python y16_tools.py mkv     REC.y16                                         # lossless 16-bit (FFV1)
    python y16_tools.py tiff    REC.y16 --start 0 --count 10 --outdir frames

`--palette` takes `white`, `black`, `iron` in any comma-separated combination, or `all` (default `white`).
Outputs are named `<recording>_<tone>_<palette>.mp4`.
`--auto-sens gentle|normal|strong`, `--min-range N` (default 64) and `--scale 1..4` match the phone's settings.

## Files

- `boson_core.py` - the shared processing (palettes, tone curve, MIN/MAX, CURVE, AUTO, recording reader).
