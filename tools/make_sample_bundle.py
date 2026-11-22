#!/usr/bin/env python3
"""Generate a procedural sample scene and app icon so the repo builds out of the box."""

import json
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


def make_icon(out_path: Path, size: int = 192) -> None:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Rounded dark indigo background
    draw.rounded_rectangle([0, 0, size - 1, size - 1], radius=size // 4, fill=(18, 22, 42, 255))

    # Layered parallax cards inside icon
    cx, cy = size // 2, size // 2
    draw.rounded_rectangle(
        [cx - 52, cy - 58, cx + 36, cy + 42],
        radius=16,
        fill=(30, 58, 138, 220),
        outline=(56, 189, 248, 200),
        width=3,
    )
    draw.rounded_rectangle(
        [cx - 32, cy - 38, cx + 56, cy + 60],
        radius=16,
        fill=(79, 70, 229, 245),
        outline=(165, 180, 252, 255),
        width=3,
    )
    # Glowing prism diamond on top layer
    pts = [(cx + 12, cy - 16), (cx + 36, cy + 12), (cx + 12, cy + 40), (cx - 12, cy + 12)]
    draw.polygon(pts, fill=(56, 189, 248, 255))

    out_path.parent.mkdir(parents=True, exist_ok=True)
    img.save(out_path, "PNG")


def make_sample_bundle(out_dir: Path, width: int = 720, height: int = 1560) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)

    # 1. Background: deep twilight sky gradient + layered mountain silhouettes
    bg = Image.new("RGB", (width, height))
    px = bg.load()
    for y in range(height):
        t = y / (height - 1)
        r = int(12 + 26 * (t ** 1.4))
        g = int(16 + 34 * (t ** 1.2))
        b = int(38 + 68 * math.sin(t * math.pi * 0.85))
        for x in range(width):
            px[x, y] = (r, g, b)

    draw_bg = ImageDraw.Draw(bg)
    # Subtle stars
    for i in range(60):
        sx = (i * 137 + 41) % width
        sy = (i * 97 + 23) % int(height * 0.58)
        sr = 1 if i % 3 else 2
        draw_bg.ellipse([sx - sr, sy - sr, sx + sr, sy + sr], fill=(190, 215, 255))

    # Distant mountain range
    pts_far = [(0, height)]
    for x in range(0, width + 1, 20):
        y = int(height * 0.64 - 75 * math.sin(x / 130.0) - 35 * math.cos(x / 55.0))
        pts_far.append((x, y))
    pts_far.append((width, height))
    draw_bg.polygon(pts_far, fill=(24, 32, 66))

    # Mid mountain range
    pts_mid = [(0, height)]
    for x in range(0, width + 1, 20):
        y = int(height * 0.74 - 55 * math.cos(x / 110.0 + 1.1) - 25 * math.sin(x / 45.0))
        pts_mid.append((x, y))
    pts_mid.append((width, height))
    draw_bg.polygon(pts_mid, fill=(16, 22, 46))

    bg.save(out_dir / "bg.jpg", "JPEG", quality=92)

    # 2. Foreground: floating luminous crystal prism with soft glow (RGBA)
    fg = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    cx, cy = width // 2, int(height * 0.48)

    dg = ImageDraw.Draw(glow)
    dg.ellipse([cx - 210, cy - 260, cx + 210, cy + 260], fill=(56, 189, 248, 70))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=48))
    fg.alpha_composite(glow)

    df = ImageDraw.Draw(fg)
    top = (cx, cy - 230)
    right = (cx + 150, cy)
    bottom = (cx, cy + 240)
    left = (cx - 150, cy)
    center = (cx + 10, cy - 12)

    df.polygon([top, right, center], fill=(125, 211, 252, 235))
    df.polygon([top, center, left], fill=(56, 189, 248, 225))
    df.polygon([right, bottom, center], fill=(79, 70, 229, 240))
    df.polygon([left, center, bottom], fill=(49, 46, 129, 240))
    df.line([top, right, bottom, left, top], fill=(224, 242, 254, 240), width=4)
    df.line([top, center, bottom], fill=(224, 242, 254, 180), width=3)
    df.line([left, center, right], fill=(224, 242, 254, 150), width=2)

    fg.save(out_dir / "fg.webp", "WEBP", quality=92, method=4)

    # 3. Relief map (R = foreground depth, G = background depth, centered at 128, zeroed at borders)
    rw, rh = 120, 260
    relief = Image.new("RGB", (rw, rh))
    rpx = relief.load()
    for y in range(rh):
        v = y / (rh - 1)
        # Smooth border taper so edges stay at 128 (0 shift delta)
        edge_y = min(1.0, min(v, 1.0 - v) / 0.12)
        for x in range(rw):
            u = x / (rw - 1)
            edge_x = min(1.0, min(u, 1.0 - u) / 0.12)
            edge = edge_x * edge_y

            # Foreground relief: dome peaking at the prism center
            dx = (u - 0.5) / 0.25
            dy = (v - 0.48) / 0.20
            d2 = dx * dx + dy * dy
            r_fg = max(0.0, 1.0 - d2) * 0.85 * edge

            # Background relief: sky far (-), lower mountains closer (+)
            r_bg = (v - 0.5) * 1.1 * edge

            rc = max(0, min(255, int(round(128 + r_fg * 127))))
            gc = max(0, min(255, int(round(128 + r_bg * 127))))
            rpx[x, y] = (rc, gc, 128)

    relief.save(out_dir / "relief.png", "PNG")

    # 4. Composite thumbnail
    thumb = bg.convert("RGBA")
    thumb.alpha_composite(fg)
    thumb = thumb.convert("RGB")
    thumb.thumbnail((360, 780), Image.Resampling.LANCZOS)
    thumb.save(out_dir / "thumb.png", "PNG")

    # 5. Metadata
    meta = {
        "version": 1,
        "character": "Sample Prism",
        "style": "Procedural Demo",
        "width": width,
        "height": height,
        "tint": [186, 230, 253],
        "relief": True,
    }
    (out_dir / "meta.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: make_sample_bundle.py OUT_BUNDLE_DIR [OUT_ICON_PNG]", file=sys.stderr)
        sys.exit(2)
    make_sample_bundle(Path(sys.argv[1]))
    if len(sys.argv) >= 3:
        make_icon(Path(sys.argv[2]))
    print(f"Generated sample bundle in {sys.argv[1]}")
