"""Builds the website's images and font from the app's own resources.

Run from the repository root: python site/tools/make_assets.py
Writes site/assets/: screenshots as WebP, the icon (PNG sizes), the Open Graph cards (EN, IT) and
the Manrope font as WOFF2.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "site" / "assets"
SHOTS = ROOT / "docs" / "screenshots"
FONT = ROOT / "app" / "src" / "main" / "res" / "font" / "manrope.ttf"

# The launcher icon's two blues (res/drawable/ic_launcher_background.xml)
BLUE_TOP = (0x0B, 0x63, 0xCE)
BLUE_BOTTOM = (0x3D, 0x8F, 0xEA)


def gradient(w, h):
    """Diagonal gradient from the top left to the bottom right, like the launcher icon."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w + y / h) / 2
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(BLUE_TOP, BLUE_BOTTOM))
    return img


def draw_camera(draw, cx, cy, scale, width):
    """The icon's camera (res/drawable/ic_launcher_foreground.xml) on its 24-unit grid, centred."""
    def p(x, y):
        return cx + (x - 12) * scale, cy + (y - 12) * scale
    white = (255, 255, 255)
    for r in (7, 2.5):
        x0, y0 = p(12 - r, 10 - r)
        x1, y1 = p(12 + r, 10 + r)
        draw.ellipse((x0, y0, x1, y1), outline=white, width=width)
    draw.line([p(9, 17), p(8, 21), p(16, 21), p(15, 17)], fill=white, width=width, joint="curve")
    for x, y in ((8, 21), (16, 21), (9, 17), (15, 17)):
        px, py = p(x, y)
        draw.ellipse((px - width / 2, py - width / 2, px + width / 2, py + width / 2), fill=white)


def icon(size):
    big = size * 4
    img = gradient(big, big)
    draw_camera(ImageDraw.Draw(img), big / 2, big / 2, big / 108 * 2.4, max(2, round(big / 108 * 2.4 * 2)))
    return img.resize((size, size), Image.LANCZOS)


def font(size, weight):
    f = ImageFont.truetype(str(FONT), size)
    try:
        f.set_variation_by_axes([weight])
    except OSError:
        pass
    return f


def og_card(path, title, subtitle, lines, badge):
    w, h = 1200, 630
    img = gradient(w, h)
    draw = ImageDraw.Draw(img)
    # Icon top left
    ic = icon(112)
    mask = Image.new("L", ic.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 111, 111), radius=26, fill=255)
    img.paste(ic, (72, 72), mask)
    draw.rounded_rectangle((72, 72, 183, 183), radius=26, outline=(255, 255, 255, 90), width=2)
    draw.text((72, 222), title, font=font(92, 800), fill="white")
    draw.text((76, 330), subtitle, font=font(38, 600), fill=(220, 234, 255))
    y = 410
    for line in lines:
        draw.text((76, y), line, font=font(30, 500), fill="white")
        y += 46
    # Kept left of the screenshots (they start at x = 760)
    size = 24
    while draw.textlength(badge, font=font(size, 600)) > 650:
        size -= 1
    draw.text((76, 560), badge, font=font(size, 600), fill=(200, 222, 255))
    # Two screenshots on the right, in rounded phone frames
    for i, name in enumerate(("home", "viewer360")):
        shot = Image.open(SHOTS / f"{name}.png").convert("RGB")
        sh = 500
        sw = round(shot.width * sh / shot.height)
        shot = shot.resize((sw, sh), Image.LANCZOS)
        x = 760 + i * 200
        y0 = 65 + i * 40
        frame = Image.new("L", (sw, sh), 0)
        ImageDraw.Draw(frame).rounded_rectangle((0, 0, sw - 1, sh - 1), radius=26, fill=255)
        shadow = Image.new("RGBA", (sw + 40, sh + 40), (0, 0, 0, 0))
        ImageDraw.Draw(shadow).rounded_rectangle((20, 26, sw + 20, sh + 26), radius=30, fill=(0, 30, 80, 90))
        img.paste(shadow, (x - 20, y0 - 20), shadow)
        img.paste(shot, (x, y0), frame)
    img.save(path, optimize=True)


def main():
    (OUT / "screenshots").mkdir(parents=True, exist_ok=True)
    for png in sorted(SHOTS.glob("*.png")):
        Image.open(png).convert("RGB").save(OUT / "screenshots" / f"{png.stem}.webp", quality=82, method=6)
    icon(512).save(OUT / "icon-512.png", optimize=True)
    icon(192).save(OUT / "icon-192.png", optimize=True)
    icon(180).save(OUT / "apple-touch-icon.png", optimize=True)
    icon(32).save(OUT / "favicon-32.png", optimize=True)
    og_card(OUT / "og-en.png", "Revive360", "for Samsung Gear 360",
            ["Your Gear 360 works again,", "with any Android phone."],
            "Free · Open source · No account · Not affiliated with Samsung")
    og_card(OUT / "og-it.png", "Revive360", "per Samsung Gear 360",
            ["La tua Gear 360 torna a funzionare,", "con qualsiasi telefono Android."],
            "Gratis · Open source · Senza account · Non affiliata a Samsung")
    f = TTFont(str(FONT))
    f.flavor = "woff2"
    f.save(str(OUT / "manrope.woff2"))
    print("assets written to", OUT)


if __name__ == "__main__":
    main()
