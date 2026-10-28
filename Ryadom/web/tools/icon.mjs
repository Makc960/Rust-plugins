// Draws store/icon.svg into the 512 px PNG the stores want:
//   node tools/icon.mjs [chromium path]

import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { chromium } from 'playwright-core';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const svg = readFileSync(join(root, 'store', 'icon.svg'), 'utf8');
const browser = await chromium.launch({ executablePath: process.argv[2] || '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
const page = await browser.newPage({ viewport: { width: 512, height: 512 } });
await page.setContent(`<html><body style="margin:0">${svg}</body></html>`);
await page.screenshot({ path: join(root, 'store', 'icon-512.png'), omitBackground: false });
await browser.close();
console.log('store/icon-512.png');
