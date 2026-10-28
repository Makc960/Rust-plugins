// Runs the app in Chromium against the real server (server/), two people side by side, and takes
// screenshots of each screen:
//   node e2e/run.mjs <built app dir> <screenshots dir> [chromium path]
// Anna uses the app in a browser; Boris has the Android bridge played in his page, as on a phone.
// The map's pictures are stood in for by a plain test style, so it needs no internet.

import { createReadStream, existsSync, mkdirSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, resolve } from 'node:path';

import { chromium } from 'playwright-core';

import { Friends } from '../../server/src/friends.mjs';
import { makeHandler } from '../../server/src/server.mjs';

const [appDir, shotsDir, browserPath = '/opt/pw-browsers/chromium-1194/chrome-linux/chrome'] = process.argv.slice(2).map((p, i) => (i < 2 ? resolve(p) : p));
mkdirSync(shotsDir, { recursive: true });

const CENTER = [37.6176, 55.7558];
const BORIS = [37.6305, 55.7623];
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.md': 'text/plain' };

/** Fields, a park, a river and streets around the center, in a satellite's colors. */
function testStyle() {
    const box = (x, y, w, h) => [[[x, y], [x + w, y], [x + w, y + h], [x, y + h], [x, y]]];
    const fields = [
        { color: '#3d5a32', geometry: { type: 'Polygon', coordinates: box(37.58, 55.735, 0.03, 0.02) } },
        { color: '#4f5f3f', geometry: { type: 'Polygon', coordinates: box(37.625, 55.758, 0.03, 0.015) } },
        { color: '#59544a', geometry: { type: 'Polygon', coordinates: box(37.6, 55.75, 0.022, 0.012) } },
        { color: '#22414f', geometry: { type: 'Polygon', coordinates: box(37.56, 55.744, 0.12, 0.004) } },
    ].map((f) => ({ type: 'Feature', properties: { color: f.color }, geometry: f.geometry }));
    const roads = [[[37.55, 55.7558], [37.7, 55.7558]], [[37.6176, 55.72], [37.6176, 55.79]], [[37.58, 55.73], [37.66, 55.78]]]
        .map((c) => ({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: c } }));
    return {
        version: 8,
        sources: { fields: { type: 'geojson', data: { type: 'FeatureCollection', features: fields } }, roads: { type: 'geojson', data: { type: 'FeatureCollection', features: roads } } },
        layers: [
            { id: 'background', type: 'background', paint: { 'background-color': '#33402c' } },
            { id: 'fields', type: 'fill', source: 'fields', paint: { 'fill-color': ['get', 'color'] } },
            { id: 'roads', type: 'line', source: 'roads', paint: { 'line-color': 'rgba(255, 220, 120, 0.8)', 'line-width': 4 } },
        ],
    };
}

const friends = new Friends();
const api = makeHandler(friends, { log: { error: (e) => console.error(e) } });
const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/add/') || url.pathname === '/privacy') return api(req, res);
    if (url.pathname === '/test/style.json') {
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end(JSON.stringify(testStyle()));
    }
    const file = join(appDir, decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname));
    if (!file.startsWith(appDir) || !existsSync(file) || statSync(file).isDirectory()) {
        res.writeHead(404);
        return res.end();
    }
    res.writeHead(200, { 'content-type': TYPES[extname(file)] || 'application/octet-stream' });
    createReadStream(file).pipe(res);
});
await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
const base = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch({
    executablePath: browserPath,
    args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});
const problems = [];

// What MainActivity and Bridge.kt do, played in the page: calls go to window.RyadomNative and
// answers come back through window.__ryadom, as on the phone.
function nativeBridge({ start, server }) {
    const calls = (window.__native = []);
    let stored = sessionStorage.getItem('native.account') || '';
    const later = (fn) => setTimeout(fn, 20);
    window.RyadomNative = {
        startLocation() {
            calls.push('startLocation');
            later(() => window.__ryadom.location({ lon: start[0], lat: start[1], accuracy: 140, speed: null, heading: null, time: Date.now() }));
        },
        stopLocation() { calls.push('stopLocation'); },
        requestLocation() { calls.push('requestLocation'); },
        openLocationSettings() { calls.push('openLocationSettings'); },
        http(id, method, url, body, timeout, headers) {
            const h = JSON.parse(headers);
            calls.push(`http ${method} ${new URL(url).pathname}${h.Authorization ? ' auth' : ''}`);
            fetch(url, { method, body: body || undefined, headers: h })
                .then(async (r) => window.__ryadom.http(id, r.status, await r.text()), () => window.__ryadom.http(id, -1, 'IOException'));
        },
        account() { return stored; },
        saveAccount(raw) {
            calls.push(raw ? 'saveAccount' : 'forgetAccount');
            stored = raw;
            sessionStorage.setItem('native.account', raw);
        },
        startSharing(srv, token) { calls.push(`startSharing ${srv} ${token ? 'token' : 'none'}`); },
        stopSharing() { calls.push('stopSharing'); },
        background() { return JSON.stringify({ always: false, notifications: true, unrestricted: false }); },
        requestBackground() { calls.push('requestBackground'); },
        requestNotifications() { calls.push('requestNotifications'); },
        openBatterySettings() { calls.push('openBatterySettings'); },
        battery() { return JSON.stringify({ level: 64, charging: true }); },
        share(text) { window.__shared = text; },
        copy(text) { window.__copied = text; },
        openUrl(url) { calls.push(`openUrl ${url}`); },
        vibrate() {},
        exit() {},
        info() { return JSON.stringify({ version: '1.0', store: 'github', server }); },
    };
}

async function phone(who, { start = CENTER, native = false, link = '' } = {}) {
    const context = await browser.newContext({
        viewport: { width: 400, height: 860 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'ru-RU',
        geolocation: { latitude: start[1], longitude: start[0], accuracy: 12 }, permissions: ['geolocation'],
    });
    if (native) await context.addInitScript(nativeBridge, { start, server: base });
    const page = await context.newPage();
    page.on('console', (m) => {
        if (m.type() === 'error' && !/Failed to load resource|photon/.test(m.text())) problems.push(`${who} console: ${m.text()}`);
    });
    page.on('pageerror', (e) => problems.push(`${who} page: ${e.message}`));
    // Addresses come from Photon: a fixed answer here.
    await page.route('https://photon.komoot.io/**', (route) => route.fulfill({
        status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' },
        body: JSON.stringify({ features: [{ properties: { street: 'Никольская улица', housenumber: '10', city: 'Москва' } }] }),
    }));
    const query = new URLSearchParams({ style: `${base}/test/style.json`, ...(native ? {} : { server: base }), ...(link ? { link } : {}) });
    await page.goto(`${base}/index.html?${query}`);
    await page.waitForFunction(() => window.__app && window.__app.map.map.isStyleLoaded());
    return { context, page };
}

async function shot(page, name) {
    await page.waitForTimeout(500);
    await page.screenshot({ path: join(shotsDir, `${name}.png`) });
    console.log(`shot ${name}`);
}

function check(ok, what) {
    if (!ok) problems.push(`failed: ${what}`);
}

async function register(page, name) {
    await page.fill('#nameInput', name);
    await page.click('[data-action="register"]');
    await page.waitForSelector('[data-action="grantLocation"]');
}

try {
    const anna = await phone('Анна');
    await shot(anna.page, '01_welcome');
    await anna.page.click('.swatch:nth-child(4)');
    await register(anna.page, 'Анна Соколова');
    await shot(anna.page, '02_permission');
    await anna.page.click('[data-action="grantLocation"]');
    await anna.page.waitForFunction(() => window.__app.state.fix && window.__app.state.view);
    await shot(anna.page, '03_empty');
    const annaCode = await anna.page.evaluate(() => window.__app.state.me.code);
    check(/^[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(annaCode), 'Анна has a friend code');

    await anna.page.click('#addBtn');
    await anna.page.waitForSelector('.code');
    check((await anna.page.textContent('.code')) === annaCode, 'the add screen shows her code');
    await shot(anna.page, '04_add');
    await anna.page.click('[data-action="closeScreen"]');

    // Boris, on an Android phone, types Anna's code.
    const boris = await phone('Борис', { start: BORIS, native: true });
    await register(boris.page, 'Борис');
    await boris.page.click('[data-action="grantLocation"]');
    await boris.page.waitForFunction(() => window.__app.state.view);
    await boris.page.click('#addBtn');
    await boris.page.fill('#codeInput', annaCode.toLowerCase());
    await boris.page.click('.code-form button');
    await boris.page.waitForSelector('#toast:not([hidden])');
    check((await boris.page.textContent('#toast')) === 'Заявка отправлена: Анна Соколова', 'Boris hears the request went out');
    const calls = await boris.page.evaluate(() => window.__native);
    check(calls.includes('saveAccount'), 'the account goes to the phone');
    check(calls.some((c) => c.startsWith(`startSharing ${base} token`)), 'sharing in the background starts with the token');
    check(calls.some((c) => c === 'http POST /api/location auth'), 'positions go through the bridge with the token');
    check(calls.includes('requestLocation'), 'the location permission is asked for');

    // Anna sees the request and accepts.
    await anna.page.evaluate(() => window.__app.refresh());
    await anna.page.waitForSelector('[data-action="accept"]');
    check(await anna.page.isVisible('#addBtn.badge'), 'a dot on the add button for the request');
    await anna.page.click('.sheet-head h2');
    await shot(anna.page, '05_request');
    await anna.page.click('[data-action="accept"]');
    await anna.page.waitForSelector('.row.friend');
    const status = await anna.page.textContent('.row.friend .who span');
    check(/км|м/.test(status) && /только что/.test(status), `Boris's distance and time: ${status}`);
    check((await anna.page.textContent('.row.friend .battery')).includes('64%'), 'his battery from the phone');
    await anna.page.click('.sheet-head h2');
    await anna.page.click('[data-action="fit"]');
    await anna.page.waitForTimeout(1200);
    await shot(anna.page, '06_map_friends');
    check(await anna.page.isVisible('.pin'), 'Boris on the map');

    // His card, with the address and a route.
    await anna.page.click('.pin');
    await anna.page.waitForSelector('.card .address');
    check((await anna.page.textContent('.card .address')).includes('Никольская улица, 10'), 'the address under his name');
    await shot(anna.page, '07_card');
    await anna.page.click('[data-action="closeCard"]');

    // Boris sees Anna too.
    await boris.page.evaluate(() => window.__app.refresh());
    await boris.page.waitForSelector('.row.friend');
    check((await boris.page.textContent('.row.friend b')) === 'Анна Соколова', 'Anna in Boris\'s list');

    // Boris hides; Anna sees that he did, and no marker.
    await boris.page.click('#sharingChip');
    await boris.page.click('[data-answer="yes"]');
    await boris.page.waitForSelector('#sharingChip.off');
    check((await boris.page.evaluate(() => window.__native)).includes('stopSharing'), 'hiding stops sharing in the background');
    await shot(boris.page, '08_hidden');
    await anna.page.evaluate(() => window.__app.refresh());
    await anna.page.waitForFunction(() => document.querySelector('.row.friend .who span')?.textContent === 'скрывает местоположение');
    check(!(await anna.page.isVisible('.pin')), 'no marker for someone hiding');

    // Boris's profile: background permissions, styles.
    await boris.page.click('#profileBtn');
    await boris.page.waitForSelector('#sharingSwitch');
    await shot(boris.page, '09_profile');
    await boris.page.click('[data-action="askBackground"]');
    check((await boris.page.evaluate(() => window.__native)).includes('requestBackground'), 'asks for location «always»');
    await boris.page.click('[data-action="closeScreen"]');

    // Layers.
    await anna.page.click('#layersBtn');
    await shot(anna.page, '10_layers');
    await anna.page.click('#layers [data-style="scheme"]');
    check(await anna.page.evaluate(() => JSON.parse(localStorage.getItem('ryadom.v1')).style === 'scheme'), 'the look is remembered');

    // An invite link opened in a browser, and in the app of someone new.
    const invite = await anna.context.newPage();
    await invite.goto(`${base}/add/${annaCode}`);
    check((await invite.textContent('h1')) === 'Анна Соколова зовёт тебя в друзья', 'the invite page names Anna');
    await shot(invite, '11_invite_page');
    const vera = await phone('Вера', { start: [37.6, 55.75], link: `ryadom://add/${annaCode}` });
    await register(vera.page, 'Вера');
    await vera.page.click('[data-action="grantLocation"]');
    await vera.page.waitForSelector('#codeInput');
    check((await vera.page.inputValue('#codeInput')) === annaCode, 'the link\'s code waits in the add screen');

    // Unfriending and deleting an account.
    await anna.page.click('.row.friend');
    await anna.page.click('[data-action="unfriend"]');
    await anna.page.click('[data-answer="yes"]');
    await anna.page.waitForSelector('.empty');
    await boris.page.click('#profileBtn');
    await boris.page.click('[data-action="deleteAccount"]');
    await boris.page.click('[data-answer="yes"]');
    await boris.page.waitForSelector('#nameInput');
    check((await boris.page.evaluate(() => window.__native)).includes('forgetAccount'), 'deleting forgets the account on the phone');
    check(Object.keys(friends.users).length === 2, 'the server forgot Boris');
} catch (e) {
    problems.push(`run: ${e.stack || e.message}`);
} finally {
    await browser.close();
    server.close();
}

if (problems.length) {
    console.error(problems.join('\n'));
    process.exit(1);
}
console.log('all good');
