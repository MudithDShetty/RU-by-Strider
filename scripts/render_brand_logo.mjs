import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { Resvg } from "@resvg/resvg-js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, "..");
const svgPath = path.join(root, "image.svg");
const drawableDir = path.join(root, "app", "src", "main", "res", "drawable");

/** Spiral paths extend slightly outside the nominal 1254 canvas — pad viewBox so nothing clips. */
const VIEW_BOX = "-100 -100 1454 1454";
const RENDER_SIZE = 1024;

function readSvg() {
  let svg = fs.readFileSync(svgPath, "utf8");
  svg = svg.replace(/viewBox="[^"]*"/, "");
  svg = svg.replace(
    /<svg([^>]*)>/,
    `<svg$1 viewBox="${VIEW_BOX}">`,
  );
  return svg;
}

function renderPng(svg) {
  const resvg = new Resvg(svg, {
    fitTo: { mode: "width", value: RENDER_SIZE },
    background: "transparent",
  });
  return resvg.render().asPng();
}

function trimWithPadding(pngBytes, padFraction) {
  const script = `
from PIL import Image
import io, sys
pad = float(sys.argv[1])
im = Image.open(io.BytesIO(open(0, "rb").read())).convert("RGBA")
bbox = im.getbbox()
if not bbox:
    sys.stdout.buffer.write(im.tobytes())
    raise SystemExit(0)
x0, y0, x1, y1 = bbox
w, h = x1 - x0, y1 - y0
pad_x = max(1, int(w * pad))
pad_y = max(1, int(h * pad))
out = Image.new("RGBA", (w + 2 * pad_x, h + 2 * pad_y), (0, 0, 0, 0))
out.paste(im.crop(bbox), (pad_x, pad_y))
out.save(sys.stdout.buffer, format="PNG")
`;
  const result = spawnSync("python", ["-c", script, String(padFraction)], {
    input: pngBytes,
    stdio: ["pipe", "pipe", "pipe"],
  });
  if (result.status !== 0) {
    throw new Error(result.stderr?.toString() || "trim failed");
  }
  return result.stdout;
}

const svg = readSvg();
const rawPng = renderPng(svg);

const brandLogo = trimWithPadding(rawPng, 0.04);
fs.writeFileSync(path.join(drawableDir, "ru_brand_logo.png"), brandLogo);

/** Extra padding keeps spiral tips inside Android 12 circular splash mask. */
const splashIcon = trimWithPadding(rawPng, 0.18);
fs.writeFileSync(path.join(drawableDir, "ru_splash_brand_icon.png"), splashIcon);

console.log(`Wrote ru_brand_logo.png (${brandLogo.length} bytes)`);
console.log(`Wrote ru_splash_brand_icon.png (${splashIcon.length} bytes)`);
