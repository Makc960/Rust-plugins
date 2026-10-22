// Renders an SVG file to a transparent PNG at a given scale with headless Chromium.
// usage: node render_svg.js in.svg out.png scale width height
const fs = require('fs');
const { chromium } = require('playwright');
(async () => {
  const [svgPath, out, scaleArg, wArg, hArg] = process.argv.slice(2);
  const scale = parseFloat(scaleArg), w = parseFloat(wArg), h = parseFloat(hArg);
  const W = Math.ceil(w * scale), H = Math.ceil(h * scale);
  const b64 = fs.readFileSync(svgPath).toString('base64');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });
  await page.setContent(`<html><head><style>html,body{margin:0;background:transparent}</style></head><body><img id="i" src="data:image/svg+xml;base64,${b64}" style="width:${W}px;height:${H}px;display:block"></body></html>`);
  await page.waitForFunction(() => document.getElementById('i').complete);
  await page.screenshot({ path: out, omitBackground: true, clip: { x: 0, y: 0, width: W, height: H } });
  await browser.close();
})();
