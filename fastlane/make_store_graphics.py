"""Builds the Google Play graphics from the app's screenshots, in the website's style.

Run from the repository root: python fastlane/make_store_graphics.py
Writes fastlane/metadata/android/<lang>/images/: icon.png (512x512), featureGraphic.png (1024x500)
and phoneScreenshots/N-name.jpg (1080x1920, 9:16, with a caption).
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "site" / "tools"))
from make_assets import SHOTS, font, gradient, icon  # noqa: E402

META = ROOT / "fastlane" / "metadata" / "android"

TEXT = {
    "en-US": {
        "for": "for Gear 360",
        "tag": "Your Gear 360 works again, with any Android phone.",
        "shots": [
            ("home", "Shoot from your phone"),
            ("viewer360", "Real 360° photos, or normal ones"),
            ("gallery", "Your photos and videos"),
            ("settings", "All the camera's settings"),
            ("liveview", "See it live"),
        ],
    },
    "it-IT": {
        "for": "per Gear 360",
        "tag": "La tua Gear 360 torna a funzionare, con qualsiasi Android.",
        "shots": [
            ("home", "Scatta dal telefono"),
            ("viewer360", "Foto a 360°, o normali"),
            ("gallery", "Le tue foto e i tuoi video"),
            ("settings", "Tutte le impostazioni"),
            ("liveview", "Guarda in diretta"),
        ],
    },
}


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), radius=radius, fill=255)
    return mask


def paste_shot(canvas, shot, x, y, radius):
    shadow = Image.new("RGBA", (shot.width + 60, shot.height + 60), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((30, 40, shot.width + 30, shot.height + 40), radius=radius + 6, fill=(0, 30, 80, 80))
    canvas.paste(shadow, (x - 30, y - 30), shadow)
    canvas.paste(shot, (x, y), rounded(shot, radius))


def centered(draw, y, text, f, fill, width):
    w = draw.textlength(text, font=f)
    draw.text(((width - w) / 2, y), text, font=f, fill=fill)


def screenshot(name, caption, out):
    w, h = 1080, 1920
    img = gradient(w, h)
    draw = ImageDraw.Draw(img)
    size = 76
    while draw.textlength(caption, font=font(size, 800)) > w - 120:
        size -= 2
    centered(draw, 120, caption, font(size, 800), "white", w)
    shot = Image.open(SHOTS / f"{name}.png").convert("RGB")
    if shot.height > shot.width * 1.5:
        sh = 1560
        sw = round(shot.width * sh / shot.height)
    else:
        sw = 940
        sh = round(shot.height * sw / shot.width)
    shot = shot.resize((sw, sh), Image.LANCZOS)
    top = 290 if sh > 1000 else 560
    paste_shot(img, shot, (w - sw) // 2, top, 44)
    img.save(out, quality=90)


def feature_graphic(t, out):
    w, h = 1024, 500
    img = gradient(w, h)
    draw = ImageDraw.Draw(img)
    ic = icon(120)
    img.paste(ic, (60, 60), rounded(ic, 28))
    draw.text((60, 200), "Revive360", font=font(80, 800), fill="white")
    draw.text((64, 296), t["for"], font=font(34, 600), fill=(220, 234, 255))
    tag = t["tag"]
    size = 26
    while draw.textlength(tag, font=font(size, 500)) > 560:
        size -= 1
    draw.text((64, 360), tag, font=font(size, 500), fill="white")
    for i, name in enumerate(("viewer360", "home")):
        shot = Image.open(SHOTS / f"{name}.png").convert("RGB")
        sh = 400
        sw = round(shot.width * sh / shot.height)
        shot = shot.resize((sw, sh), Image.LANCZOS)
        paste_shot(img, shot, 680 + i * 150, 40 + i * 60, 22)
    img.save(out, optimize=True)


def main():
    for lang, t in TEXT.items():
        images = META / lang / "images"
        shots = images / "phoneScreenshots"
        shots.mkdir(parents=True, exist_ok=True)
        for old in shots.glob("*.jpg"):
            old.unlink()
        icon(512).save(images / "icon.png", optimize=True)
        feature_graphic(t, images / "featureGraphic.png")
        for i, (name, caption) in enumerate(t["shots"], 1):
            screenshot(name, caption, shots / f"{i}-{name}.jpg")
    print("graphics written to", META)


if __name__ == "__main__":
    main()
