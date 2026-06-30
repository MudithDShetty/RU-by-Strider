import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const srcPath = path.join(root, "transparent-image.png");
const drawableDir = path.join(root, "app", "src", "main", "res", "drawable");
const wordmarkOut = path.join(drawableDir, "ru_splash_wordmark.png");
const iconOut = path.join(drawableDir, "ru_splash_icon.png");

const script = `
from PIL import Image
import sys
src = Image.open(sys.argv[1]).convert("RGBA")
bbox = src.getbbox()
if not bbox:
    raise SystemExit("empty image")
wordmark = src.crop(bbox)
wordmark.save(sys.argv[2])

# Square canvas for Android 12 circular splash — generous pad so "ru" stays inside mask.
pad = 0.42
side = int(max(wordmark.width, wordmark.height) * (1 + pad))
canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
x = (side - wordmark.width) // 2
y = (side - wordmark.height) // 2
canvas.paste(wordmark, (x, y), wordmark)
canvas.save(sys.argv[3])
print(f"wordmark {wordmark.size} icon canvas {canvas.size}")
`;

const result = spawnSync("python", ["-c", script, srcPath, wordmarkOut, iconOut], {
  encoding: "utf8",
});
if (result.status !== 0) {
  console.error(result.stderr || result.stdout);
  process.exit(result.status ?? 1);
}
console.log(result.stdout.trim());
console.log(`Wrote ${wordmarkOut}`);
console.log(`Wrote ${iconOut}`);
