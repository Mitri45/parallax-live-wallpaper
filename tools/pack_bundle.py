#!/usr/bin/env python3
"""Pack a background, foreground cutout, and optional grayscale depth map into a .wpbundle archive.

Example:
  python3 tools/pack_bundle.py \
    --bg background_inpainted.png \
    --fg subject_cutout.png \
    --depth depth_anything.png \
    --title "Cyberpunk Rain" \
    --out cyberpunk_rain.wpbundle
"""

import argparse
import io
import json
import zipfile
from pathlib import Path

from PIL import Image, ImageFilter


def build_relief_map(depth_path: Path, fg_img: Image.Image, rw: int = 160, rh: int = 340) -> bytes:
    """Convert a standard grayscale depth map (bright = near, dark = far) into a border-zeroed 2-channel relief.png.

    R channel = foreground depth relief (centered at 128)
    G channel = background depth relief (centered at 128)
    """
    depth = Image.open(depth_path).convert("L").resize((rw, rh), Image.Resampling.BILINEAR)
    # Smooth slightly to avoid sharp depth discontinuities folding the mesh
    depth = depth.filter(ImageFilter.GaussianBlur(radius=2.0))
    alpha = fg_img.split()[-1].resize((rw, rh), Image.Resampling.BILINEAR)

    dpx = depth.load()
    apx = alpha.load()

    # Compute mean depth on foreground vs background for zero-centering each layer
    fg_sum, fg_cnt = 0.0, 0
    bg_sum, bg_cnt = 0.0, 0
    for y in range(rh):
        for x in range(rw):
            val = dpx[x, y] / 255.0
            if apx[x, y] >= 128:
                fg_sum += val
                fg_cnt += 1
            else:
                bg_sum += val
                bg_cnt += 1

    fg_mean = fg_sum / fg_cnt if fg_cnt else 0.5
    bg_mean = bg_sum / bg_cnt if bg_cnt else 0.5

    relief = Image.new("RGB", (rw, rh))
    rpx = relief.load()
    for y in range(rh):
        v = y / max(1, rh - 1)
        edge_y = min(1.0, min(v, 1.0 - v) / 0.10)
        for x in range(rw):
            u = x / max(1, rw - 1)
            edge_x = min(1.0, min(u, 1.0 - u) / 0.10)
            edge = edge_x * edge_y

            val = dpx[x, y] / 255.0
            r_fg = max(-1.0, min(1.0, (val - fg_mean) * 2.2)) * edge
            r_bg = max(-1.0, min(1.0, (val - bg_mean) * 1.8)) * edge

            rc = max(0, min(255, int(round(128 + r_fg * 127))))
            gc = max(0, min(255, int(round(128 + r_bg * 127))))
            rpx[x, y] = (rc, gc, 128)

    buf = io.BytesIO()
    relief.save(buf, "PNG")
    return buf.getvalue()


def main() -> None:
    parser = argparse.ArgumentParser(description="Pack layered assets into a .wpbundle for Parallax Live.")
    parser.add_argument("--bg", required=True, type=Path, help="Clean background plate (JPG or PNG)")
    parser.add_argument("--fg", required=True, type=Path, help="Foreground subject cutout with alpha (PNG or WebP)")
    parser.add_argument("--depth", type=Path, help="Optional grayscale depth map (bright=near, dark=far)")
    parser.add_argument("--sway", type=Path, help="Optional grayscale wind-sway weight map")
    parser.add_argument("--swing", type=Path, help="Optional RGB wind direction/lag map")
    parser.add_argument("--title", default="", help="Display label stored in meta.json")
    parser.add_argument("--style", default="", help="Style tag stored in meta.json")
    parser.add_argument("--tint", default="255,247,232", help="RGB tint for ambient motes (e.g. 186,230,253)")
    parser.add_argument("--max-side", type=int, default=3200,
                        help="Downscale so the longest side is at most this many pixels (0 = keep size). "
                             "Keeps bitmaps under GPU texture limits; the app also subsamples beyond 4096.")
    parser.add_argument("--out", required=True, type=Path, help="Output .wpbundle path")
    args = parser.parse_args()

    try:
        tint = [int(c.strip()) for c in args.tint.split(",")]
    except ValueError:
        parser.error("--tint must be 3 comma-separated integers (R,G,B)")
    if len(tint) != 3 or any(not 0 <= c <= 255 for c in tint):
        parser.error("--tint must be 3 comma-separated integers in 0..255 (R,G,B)")

    if args.sway and args.swing and Image.open(args.sway).size != Image.open(args.swing).size:
        parser.error("--sway and --swing must have the same dimensions")

    bg = Image.open(args.bg).convert("RGB")
    longest = max(bg.size)
    if args.max_side and longest > args.max_side:
        scale = args.max_side / longest
        bg = bg.resize((round(bg.width * scale), round(bg.height * scale)), Image.Resampling.LANCZOS)
    fg = Image.open(args.fg).convert("RGBA")
    if fg.size != bg.size:
        fg = fg.resize(bg.size, Image.Resampling.LANCZOS)

    bg_buf = io.BytesIO()
    bg.save(bg_buf, "JPEG", quality=92)

    fg_buf = io.BytesIO()
    fg.save(fg_buf, "WEBP", quality=92, method=4)

    thumb = bg.convert("RGBA")
    thumb.alpha_composite(fg)
    thumb = thumb.convert("RGB")
    thumb.thumbnail((360, 780), Image.Resampling.LANCZOS)
    thumb_buf = io.BytesIO()
    thumb.save(thumb_buf, "PNG")

    has_relief = args.depth is not None
    meta = {
        "version": 1,
        "character": args.title or args.out.stem,
        "style": args.style,
        "width": bg.width,
        "height": bg.height,
        "tint": tint,
        "relief": has_relief,
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("bg.jpg", bg_buf.getvalue())
        zf.writestr("fg.webp", fg_buf.getvalue())
        zf.writestr("thumb.png", thumb_buf.getvalue())
        zf.writestr("meta.json", json.dumps(meta, indent=2) + "\n")
        if args.depth:
            zf.writestr("relief.png", build_relief_map(args.depth, fg))
        if args.sway:
            zf.write(args.sway, "sway.png")
        if args.swing:
            zf.write(args.swing, "swing.png")

    print(f"Packed {args.out} ({args.out.stat().st_size / 1024:.1f} KiB, relief={has_relief})")


if __name__ == "__main__":
    main()
