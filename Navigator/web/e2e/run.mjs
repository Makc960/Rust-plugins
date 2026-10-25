// Runs the app in Chromium against the made-up town (city.mjs) and takes screenshots of each screen:
//   node e2e/run.mjs <built app dir> <screenshots dir> [chromium path]
// Every server the app talks to is answered here, so it needs no internet.

import { createReadStream, existsSync, mkdirSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, resolve } from 'node:path';

import { chromium } from 'playwright-core';

import { CENTER, cityGeojson, cityStyle, grid, reverseAnswer, routeAnswer, searchAnswer } from './city.mjs';

const [appDir, shotsDir, browserPath = '/opt/pw-browsers/chromium-1194/chrome-linux/chrome'] = process.argv.slice(2).map((p, i) => (i < 2 ? resolve(p) : p));
mkdirSync(shotsDir, { recursive: true });

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.md': 'text/plain' };

const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/test/city.geojson') return send(res, 'application/json', JSON.stringify(cityGeojson()));
    if (url.pathname.startsWith('/test/style-')) return send(res, 'application/json', JSON.stringify(cityStyle(url.pathname.includes('night') ? 'night' : 'day')));
    const file = join(appDir, decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname));
    if (!file.startsWith(appDir) || !existsSync(file) || statSync(file).isDirectory()) {
        res.writeHead(404);
        return res.end();
    }
    res.writeHead(200, { 'content-type': TYPES[extname(file)] || 'application/octet-stream' });
    createReadStream(file).pipe(res);
});
function send(res, type, body) {
    res.writeHead(200, { 'content-type': type });
    res.end(body);
}
await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
const base = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch({
    executablePath: browserPath,
    args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});

const problems = [];

async function phone({ landscape = false, theme = 'day' } = {}) {
    const size = landscape ? { width: 860, height: 400 } : { width: 400, height: 860 };
    const context = await browser.newContext({
        viewport: size, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'ru-RU',
        geolocation: { latitude: CENTER[1], longitude: CENTER[0], accuracy: 12 }, permissions: ['geolocation'],
    });
    await context.addInitScript((t) => {
        localStorage.setItem('shturman.v1', JSON.stringify({ settings: { theme: t, demoSpeed: 4 }, view: { center: [37.6176, 55.7558], zoom: 15 } }));
        window.speechSynthesis && (window.speechSynthesis.speak = (u) => { (window.__said ||= []).push(u.text); });
    }, theme);
    const page = await context.newPage();
    page.on('console', (m) => { if (m.type() === 'error') problems.push(`console: ${m.text()}`); });
    page.on('pageerror', (e) => problems.push(`page: ${e.message}`));
    await page.route('https://photon.komoot.io/**', (route) => {
        const url = new URL(route.request().url());
        const body = url.pathname === '/reverse' ? reverseAnswer([+url.searchParams.get('lon'), +url.searchParams.get('lat')]) : searchAnswer();
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body), headers: { 'access-control-allow-origin': '*' } });
    });
    await page.route('https://valhalla1.openstreetmap.de/**', (route) =>
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(routeAnswer()), headers: { 'access-control-allow-origin': '*' } }));
    await page.goto(`${base}/index.html?style=${encodeURIComponent(`${base}/test/style-${theme}.json`)}`);
    await page.waitForFunction(() => window.__app && window.__app.map.map.isStyleLoaded());
    await page.waitForTimeout(800);
    return { context, page };
}

async function shot(page, name) {
    await page.waitForTimeout(600);
    await page.screenshot({ path: join(shotsDir, `${name}.png`) });
    console.log(`shot ${name}`);
}

try {
    // Day, upright: every screen in turn.
    {
        const { context, page } = await phone();
        await shot(page, '01_map');
        await page.click('#search-open');
        await page.fill('#search-input', 'кафе');
        await page.waitForSelector('#search-results .row');
        await shot(page, '02_search');
        await page.click('#search-results .row');
        await page.waitForSelector('#sheet .actions');
        await shot(page, '03_place');
        await page.click('[data-act="route"]');
        await page.waitForSelector('#sheet .option');
        await page.waitForTimeout(1200);
        await shot(page, '04_routes');
        await page.click('[data-act="demo"]');
        await page.waitForSelector('#banner .dist');
        await page.waitForTimeout(4500);
        await shot(page, '05_driving');
        // On to where the lanes show, before the turn onto проспект Мира.
        await page.waitForFunction(() => {
            const n = window.__app.S.nav;
            return n && n.progress && n.progress.next === 3 && n.progress.toNext < 300;
        }, null, { timeout: 60000 });
        await shot(page, '06_lanes');
        await page.click('[data-act="overview"]');
        await page.waitForTimeout(1000);
        await shot(page, '07_overview');
        await page.click('#resume-btn');
        await page.waitForFunction(() => window.__app.S.nav && window.__app.S.nav.arrived, null, { timeout: 90000 });
        await shot(page, '08_arrived');
        const said = await page.evaluate(() => window.__said || []);
        console.log('said:', said.join(' | '));
        await page.click('[data-act="done"]');
        await page.click('#menu-btn');
        await page.waitForSelector('#settings .setting');
        await shot(page, '09_settings');
        await context.close();
    }
    // Night.
    {
        const { context, page } = await phone({ theme: 'night' });
        await page.evaluate((c) => window.__app.openPreview({ title: 'Кофейня «Утро»', subtitle: 'Набережная улица, 12', lon: c[0], lat: c[1], id: 'x' }), grid(9, -7));
        await page.waitForSelector('#sheet .option');
        await page.waitForTimeout(1000);
        await shot(page, '10_night_routes');
        await page.click('[data-act="demo"]');
        await page.waitForTimeout(5000);
        await shot(page, '11_night_driving');
        await context.close();
    }
    // Lying on its side in a car holder.
    {
        const { context, page } = await phone({ landscape: true });
        await page.evaluate((c) => window.__app.openPreview({ title: 'Кофейня «Утро»', subtitle: 'Набережная улица, 12', lon: c[0], lat: c[1], id: 'x' }), grid(9, -7));
        await page.waitForSelector('#sheet .option');
        await page.waitForTimeout(1000);
        await shot(page, '12_landscape_routes');
        await page.click('[data-act="go"]');
        await page.waitForTimeout(2500);
        await shot(page, '13_landscape_driving');
        await context.close();
    }
} catch (e) {
    problems.push(`run: ${e.stack || e}`);
} finally {
    await browser.close();
    server.close();
}

if (problems.length) {
    console.log(`PROBLEMS:\n${problems.join('\n')}`);
    process.exitCode = 1;
} else {
    console.log('no problems');
}
