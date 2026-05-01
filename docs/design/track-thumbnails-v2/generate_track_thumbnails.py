#!/usr/bin/env python3
from __future__ import annotations

from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


SOURCE_DIR = Path("/Users/wattledgnata/traeprojects/laptime/miniprogram/track_images")
OUT_DIR = Path(__file__).resolve().parent
TRANSPARENT_DIR = OUT_DIR / "transparent"
PREVIEW_DIR = OUT_DIR / "preview"
CANVAS = (960, 540)
MARGIN = 92

BG = (7, 8, 13, 255)
SURFACE = (13, 15, 22, 255)
GRID = (32, 36, 48, 92)
CYAN = (103, 232, 249, 235)
CYAN_CORE = (217, 252, 255, 235)
PURPLE = (155, 92, 255, 190)


def connected_components(mask: list[list[bool]], width: int, height: int) -> list[list[tuple[int, int]]]:
    seen = [[False for _ in range(width)] for _ in range(height)]
    components: list[list[tuple[int, int]]] = []

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

            components.append(component)

    return components


def extract_track_mask(image: Image.Image) -> Image.Image:
    rgb = image.convert("RGB")
    width, height = rgb.size
    pixels = rgb.load()

    # Original files are mostly white background with black track strokes.
    # Keep only dark, substantial connected components so corner numbers and labels fall away.
    raw: list[list[bool]] = []
    for y in range(height):
        row: list[bool] = []
        for x in range(width):
            r, g, b = pixels[x, y]
            gray = r * 0.299 + g * 0.587 + b * 0.114
            row.append(gray < 182)
        raw.append(row)

    components = connected_components(raw, width, height)
    if not components:
        raise RuntimeError("No dark track pixels found")

    total = width * height
    min_area = max(240, int(total * 0.0012))
    kept = Image.new("L", (width, height), 0)
    kept_pixels = kept.load()

    for component in sorted(components, key=len, reverse=True):
        if len(component) < min_area:
            continue
        xs = [p[0] for p in component]
        ys = [p[1] for p in component]
        component_width = max(xs) - min(xs) + 1
        component_height = max(ys) - min(ys) + 1
        if component_width < width * 0.13 and component_height < height * 0.13:
            continue
        for x, y in component:
            kept_pixels[x, y] = 255

    mask = kept
    mask = mask.filter(ImageFilter.MaxFilter(3))
    mask = mask.filter(ImageFilter.GaussianBlur(0.45))
    return mask


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

    glow_wide = mask.filter(ImageFilter.GaussianBlur(14))
    glow_mid = mask.filter(ImageFilter.GaussianBlur(5))
    edge = mask.filter(ImageFilter.MaxFilter(5))
    core = mask.filter(ImageFilter.MinFilter(3))

    wide_layer = Image.new("RGBA", CANVAS, (36, 112, 140, 0))
    wide_layer.putalpha(glow_wide.point(lambda p: int(p * 0.52)))
    result.alpha_composite(wide_layer)

    mid_layer = Image.new("RGBA", CANVAS, (103, 232, 249, 0))
    mid_layer.putalpha(glow_mid.point(lambda p: int(p * 0.72)))
    result.alpha_composite(mid_layer)

    edge_layer = Image.new("RGBA", CANVAS, CYAN)
    edge_layer.putalpha(edge.point(lambda p: min(220, int(p * 0.95))))
    result.alpha_composite(edge_layer)

    core_layer = Image.new("RGBA", CANVAS, CYAN_CORE)
    core_layer.putalpha(core.point(lambda p: min(190, int(p * 0.7))))
    result.alpha_composite(core_layer)

    return result


def draw_background() -> Image.Image:
    bg = Image.new("RGBA", CANVAS, BG)
    draw = ImageDraw.Draw(bg, "RGBA")
    draw.rectangle((0, 0, CANVAS[0], CANVAS[1]), fill=SURFACE)

    for x in range(0, CANVAS[0], 48):
        draw.line((x, 0, x, CANVAS[1]), fill=GRID, width=1)
    for y in range(0, CANVAS[1], 48):
        draw.line((0, y, CANVAS[0], y), fill=GRID, width=1)

    for i in range(9):
        x = CANVAS[0] - 230 + i * 24
        draw.line((x, 40, x + 70, 0), fill=(155, 92, 255, 92), width=2)

    draw.rectangle((0, 0, CANVAS[0] - 1, CANVAS[1] - 1), outline=(48, 52, 66, 255), width=2)
    draw.line((0, 0, 72, 0), fill=PURPLE, width=3)
    draw.line((0, 0, 0, 72), fill=PURPLE, width=3)
    draw.line((CANVAS[0] - 72, CANVAS[1] - 1, CANVAS[0] - 1, CANVAS[1] - 1), fill=PURPLE, width=3)
    draw.line((CANVAS[0] - 1, CANVAS[1] - 72, CANVAS[0] - 1, CANVAS[1] - 1), fill=PURPLE, width=3)
    return bg


def make_contact_sheet(paths: list[Path]) -> None:
    thumb_w, thumb_h = 360, 203
    cols = 3
    rows = (len(paths) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * thumb_w, rows * (thumb_h + 34)), BG)
    draw = ImageDraw.Draw(sheet)

    for index, path in enumerate(paths):
        image = Image.open(path).convert("RGBA").resize((thumb_w, thumb_h), Image.Resampling.LANCZOS)
        x = (index % cols) * thumb_w
        y = (index // cols) * (thumb_h + 34)
        sheet.alpha_composite(image, (x, y))
        draw.text((x + 14, y + thumb_h + 8), path.stem, fill=(236, 236, 242, 255))

    sheet.save(OUT_DIR / "contact-sheet.png")


def main() -> None:
    TRANSPARENT_DIR.mkdir(parents=True, exist_ok=True)
    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)

    preview_paths: list[Path] = []
    for source in sorted(SOURCE_DIR.glob("*.jpg")):
        source_image = Image.open(source)
        mask = fit_to_canvas(extract_track_mask(source_image))
        track = render_track(mask)

        transparent_path = TRANSPARENT_DIR / f"{source.stem}.png"
        preview_path = PREVIEW_DIR / f"{source.stem}.png"

        track.save(transparent_path)

        preview = draw_background()
        preview.alpha_composite(track)
        preview.save(preview_path)
        preview_paths.append(preview_path)

    make_contact_sheet(preview_paths)


if __name__ == "__main__":
    main()
