#!/usr/bin/env python3
"""Rebuild the Pearl / Umber rest-scene loops in app/src/main/res/raw from the Grok Imagine loops.

쉬는 화면(Pearl·Umber)의 그림 영상만 만든다. 글자·타이머·버튼은 앱 코드(RestSceneView)가 그린다.

For each theme, frame by frame (ffmpeg decode -> numpy -> ffmpeg encode):
  1. crop the artwork region only. The old mock-up's text column, header words and footer buttons stay outside;
     the two thin header/footer rules that cross the crop are erased by interpolating from the rows around them,
     so the shadow (Pearl) and glow (Umber) have room to fade out,
  2. (Umber) map the Grok ground onto the app's Umber ground token (per-channel black point, highlights kept),
  3. feather all four edges (smoothstep; Umber also along its object's ellipse) into ONE flat colour = the theme's
     bg token, the ground of RestSceneView,
  4. encode a small, muted, loop-friendly H.264 Constrained Baseline file (no B-frames, 2 reference frames — the
     Pleos emulator's decoder corrupted blocks with x264's default 16 refs), BT.709 tagged, 24 fps, 240 frames,
     IDR every 2 s.

Then, for the finish screen (마침 화면), one still per theme is pulled out of the finished loop with ffmpeg:
  5. frame 0 of res/raw/rest_<theme>.mp4 (the same frame the reduced-motion rest holds) -> small lossy WebP in
     res/drawable-nodpi/rest_<theme>_still.webp. RestSceneView shows it when the finish appears without a live
     player (e.g. the app came back at the finish), with the settled halo drawn by code on top.
     ffmpeg -i res/raw/rest_<theme>.mp4 -frames:v 1 -vf scale=in_color_matrix=bt709:in_range=tv,format=bgra
            -c:v libwebp -lossless 0 -quality 88 -compression_level 6 res/drawable-nodpi/rest_<theme>_still.webp

Needs ffmpeg on PATH and numpy (Pillow only for --preview).
Re-run from the project folder:  python tools/prepare_rest_scenes.py [--preview DIR]
Stills only (keeps the loops as they are):  python tools/prepare_rest_scenes.py --stills-only
The crop sizes and flat colours are mirrored in RestSceneView (PEARL_W/H, UMBER_W/H, CarTheme bg).
"""
import argparse
import pathlib
import subprocess
import sys

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "res" / "raw"
STILLS = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
STILL_QUALITY = 88
SRC = pathlib.Path.home() / "Documents" / "Pit-In" / "pit-in" / "hybrid-motion" / "assets" / "grok"
FRAMES, FPS = 240, 24

# All numbers are in the 1168x784 Grok frame. crop = (x, y, w, h); rules = source rows (first, last) to erase;
# feather = (left, right, top, bottom) px.
SCENES = {
    "pearl": {
        "src": "pearl-guided-loop-10s.mp4",
        "crop": (400, 96, 768, 544),      # header words end y=92, footer button starts y=647, text column ends x~395
        "rules": [(116, 119), (625, 628)],
        "flat": (0xf6, 0xf4, 0xf0),       # CarTheme.PEARL.bg
        "feather": (56, 56, 56, 64),
        "ellipse": None,
        "levels": ((246, 244, 241), (246, 244, 240)),  # Grok ground along the crop edge (median) -> Pearl bg token
        "denoise": None,
        "crf": 21,
    },
    "umber": {
        "src": "umber-freedom-loop-10s.mp4",
        "crop": (400, 96, 768, 544),      # header words end y=87, footer icon/button start y=647
        "rules": [(106, 109), (621, 624)],
        "flat": (0x1c, 0x16, 0x11),       # CarTheme.UMBER.bg (the v5 design ground is 29/23/18)
        "feather": (64, 72, 56, 56),
        # The Umber haze fills the crop, so a rectangular feather alone leaves a soft lit box. The haze is also faded
        # along the ellipse that the line object sweeps over the whole loop (fitted to its high-frequency footprint:
        # centre 410,273, radii 341x252 in crop pixels; 99 % of the footprint lies inside r=1.02).
        "ellipse": (410, 273, 341, 252, 0.98, 1.14),
        "levels": ((37, 27, 19), (28, 22, 17)),  # Grok ground near the crop edge -> Umber bg token
        "denoise": "hqdn3d=1.2:1.2:2.5:2.5",      # Grok film grain only; keeps the file small
        "crf": 25,
    },
}


def smoothstep(t):
    t = np.clip(t, 0.0, 1.0)
    return t * t * (3 - 2 * t)


def feather_mask(w, h, feather, ellipse=None):
    left, right, top, bottom = feather
    x = np.arange(w, dtype=np.float32)
    y = np.arange(h, dtype=np.float32)
    mx = smoothstep(x / left) * smoothstep((w - 1 - x) / right)
    my = smoothstep(y / top) * smoothstep((h - 1 - y) / bottom)
    mask = my[:, None] * mx[None, :]
    if ellipse:
        cx, cy, rx, ry, r0, r1 = ellipse
        r = np.sqrt(((x[None, :] - cx) / rx) ** 2 + ((y[:, None] - cy) / ry) ** 2)
        mask = mask * (1 - smoothstep((r - r0) / (r1 - r0)))
    return mask[..., None].astype(np.float32)


def levels_lut(levels):
    (sources, targets) = levels
    luts = []
    for s, t in zip(sources, targets):
        v = np.arange(256, dtype=np.float32)
        out = np.where(v < s, v * t / s, t + (v - s) * (255 - t) / (255 - s))
        luts.append(np.clip(np.round(out), 0, 255).astype(np.uint8))
    return luts


def erase_rules(frame, rules, y0):
    for first, last in rules:
        a, b = first - y0 - 1, last - y0 + 1  # clean rows just outside the rule
        if a < 0 or b >= frame.shape[0]:
            continue
        top, bottom = frame[a].astype(np.float32), frame[b].astype(np.float32)
        for row in range(a + 1, b):
            t = (row - a) / (b - a)
            frame[row] = np.round(top * (1 - t) + bottom * t).astype(np.uint8)


def build(name, cfg, preview):
    x, y, w, h = cfg["crop"]
    decode_vf = f"crop={w}:{h}:{x}:{y}"
    if cfg["denoise"]:
        decode_vf += "," + cfg["denoise"]
    decode_vf += ",scale=in_color_matrix=bt709:in_range=tv,format=rgb24"
    out = OUT / f"rest_{name}.mp4"
    decoder = subprocess.Popen(["ffmpeg", "-v", "error", "-i", str(SRC / cfg["src"]), "-vf", decode_vf,
                                "-frames:v", str(FRAMES), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                               stdout=subprocess.PIPE)
    encoder = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
                                "-s", f"{w}x{h}", "-r", str(FPS), "-i", "-",
                                "-vf", "scale=out_color_matrix=bt709:out_range=tv,format=yuv420p",
                                "-an", "-c:v", "libx264", "-profile:v", "baseline", "-level:v", "3.1",
                                "-preset", "veryslow", "-crf", str(cfg["crf"]), "-refs", "2",
                                "-g", str(FPS * 2), "-keyint_min", str(FPS * 2), "-sc_threshold", "0",
                                "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
                                "-color_range", "tv",
                                "-x264-params", "colorprim=bt709:transfer=bt709:colormatrix=bt709:fullrange=off",
                                "-movflags", "+faststart", "-map_metadata", "-1", str(out)],
                               stdin=subprocess.PIPE)
    mask = feather_mask(w, h, cfg["feather"], cfg["ellipse"])
    flat = np.array(cfg["flat"], dtype=np.float32)
    luts = levels_lut(cfg["levels"]) if cfg["levels"] else None
    size = w * h * 3
    count = 0
    while True:
        raw = decoder.stdout.read(size)
        if len(raw) < size:
            break
        frame = np.frombuffer(raw, np.uint8).reshape(h, w, 3).copy()
        erase_rules(frame, cfg["rules"], y)
        if luts:
            for c in range(3):
                frame[..., c] = luts[c][frame[..., c]]
        blended = flat + (frame.astype(np.float32) - flat) * mask
        encoder.stdin.write(np.clip(np.round(blended), 0, 255).astype(np.uint8).tobytes())
        count += 1
    encoder.stdin.close()
    if decoder.wait() != 0 or encoder.wait() != 0 or count != FRAMES:
        raise SystemExit(f"{name}: ffmpeg failed or frame count {count} != {FRAMES}")
    bytes_ = out.stat().st_size
    print(f"{out.name}: {bytes_:,} bytes, {w}x{h}, {count} frames, flat #{''.join(f'{c:02x}' for c in cfg['flat'])}")
    if preview:
        preview.mkdir(parents=True, exist_ok=True)
        for t in (0, 2.5, 5, 7.5):
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", str(t), "-i", str(out), "-frames:v", "1",
                            str(preview / f"rest_{name}-{t:04.1f}s.png")], check=True)
    return bytes_


def extract_still(name):
    """Finish screen: frame 0 of the finished loop as a small WebP (bt709 tv -> RGB, libwebp does its own YUV)."""
    src = OUT / f"rest_{name}.mp4"
    out = STILLS / f"rest_{name}_still.webp"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(src), "-frames:v", "1",
                    "-vf", "scale=in_color_matrix=bt709:in_range=tv,format=bgra",
                    "-c:v", "libwebp", "-lossless", "0", "-quality", str(STILL_QUALITY), "-compression_level", "6",
                    "-map_metadata", "-1", str(out)], check=True)
    bytes_ = out.stat().st_size
    print(f"{out.name}: {bytes_:,} bytes (frame 0 of {src.name})")
    return bytes_


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", type=pathlib.Path, help="also write a few decoded frames here")
    parser.add_argument("--stills-only", action="store_true", help="only re-extract the finish stills from res/raw")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    STILLS.mkdir(parents=True, exist_ok=True)
    total = 0 if args.stills_only else sum(build(n, c, args.preview) for n, c in SCENES.items())
    stills = sum(extract_still(n) for n in SCENES)
    if not args.stills_only:
        print(f"total {total:,} bytes (loops)")
    print(f"stills {stills:,} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
