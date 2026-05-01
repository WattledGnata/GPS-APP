#!/usr/bin/env python3
from __future__ import annotations

from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


SOURCE_IMAGE = Path(__file__).resolve().parents[1] / "track-source-pdfs" / "chengdu-tianfu-51gt3-300dpi.png"
OUT_DIR = Path(__file__).resolve().parent / "highres"
CANVAS = (3840, 2160)
WORK_WIDTH = 2600
MARGIN = 260
DERIVED_SIZES = {
    "hero": (3840, 2160),
    "card": (1440, 810),
    "thumb": (720, 405),
}

BG = (7, 8, 13, 255)
SURFACE = (13, 15, 22, 255)
GRID = (32, 36, 48, 92)
CYAN = (103, 232, 249, 238)
CYAN_CORE = (217, 252, 255, 238)
PURPLE = (155, 92, 255, 190)
START_MARKER_CENTER = (1765, 1380)


def largest_component(mask: list[list[bool]], width: int, height: int) -> list[tuple[int, int]]:
    seen = [[False for _ in range(width)] for _ in range(height)]
    best: list[tuple[int, int]] = []

    for y in range(height):
        for x in range(width):
            if not mask[y][x] or seen[y][x]:
                continue

            queue: deque[tuple[int, int]] = deque([(x, y)])
            seen[y][x] = True
            component: list[tuple[int, int]] = []

            while queue:
                cx, cy = queue.popleft()
                component.append((cx, cy))
                for nx in (cx - 1, cx, cx + 1):
                    for ny in (cy - 1, cy, cy + 1):
                        if nx == cx and ny == cy:
                            continue
                        if nx < 0 or ny < 0 or nx >= width or ny >= height:
                            continue
                        if mask[ny][nx] and not seen[ny][nx]:
                            seen[ny][nx] = True
                            queue.append((nx, ny))

            if len(component) > len(best):
                best = component

    return best


def extract_main_track_mask(source: Image.Image) -> Image.Image:
    if source.width > WORK_WIDTH:
        height = int(source.height * WORK_WIDTH / source.width)
        source = source.resize((WORK_WIDTH, height), Image.Resampling.LANCZOS)

    rgb = source.convert("RGB")
    width, height = rgb.size
    pixels = rgb.load()

    raw: list[list[bool]] = []
    for y in range(height):
        row: list[bool] = []
        for x in range(width):
            r, g, b = pixels[x, y]
            gray = r * 0.299 + g * 0.587 + b * 0.114
            row.append(gray < 96)
        raw.append(row)

    component = largest_component(raw, width, height)
    if not component:
        raise RuntimeError("No main track component found")

    mask = Image.new("L", (width, height), 0)
    mask_pixels = mask.load()
    for x, y in component:
        mask_pixels[x, y] = 255

    mask = mask.filter(ImageFilter.MaxFilter(3))
    return mask.filter(ImageFilter.GaussianBlur(0.35))


def fit_to_canvas(mask: Image.Image) -> Image.Image:
    bbox = mask.getbbox()
    if bbox is None:
        raise RuntimeError("Empty mask")

    cropped = mask.crop(bbox)
    max_w = CANVAS[0] - MARGIN * 2
    max_h = CANVAS[1] - MARGIN * 2
    scale = min(max_w / cropped.width, max_h / cropped.height)
    size = (max(1, int(cropped.width * scale)), max(1, int(cropped.height * scale)))
    resized = cropped.resize(size, Image.Resampling.LANCZOS)

    canvas = Image.new("L", CANVAS, 0)
    x = (CANVAS[0] - size[0]) // 2
    y = (CANVAS[1] - size[1]) // 2
    canvas.paste(resized, (x, y))
    return canvas


def render_track(mask: Image.Image) -> Image.Image:
    result = Image.new("RGBA", CANVAS, (0, 0, 0, 0))

    layers = [
        (mask.filter(ImageFilter.GaussianBlur(28)), (36, 112, 140), 0.48),
        (mask.filter(ImageFilter.GaussianBlur(10)), (103, 232, 249), 0.64),
        (mask.filter(ImageFilter.MaxFilter(7)), CYAN[:3], 0.88),
        (mask.filter(ImageFilter.MinFilter(5)), CYAN_CORE[:3], 0.7),
    ]

    for alpha_mask, color, strength in layers:
        layer = Image.new("RGBA", CANVAS, (*color, 0))
        layer.putalpha(alpha_mask.point(lambda p, s=strength: min(238, int(p * s))))
        result.alpha_composite(layer)

    return result


def add_start_finish_marker(track: Image.Image) -> None:
    marker_layer = Image.new("RGBA", CANVAS, (0, 0, 0, 0))
    draw = ImageDraw.Draw(marker_layer, "RGBA")

    cx, cy = START_MARKER_CENTER
    width = 82
    height = 122
    cell = height // 6
    left = cx - width // 2
    top = cy - height // 2

    draw.rounded_rectangle(
        (left - 8, top - 8, left + width + 8, top + height + 8),
        radius=5,
        fill=(7, 8, 13, 130),
        outline=(103, 232, 249, 180),
        width=4,
    )

    for row in range(6):
        for col in range(4):
            fill = (236, 236, 242, 245) if (row + col) % 2 == 0 else (7, 8, 13, 230)
            draw.rectangle(
                (
                    left + col * cell,
                    top + row * cell,
                    left + (col + 1) * cell,
                    top + (row + 1) * cell,
                ),
                fill=fill,
            )

    draw.line((cx, cy - height // 2 - 38, cx, cy + height // 2 + 38), fill=(103, 232, 249, 210), width=8)
    glow = marker_layer.filter(ImageFilter.GaussianBlur(10))
    track.alpha_composite(glow)
    track.alpha_composite(marker_layer)


def draw_background() -> Image.Image:
    bg = Image.new("RGBA", CANVAS, SURFACE)
    draw = ImageDraw.Draw(bg, "RGBA")

    for x in range(0, CANVAS[0], 120):
        draw.line((x, 0, x, CANVAS[1]), fill=GRID, width=2)
    for y in range(0, CANVAS[1], 120):
        draw.line((0, y, CANVAS[0], y), fill=GRID, width=2)

    for i in range(11):
        x = CANVAS[0] - 840 + i * 76
        draw.line((x, 148, x + 190, 0), fill=(155, 92, 255, 88), width=4)

    draw.rectangle((0, 0, CANVAS[0] - 1, CANVAS[1] - 1), outline=(48, 52, 66, 255), width=4)
    draw.line((0, 0, 280, 0), fill=PURPLE, width=7)
    draw.line((0, 0, 0, 280), fill=PURPLE, width=7)
    draw.line((CANVAS[0] - 280, CANVAS[1] - 1, CANVAS[0] - 1, CANVAS[1] - 1), fill=PURPLE, width=7)
    draw.line((CANVAS[0] - 1, CANVAS[1] - 280, CANVAS[0] - 1, CANVAS[1] - 1), fill=PURPLE, width=7)
    return bg


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    source = Image.open(SOURCE_IMAGE)
    mask = fit_to_canvas(extract_main_track_mask(source))
    track = render_track(mask)
    add_start_finish_marker(track)

    transparent_path = OUT_DIR / "chengdu-tianfu-transparent-3840.png"
    preview_path = OUT_DIR / "chengdu-tianfu-preview-3840.png"

    track.save(transparent_path)

    preview = draw_background()
    preview.alpha_composite(track)
    preview.save(preview_path)

    for name, size in DERIVED_SIZES.items():
        if size == CANVAS:
            continue
        suffix = f"{size[0]}"
        track.resize(size, Image.Resampling.LANCZOS).save(OUT_DIR / f"chengdu-tianfu-transparent-{suffix}.png")
        preview.resize(size, Image.Resampling.LANCZOS).save(OUT_DIR / f"chengdu-tianfu-preview-{suffix}.png")


if __name__ == "__main__":
    main()
