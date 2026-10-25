// Puts the app's web part together in one folder for the Android assets (or a browser):
//   node tools/build.mjs [out dir]     (default: dist)
// The page and its scripts as written, MapLibre's own files, and the map styles.

import { cpSync, existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { loadStyles } from './styles.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const out = resolve(process.argv[2] || join(root, 'dist'));
const maplibre = join(root, 'node_modules', 'maplibre-gl', 'dist');

if (!existsSync(maplibre)) {
    console.error(`No MapLibre in ${maplibre}: run "npm ci" in ${root} first.`);
    process.exit(1);
}

rmSync(out, { recursive: true, force: true });
mkdirSync(join(out, 'lib'), { recursive: true });
mkdirSync(join(out, 'styles'), { recursive: true });

cpSync(join(root, 'src'), out, { recursive: true });
for (const f of ['maplibre-gl.mjs', 'maplibre-gl-shared.mjs', 'maplibre-gl-worker.mjs', 'maplibre-gl.css']) {
    cpSync(join(maplibre, f), join(out, 'lib', f));
}
cpSync(join(root, 'node_modules', 'maplibre-gl', 'LICENSE.txt'), join(out, 'lib', 'LICENSE-maplibre.txt'));

const styles = loadStyles(join(root, 'vendor', 'openfreemap'));
for (const [name, style] of Object.entries(styles)) {
    writeFileSync(join(out, 'styles', `${name}.json`), JSON.stringify(style));
}
cpSync(join(root, 'vendor', 'openfreemap', 'LICENSE.md'), join(out, 'styles', 'LICENSE.md'));

console.log(`web app in ${out}`);
