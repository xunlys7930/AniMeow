"""Generate Android mipmap PNGs for alternative launcher icons.

Reads assets/raw/icon_02.png ~ icon_18.png and writes resized PNGs into
android/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_NN.png
"""
import os
from PIL import Image

DENSITIES = {
    "mdpi":   48,
    "hdpi":   72,
    "xhdpi":  96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

SRC_DIR = os.path.join("assets", "raw")
RES_DIR = os.path.join("android", "app", "src", "main", "res")

def main():
    total = 0
    for n in range(2, 19):
        src = os.path.join(SRC_DIR, f"icon_{n:02d}.png")
        if not os.path.exists(src):
            print(f"SKIP missing: {src}")
            continue
        img = Image.open(src).convert("RGBA")
        for density, size in DENSITIES.items():
            out_dir = os.path.join(RES_DIR, f"mipmap-{density}")
            os.makedirs(out_dir, exist_ok=True)
            out_path = os.path.join(out_dir, f"ic_launcher_{n:02d}.png")
            resized = img.resize((size, size), Image.LANCZOS)
            resized.save(out_path, "PNG", optimize=True)
            total += 1
        print(f"icon_{n:02d}.png -> {len(DENSITIES)} densities")
    print(f"Done. Wrote {total} files.")

if __name__ == "__main__":
    main()
