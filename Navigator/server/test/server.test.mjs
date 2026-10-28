import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

import { cleanFeedback, Feedback } from '../src/feedback.mjs';
import { KINDS, Reports } from '../src/reports.mjs';
import { configFrom, limiter, makeServer } from '../src/server.mjs';
import { Trips } from '../src/trips.mjs';

const HOUR = 3600 * 1000;
const ME = 'device-aaaaaaaaaaaa';
const YOU = 'device-bbbbbbbbbbbb';
const THEM = 'device-cccccccccccc';
// Krasnaya street in Krasnodar, and a spot 100 m north of it.
const SPOT = { lat: 45.0402, lon: 38.9746 };
const NEAR = { lat: 45.0411, lon: 38.9746 };

function clock(start = Date.UTC(2026, 9, 28, 12)) {
    const c = { now: start };
    c.fn = () => c.now;
    return c;
}

test('a report stays as long as its kind does, unless drivers say it is still there', () => {
    const c = clock();
    const reports = new Reports({ now: c.fn });
    const { report, confirmed } = reports.add({ kind: 'police', ...SPOT, heading: 90, device: ME });
    assert.equal(confirmed, false);
    assert.equal(report.kind, 'police');
    assert.equal(report.votes, 1);
    assert.ok(!('author' in report) && !JSON.stringify(report).includes(ME), 'who sent it is never shown');
    assert.equal(reports.inBox([38.9, 45.0, 39.1, 45.1]).length, 1);
    assert.equal(reports.inBox([39.5, 45.0, 39.6, 45.1]).length, 0, 'not outside the box');

    c.now += 1.5 * HOUR;
    reports.vote(report.id, { device: YOU, still: true });
    c.now += 1.5 * HOUR;
    assert.equal(reports.inBox([38.9, 45.0, 39.1, 45.1]).length, 1, 'kept by the second driver');
    c.now += KINDS.police.ttl;
    assert.equal(reports.inBox([38.9, 45.0, 39.1, 45.1]).length, 0, 'gone in the end');
    assert.equal(reports.prune(), 1);
});

test('the same thing reported again close by is one report, confirmed; the other way is another', () => {
    const reports = new Reports({ now: clock().fn });
    const first = reports.add({ kind: 'crash', ...SPOT, heading: 0, device: ME }).report;
    const again = reports.add({ kind: 'crash', ...NEAR, heading: 10, device: YOU });
    assert.equal(again.confirmed, true);
    assert.equal(again.report.id, first.id);
    assert.equal(again.report.votes, 2);
    const opposite = reports.add({ kind: 'crash', ...NEAR, heading: 180, device: THEM });
    assert.equal(opposite.confirmed, false, 'the other side of the road');
    assert.equal(reports.add({ kind: 'works', ...SPOT, device: THEM }).confirmed, false, 'another kind');
    assert.throws(() => reports.add({ kind: 'ufo', ...SPOT, device: ME }), /kind/);
    assert.throws(() => reports.add({ kind: 'jam', lat: 95, lon: 0, device: ME }), /coordinates/);
    assert.throws(() => reports.add({ kind: 'jam', ...SPOT, device: 'x' }), /device/);
});

test('two drivers saying it is gone take it off; its author alone can too', () => {
    const reports = new Reports({ now: clock().fn });
    const a = reports.add({ kind: 'jam', ...SPOT, device: ME }).report;
    assert.equal(reports.vote(a.id, { device: YOU, still: false }).removed, false);
    assert.equal(reports.vote(a.id, { device: YOU, still: false }).removed, false, 'one phone, one voice');
    assert.equal(reports.vote(a.id, { device: THEM, still: false }).removed, true);
    assert.throws(() => reports.vote(a.id, { device: THEM, still: true }), /no such/);
    const b = reports.add({ kind: 'danger', ...SPOT, device: ME }).report;
    assert.equal(reports.vote(b.id, { device: ME, still: false }).removed, true);
});

test('a shared drive: made with a secret key, seen by anyone with the link, gone after it ends', () => {
    const c = clock();
    const trips = new Trips({ now: c.fn });
    const { id, key } = trips.create({ dest: { title: 'Кафе «Самовар»', ...SPOT }, name: 'Макс', pos: NEAR, left: 600, remaining: 7300, line: [[38.97, 45.04], [38.98, 45.05], ['x', 1]] });
    const seen = trips.get(id);
    assert.equal(seen.dest.title, 'Кафе «Самовар»');
    assert.equal(seen.eta, c.now + 600 * 1000);
    assert.equal(seen.line.length, 2, 'bad points are left out');
    assert.ok(!JSON.stringify(trips.store.data).includes(key), 'only a hash of the key is kept');
    assert.ok(!('key' in seen));

    c.now += 60 * 1000;
    assert.throws(() => trips.update(id, 'wrong', { left: 1 }), /not yours/);
    const later = trips.update(id, key, { pos: SPOT, left: 30, remaining: 200 });
    assert.equal(later.remaining, 200);
    assert.equal(later.eta, c.now + 30 * 1000);
    const done = trips.update(id, key, { arrived: true });
    assert.equal(done.arrived, true);
    assert.throws(() => trips.update(id, key, { left: 5 }), /ended/);
    c.now += 31 * 60 * 1000;
    assert.equal(trips.get(id), null, 'forgotten half an hour after');
    assert.throws(() => trips.create({ dest: { title: 'x' } }), /destination/);
});

test('search notes keep only what helps, the place rounded', () => {
    const note = cleanFeedback({ query: '  Красная 120 ', wanted: 'дом у парка', town: 'Краснодар', point: { lat: 45.040213, lon: 38.974611 }, results: ['a', 'b'], token: 'nope' }, Date.UTC(2026, 0, 1));
    assert.deepEqual(note, { at: '2026-01-01T00:00:00.000Z', query: 'Красная 120', wanted: 'дом у парка', town: 'Краснодар', point: { lat: 45.04, lon: 38.975 }, results: ['a', 'b'] });
    assert.throws(() => cleanFeedback({ query: ' ' }), /query/);
    const dir = mkdtempSync(join(tmpdir(), 'nav-'));
    try {
        const feedback = new Feedback({ file: join(dir, 'feedback.jsonl') });
        feedback.add({ query: 'один' });
        feedback.add({ query: 'два' });
        assert.deepEqual(feedback.recent(5).map((n) => n.query), ['один', 'два']);
        assert.equal(readFileSync(join(dir, 'feedback.jsonl'), 'utf8').trim().split('\n').length, 2);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test('the servers the app asks come from the server\'s settings, only those set', () => {
    assert.deepEqual(configFrom({}), { servers: {}, message: '', latest: '' });
    assert.deepEqual(configFrom({ PHOTON_URL: 'https://ice-rust.store/photon/', OVERPASS_URL: 'https://a/api, https://b/api', MESSAGE: 'Привет' }), {
        servers: { photon: 'https://ice-rust.store/photon', overpass: ['https://a/api', 'https://b/api'] }, message: 'Привет', latest: '',
    });
    const allow = limiter(2, 1000);
    assert.equal(allow('a', 0), true);
    assert.equal(allow('a', 10), true);
    assert.equal(allow('a', 20), false);
    assert.equal(allow('a', 1500), true);
});

test('over HTTP: reports, votes, a shared drive with its page, feedback, config', async () => {
    const reports = new Reports();
    const trips = new Trips();
    const feedback = new Feedback();
    const server = makeServer({ reports, trips, feedback, config: configFrom({ VALHALLA_URL: 'https://v' }), publicUrl: 'https://ice-rust.store/navigator', log: { error() {} } });
    await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
    const base = `http://127.0.0.1:${server.address().port}`;
    const call = async (method, path, body) => {
        const r = await fetch(base + path, { method, body: body ? JSON.stringify(body) : undefined, headers: body ? { 'content-type': 'application/json' } : {} });
        const type = r.headers.get('content-type') || '';
        return { status: r.status, body: type.includes('json') ? await r.json() : await r.text() };
    };
    try {
        assert.equal((await call('GET', '/health')).body, 'ok');
        assert.deepEqual((await call('GET', '/api/config')).body.servers, { valhalla: 'https://v' });

        const made = await call('POST', '/api/reports', { kind: 'camera', ...SPOT, heading: 45, device: ME });
        assert.equal(made.status, 200);
        const box = await call('GET', '/api/reports?bbox=38.9,45.0,39.1,45.1');
        assert.deepEqual(box.body.reports.map((r) => r.kind), ['camera']);
        assert.equal((await call('GET', '/api/reports?bbox=0,0,10,10')).status, 400, 'no country-sized boxes');
        assert.equal((await call('POST', `/api/reports/${made.body.report.id}/vote`, { device: ME, still: false })).body.removed, true);
        assert.equal((await call('POST', '/api/reports', { kind: 'nope', ...SPOT, device: ME })).status, 400);

        const trip = await call('POST', '/api/trips', { dest: { title: 'Дом <b>', ...SPOT }, pos: NEAR, left: 300 });
        assert.match(trip.body.url, /^https:\/\/ice-rust\.store\/navigator\/t\/[A-Za-z0-9_-]+$/);
        assert.equal((await call('GET', `/api/trips/${trip.body.id}`)).body.left, 300);
        const pageView = await call('GET', `/t/${trip.body.id}`);
        assert.equal(pageView.status, 200);
        assert.ok(pageView.body.includes('Дом &lt;b&gt;') && !pageView.body.includes('Дом <b>'), 'the title is escaped');
        assert.equal((await call('PUT', `/api/trips/${trip.body.id}`, { key: 'no', left: 1 })).status, 403);
        assert.equal((await call('PUT', `/api/trips/${trip.body.id}`, { key: trip.body.key, left: 120 })).body.left, 120);
        assert.equal((await call('POST', `/api/trips/${trip.body.id}/end`, { key: 'no' })).status, 403);
        assert.equal((await call('POST', `/api/trips/${trip.body.id}/end`, { key: trip.body.key })).body.ended > 0, true);
        assert.equal((await call('GET', '/t/zzzzzzzz')).status, 404);

        assert.equal((await call('POST', '/api/feedback', { query: 'Северная 324', wanted: 'не тот дом' })).status, 200);
        assert.equal(feedback.recent()[0].wanted, 'не тот дом');
        assert.equal((await call('GET', '/privacy')).status, 200);
        assert.equal((await call('GET', '/api/nothing')).status, 404);
    } finally {
        server.close();
    }
});
