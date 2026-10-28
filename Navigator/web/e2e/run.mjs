// Runs the app in Chromium against the made-up town (city.mjs) and takes screenshots of each screen:
//   node e2e/run.mjs <built app dir> <screenshots dir> [chromium path]
// Every server the app talks to is answered here, so it needs no internet.

import { createReadStream, existsSync, mkdirSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, resolve } from 'node:path';

import { chromium } from 'playwright-core';

import { CENTER, cityGeojson, cityStyle, friendsAnswer, grid, nearbyAnswer, overpassAnswer, reportsAnswer, reverseAnswer, routeAnswer, searchAnswer, traceAnswer } from './city.mjs';

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
let reported = null;
let feedbackSent = null;

// What MainActivity and Bridge.kt do, played in the page: network, position and back go through
// window.ShturmanNative and come back through window.__shturman, as on the phone.
function nativeBridge(start) {
    const calls = (window.__native = []);
    const answer = (fn) => setTimeout(fn, 20);
    window.ShturmanNative = {
        startLocation() {
            calls.push('startLocation');
            answer(() => window.__shturman.location({ lon: start[0], lat: start[1], accuracy: 8, speed: null, heading: null, time: Date.now() }));
        },
        stopLocation() { calls.push('stopLocation'); },
        requestLocation() { calls.push('requestLocation'); },
        openLocationSettings() { calls.push('openLocationSettings'); },
        speak(text) { (window.__said ||= []).push(text); },
        stopSpeaking() {},
        http(id, method, url, body, timeout, headers) {
            calls.push(`http ${url.split('?')[0]}`);
            fetch(url, { method, body: body || undefined, headers: JSON.parse(headers || '{}') }).then(async (r) => window.__shturman.http(id, r.status, await r.text()), () => window.__shturman.http(id, -1, 'IOException'));
        },
        setLocationMode(mode) { calls.push(`setLocationMode ${mode}`); },
        hud(on) { calls.push(`hud ${on}`); },
        network() { return JSON.stringify({ online: true, metered: false }); },
        prefetch(tag, urls) { calls.push(`prefetch ${tag} ${JSON.parse(urls).length}`); },
        cancelPrefetch() {},
        storage() { return JSON.stringify({ cache: 42, offline: 0 }); },
        clearMaps() {},
        canListen() { return true; },
        listen() { setTimeout(() => window.__shturman.heard('кафе'), 50); },
        openRyadom() { calls.push('openRyadom'); },
        keepScreenOn(on) { calls.push(`keepScreenOn ${on}`); },
        navStatus(title, text) { calls.push('navStatus'); window.__status = `${title} | ${text}`; },
        setTheme(t) { calls.push(`setTheme ${t}`); },
        share() {}, copy() {}, openUrl() {}, vibrate() {}, exit() {},
        info() { return JSON.stringify({ version: '1.0', store: 'github', supportUrl: 'https://example.org' }); },
    };
}

async function phone({ landscape = false, theme = 'day', native = false, seed = {} } = {}) {
    const size = landscape ? { width: 860, height: 400 } : { width: 400, height: 860 };
    const context = await browser.newContext({
        viewport: size, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'ru-RU',
        geolocation: { latitude: CENTER[1], longitude: CENTER[0], accuracy: 12 }, permissions: ['geolocation'],
    });
    await context.addInitScript(([t, extra]) => {
        localStorage.setItem('shturman.v1', JSON.stringify({ settings: { theme: t, demoSpeed: 4 }, view: { center: [37.6176, 55.7558], zoom: 15 }, ...extra }));
        window.speechSynthesis && (window.speechSynthesis.speak = (u) => { (window.__said ||= []).push(u.text); });
    }, [theme, seed]);
    if (native) await context.addInitScript(nativeBridge, CENTER);
    const page = await context.newPage();
    page.on('console', (m) => { if (m.type() === 'error') problems.push(`console: ${m.text()}`); });
    page.on('pageerror', (e) => problems.push(`page: ${e.message}`));
    await page.route('https://photon.komoot.io/**', (route) => {
        const url = new URL(route.request().url());
        const point = [+url.searchParams.get('lon'), +url.searchParams.get('lat')];
        const tag = url.searchParams.get('osm_tag');
        const body = url.pathname !== '/reverse' ? searchAnswer() : tag ? nearbyAnswer(point, tag) : reverseAnswer(point);
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body), headers: { 'access-control-allow-origin': '*' } });
    });
    await page.route('https://valhalla1.openstreetmap.de/**', (route) => {
        const trace = route.request().url().includes('/trace_attributes');
        const body = trace ? traceAnswer(JSON.parse(route.request().postData() || '{}')) : routeAnswer();
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body), headers: { 'access-control-allow-origin': '*' } });
    });
    await page.route(/overpass/, (route) =>
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(overpassAnswer()), headers: { 'access-control-allow-origin': '*' } }));
    await page.route('https://nominatim.openstreetmap.org/**', (route) =>
        route.fulfill({ status: 200, contentType: 'application/json', body: '[]', headers: { 'access-control-allow-origin': '*' } }));
    // The app's own server, and «Рядом»'s.
    await page.route('https://ice-rust.store/**', (route) => {
        const req = route.request();
        const url = new URL(req.url());
        const path = url.pathname;
        let body = { ok: true };
        if (path === '/navigator/api/config') body = { servers: {}, message: '', latest: '' };
        else if (path === '/navigator/api/reports' && req.method() === 'GET') body = reportsAnswer();
        else if (path === '/navigator/api/reports') {
            const r = JSON.parse(req.postData() || '{}');
            body = { report: { id: 'new', kind: r.kind, lat: r.lat, lon: r.lon, heading: r.heading, created: Date.now(), confirmed: Date.now(), votes: 1 }, confirmed: false };
            (reported ||= []).push(r.kind);
        } else if (path.endsWith('/vote')) body = { removed: false };
        else if (path === '/navigator/api/trips') body = { id: 'AbCd1234', key: 'secret', url: 'https://ice-rust.store/navigator/t/AbCd1234' };
        else if (path === '/navigator/api/feedback') (feedbackSent ||= []).push(JSON.parse(req.postData() || '{}'));
        else if (path === '/ryadom/api/friends') body = req.headers().authorization === 'Bearer view-token-0123456789abcdef' ? friendsAnswer() : { error: 'who' };
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body), headers: { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*' } });
    });
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
        const trips = [
            { dest: { title: 'Кофейня «Утро»', subtitle: 'Набережная улица, 12', kind: '', lon: grid(9, -7)[0], lat: grid(9, -7)[1], id: 'x' }, start: Date.now() - 3 * 3600000, end: Date.now() - 3 * 3600000 + 14 * 60000, length: 4300, mode: 'car', arrived: true },
            { dest: { title: 'Работа', subtitle: 'Тверская улица, 7', kind: '', lon: grid(6, 0)[0], lat: grid(6, 0)[1], id: 'w' }, start: Date.now() - 26 * 3600000, end: Date.now() - 26 * 3600000 + 22 * 60000, length: 9100, mode: 'car', arrived: true },
        ];
        const { context, page } = await phone({ seed: { trips } });
        await shot(page, '01_map');
        // A drive offered on the map, as on a weekday morning at home.
        await page.evaluate((c) => {
            const a = window.__app;
            a.S.suggest = { to: 'work', place: { title: 'Работа', lon: c[0], lat: c[1], id: 'w' }, route: { duration: 1080, length: 7200 } };
            a.renderSuggestion();
        }, grid(6, 0));
        await shot(page, '01c_suggest');
        await page.evaluate(() => {
            window.__app.S.suggest = null;
            window.__app.renderSuggestion();
        });
        // The layers button: Схема → Гибрид, remembered; and back.
        await page.click('#layers-btn');
        const type = await page.evaluate(() => JSON.parse(localStorage.getItem('shturman.v1')).settings.mapType);
        if (type !== 'hybrid') problems.push(`layers: map type is ${type}`);
        await page.click('#layers-btn');
        await page.click('#layers-btn');
        await page.click('#search-open');
        await page.waitForSelector('#search-results .cat');
        await page.click('#search-results [data-act="cat"][data-id="cafe"]');
        await page.waitForSelector('#search-results .row');
        await shot(page, '01b_nearby');
        await page.click('#search-clear');
        await page.fill('#search-input', 'кафе');
        await page.waitForSelector('#search-results .row');
        await shot(page, '02_search');
        // «Не то нашлось»: a word on what was wanted goes to the server.
        await page.click('#search-results [data-act="feedback"]');
        await page.waitForSelector('#ask-text');
        await page.fill('#ask-text', 'Искал кофейню у парка');
        await shot(page, '02b_feedback');
        await page.click('#dialog [data-act="send"]');
        await page.waitForTimeout(400);
        if (!feedbackSent || feedbackSent[0].wanted !== 'Искал кофейню у парка' || feedbackSent[0].query !== 'кафе') problems.push(`feedback: ${JSON.stringify(feedbackSent)}`);
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
        // Road warnings are on the map and along the route: a bump, the camera, a crossing, a school, reports.
        const kinds = await page.evaluate(() => window.__app.S.nav.hazards.map((h) => h.kind));
        for (const want of ['bump', 'camera', 'rail', 'school', 'crash', 'police']) if (!kinds.includes(want)) problems.push(`hazards: no ${want} in ${kinds}`);
        // What is on the road, reported from the car.
        await page.click('#report-btn');
        await page.waitForSelector('#sheet .report-kind');
        await shot(page, '05d_report');
        await page.click('#sheet [data-kind="jam"]');
        await page.waitForTimeout(500);
        if (!reported || reported[0] !== 'jam') problems.push(`report: sent ${reported}`);
        // Places along the way: petrol stations ahead, one taken as a stop.
        await page.click('[data-act="along"]');
        await page.click('[data-act="alongCat"][data-id="fuel"]');
        await page.waitForSelector('[data-act="addStop"]');
        await shot(page, '05b_along');
        await page.click('[data-act="addStop"]');
        await page.waitForFunction(() => window.__app.S.nav && window.__app.S.nav.stops.length === 1 && !window.__app.S.nav.rerouting);
        await page.waitForTimeout(1500);
        await shot(page, '05c_stop_added');
        // On to where the lanes show, before the turn onto проспект Мира.
        await page.waitForFunction(() => {
            const n = window.__app.S.nav;
            return n && n.progress && n.progress.next === 3 && n.progress.toNext < 300;
        }, null, { timeout: 60000 });
        await shot(page, '06_lanes');
        await page.click('[data-act="overview"]');
        await page.waitForTimeout(1000);
        await shot(page, '07_overview');
        await page.click('#navbar .eta');
        await page.click('#dialog [data-value="steps"]');
        await page.waitForSelector('#steps .step-row');
        await shot(page, '07b_steps');
        await page.click('#steps [data-act="closeSteps"]');
        // The windscreen view, mirrored.
        await page.click('#navbar .eta');
        await page.click('#dialog [data-value="hud"]');
        await page.waitForSelector('#hud .hud-view');
        await shot(page, '15_hud');
        await page.click('#hud [data-act="exit"]');
        // Sharing the drive: a link from the server.
        await page.click('#navbar .eta');
        await page.click('#dialog [data-value="share"]');
        await page.waitForFunction(() => window.__app.S.nav.share && window.__app.S.nav.share.id === 'AbCd1234');
        await page.click('#resume-btn');
        await page.waitForFunction(() => window.__app.S.nav && window.__app.S.nav.arrived, null, { timeout: 90000 });
        await shot(page, '08_arrived');
        const said = await page.evaluate(() => window.__said || []);
        console.log('said:', said.join(' | '));
        await page.click('[data-act="done"]');
        await page.click('#menu-btn');
        await page.waitForSelector('#settings .setting');
        await shot(page, '09_settings');
        await page.evaluate(() => document.querySelector('#settings .page-body').scrollBy(0, 900));
        await shot(page, '09b_settings_more');
        await page.click('#settings [data-act="history"]');
        await page.waitForSelector('#history .row');
        await shot(page, '09c_history');
        await context.close();
    }
    // Friends from «Рядом» on the map, once linked.
    {
        const { context, page } = await phone({ seed: { ryadom: { server: 'https://ice-rust.store/ryadom', token: 'view-token-0123456789abcdef', name: 'Макс' } } });
        await page.waitForSelector('.friend-pin', { timeout: 10000 });
        await page.evaluate(() => {
            window.__app.map.setMode('free');
            window.__app.map.map.jumpTo({ zoom: 14 });
        });
        await shot(page, '16_friends');
        await page.click('.friend-pin');
        await page.waitForSelector('#sheet .avatar');
        await shot(page, '16b_friend');
        // A report on the map, tapped.
        await page.evaluate(() => window.__app.openHazard(window.__app.S.viewReports.length ? { kind: 'crash', id: 'rr1', point: [window.__app.S.viewReports[0].lon, window.__app.S.viewReports[0].lat], report: window.__app.S.viewReports[0] } : null));
        await page.waitForSelector('#sheet [data-act="still"]');
        await shot(page, '17_report_card');
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
    // As inside the Android app: everything through the bridge.
    {
        const { context, page } = await phone({ native: true });
        await page.waitForFunction(() => window.__app.S.locStatus === 'ok');
        await page.evaluate(() => window.__shturman.intent('geo:0,0?q=55.7429,37.6326(Кофейня «Утро»)'));
        await page.waitForSelector('#sheet [data-act="route"]');
        await page.click('[data-act="route"]');
        await page.waitForSelector('#sheet .option');
        await page.click('[data-act="go"]');
        await page.waitForSelector('#banner .dist');
        // Driving at 15 m/s with real fixes, then into a tunnel: no fixes, the car goes on along the route.
        await page.evaluate(async () => {
            const r = window.__app.S.nav.route;
            const at = (d) => {
                let i = 0;
                while (i < r.coords.length - 2 && r.cum[i + 1] < d) i++;
                const f = (d - r.cum[i]) / (r.cum[i + 1] - r.cum[i]);
                return [r.coords[i][0] + (r.coords[i + 1][0] - r.coords[i][0]) * f, r.coords[i][1] + (r.coords[i + 1][1] - r.coords[i][1]) * f];
            };
            for (let k = 0; k < 6; k++) {
                const [lon, lat] = at(50 + k * 15);
                window.__shturman.location({ lon, lat, accuracy: 6, speed: 15, heading: 90, time: Date.now() });
                await new Promise((ok) => setTimeout(ok, 1000));
            }
        });
        const lastSeen = await page.evaluate(() => window.__app.S.nav.progress.along);
        await page.waitForFunction(() => window.__app.S.nav.predicting, null, { timeout: 8000 });
        await page.waitForTimeout(2200);
        const tunnel = await page.evaluate(() => ({ along: window.__app.S.nav.progress.along, banner: document.querySelector('#banner').textContent }));
        await shot(page, '14a_tunnel');
        // Just past a driver's report: is it still there?
        await page.evaluate(() => {
            const h = window.__app.S.nav.hazards.find((x) => x.report);
            if (h) window.__app.showPrompt(h);
        });
        await page.waitForSelector('#prompt [data-act="yes"]');
        await shot(page, '14b_prompt');
        await page.click('#prompt [data-act="yes"]');
        if (!(tunnel.along > lastSeen + 40)) problems.push(`tunnel: stayed at ${tunnel.along} from ${lastSeen}`);
        if (!tunnel.banner.includes('веду по маршруту')) problems.push(`tunnel: banner "${tunnel.banner}"`);
        await page.waitForFunction(() => window.__status);
        const status = await page.evaluate(() => window.__status);
        if (!/^\d[\d,]* к?м · /.test(status)) problems.push(`native: notification says "${status}"`);
        const back = await page.evaluate(() => window.__shturman.back());
        await page.waitForSelector('#dialog .box');
        await shot(page, '14_native_end_dialog');
        await page.click('#dialog [data-value="yes"]');
        const calls = await page.evaluate(() => window.__native);
        const said = await page.evaluate(() => window.__said || []);
        const atBrowse = await page.evaluate(() => window.__shturman.back());
        console.log('native calls:', [...new Set(calls)].join(', '));
        for (const want of ['navStatus', 'startLocation', 'http https://valhalla1.openstreetmap.de/route', 'http https://photon.komoot.io/reverse', 'keepScreenOn true', 'keepScreenOn false', 'setTheme day', 'setLocationMode nav', 'setLocationMode browse']) {
            if (!calls.includes(want)) problems.push(`native: no "${want}"`);
        }
        if (back !== true) problems.push('native: back while driving was not handled');
        if (atBrowse !== false) problems.push('native: back on the map should leave the app');
        if (!said[0] || !said[0].startsWith('Маршрут построен')) problems.push(`native: said ${said}`);
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
