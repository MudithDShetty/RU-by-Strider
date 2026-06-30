import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Resvg } from "@resvg/resvg-js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, "..");
const svgPath = path.join(root, "light mode logo.svg");
const outPath = path.join(root, "app", "src", "main", "res", "drawable", "ic_launcher_icon.png");

let svg = fs.readFileSync(svgPath, "utf8");
// Background is supplied by adaptive-icon layer; keep foreground transparent.
svg = svg.replace(
  /<path d="M0 0 C413\.82 0[^"]+" fill="#FCF5E4" transform="translate\(0,0\)"\/>\s*/,
  "",
);
if (!svg.includes("viewBox")) {
  svg = svg.replace(
    '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" width="1254" height="1254">',
    '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" width="1254" height="1254" viewBox="0 0 1254 1254">',
  );
}

const size = 1024;
const resvg = new Resvg(svg, {
  fitTo: { mode: "width", value: size },
  background: "transparent",
});
const png = resvg.render().asPng();
fs.writeFileSync(outPath, png);
console.log(`Wrote ${outPath} (${png.length} bytes)`);
