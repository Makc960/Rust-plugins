import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

import { Friends } from '../../server/src/friends.mjs';
import { makeServer } from '../../server/src/server.mjs';
import { Api, codeIn, inviteLink } from '../src/js/api.js';
import { bounds, circle, meters } from '../src/js/geo.js';
import { ago, distance, escape, friendsCount, initials, plural, speed } from '../src/js/words.js';
import { hybridStyle, loadStyles, SATELLITE, schemeStyle } from '../tools/styles.mjs';

const liberty = JSON.parse(readFileSync(new URL('../vendor/openfreemap/liberty.json', import.meta.url), 'utf8'));

test('Russian words for numbers, times and distances', () => {
    assert.deepEqual([1, 2, 5, 11, 21, 104].map((n) => plural(n, 'друг', 'друга', 'друзей')), ['друг', 'друга', 'друзей', 'друзей', 'друг', 'друга']);
    assert.equal(friendsCount(3), '3 друга');
    const now = new Date(2026, 9, 28, 15, 30).getTime();
    assert.equal(ago(now - 20000, now), 'только что');
    assert.equal(ago(now - 7 * 60000, now), '7 мин назад');
    assert.equal(ago(now - 2 * 3600000, now), '2 часа назад');
    assert.equal(ago(new Date(2026, 9, 28, 8, 5).getTime(), now), 'сегодня в 08:05');
    assert.equal(ago(new Date(2026, 9, 27, 18, 20).getTime(), now), 'вчера в 18:20');
    assert.equal(ago(new Date(2026, 9, 20, 9, 0).getTime(), now), '20.10 в 09:00');
    assert.deepEqual([3, 347, 1234, 15678].map(distance), ['10 м', '350 м', '1,2 км', '16 км']);
    assert.equal(speed(1), '');
    assert.equal(speed(15), '54 км/ч');
    assert.deepEqual(['Анна Петрова', 'боря', '  ', 'Ёжик в тумане'].map(initials), ['АП', 'Б', '?', 'ЁВ']);
    assert.equal(escape('<b a="1">&'), '&lt;b a=&quot;1&quot;&gt;&amp;');
});

test('distances, circles and bounds on the globe', () => {
    const moscow = [37.6176, 55.7558];
    const spb = [30.3159, 59.9391];
    assert.ok(Math.abs(meters(moscow, spb) - 634000) < 5000);
    const ring = circle(moscow, 100);
    assert.deepEqual(ring[0], ring[ring.length - 1], 'the ring closes');
    for (const p of ring) assert.ok(Math.abs(meters(moscow, p) - 100) < 1);
    assert.deepEqual(bounds([moscow, spb]), [[30.3159, 55.7558], [37.6176, 59.9391]]);
    assert.equal(bounds([]), null);
});

test('friend codes are found in links and pasted texts', () => {
    assert.equal(codeIn('ryadom://add/abcd-efgh'), 'ABCD-EFGH');
    assert.equal(codeIn('https://ice-rust.store/ryadom/add/K7QM2XWP'), 'K7QM-2XWP');
    assert.equal(codeIn('Добавь меня в «Рядом»: https://ice-rust.store/ryadom/add/ABCD-EFGH\nМой код: ABCD-EFGH'), 'ABCD-EFGH');
    assert.equal(codeIn(' wxyz-2345 '), 'WXYZ-2345');
    assert.equal(codeIn('WHATSAPPSTORE'), null, 'not inside a longer word');
    assert.equal(codeIn('abc'), null);
    assert.equal(inviteLink('https://ice-rust.store/ryadom/', 'ABCD-EFGH'), 'https://ice-rust.store/ryadom/add/ABCD-EFGH');
});

test('the client talks to the real server: two people become friends and see each other', async () => {
    const server = makeServer(new Friends(), { log: { error() {} } });
    await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
    const base = `http://127.0.0.1:${server.address().port}`;
    // The platform's http as the page has it: {method, body, type, headers} -> {status, text}.
    const http = async (url, { method, body, type, headers }) => {
        const r = await fetch(url, { method, body: body || undefined, headers: { ...headers, ...(type ? { 'content-type': type } : {}) } });
        return { status: r.status, text: await r.text() };
    };
    try {
        const anna = new Api({ server: base, http });
        const boris = new Api({ server: `${base}/`, http });
        const a = await anna.register('Анна', '#E5484D');
        const b = await boris.register('Борис');
        assert.equal(anna.token, a.token);
        assert.deepEqual(await anna.add(b.me.code.toLowerCase()), { result: 'requested', name: 'Борис' });
        await boris.locate({ lat: 55.75, lon: 37.62, accuracy: 10, battery: 64, charging: true });
        const pending = await boris.view();
        assert.equal(pending.incoming[0].name, 'Анна');
        await boris.accept(pending.incoming[0].id);
        const seen = await anna.view();
        assert.equal(seen.friends[0].name, 'Борис');
        assert.equal(seen.friends[0].place.battery, 64);
        assert.equal((await boris.update({ sharing: false })).sharing, false);
        assert.equal((await anna.view()).friends[0].place, null);
        await assert.rejects(anna.add('ZZZZ-ZZZZ'), (e) => e.status === 404);
        await assert.rejects(new Api({ server: base, http, token: 'bad' }).view(), (e) => e.status === 401);
        await assert.rejects(new Api({ server: base, http: async () => { throw new Error('down'); } }).view(), (e) => e.status === 0);
        await anna.unfriend(seen.friends[0].id);
        assert.deepEqual((await boris.view()).friends, []);
        await boris.remove();
        await assert.rejects(boris.view(), (e) => e.status === 401);
    } finally {
        server.close();
    }
});

test('the hybrid is the satellite pictures with roads and Russian names over them', () => {
    const hybrid = hybridStyle(liberty);
    assert.deepEqual(hybrid.sources.satellite, SATELLITE);
    assert.ok(hybrid.sources.openmaptiles.url.startsWith('https://tiles.openfreemap.org/'));
    assert.equal(hybrid.layers[1].id, 'satellite');
    const ids = hybrid.layers.map((l) => l.id);
    for (const id of ['road_motorway', 'road_minor', 'label_city', 'highway-name-major', 'boundary_2']) assert.ok(ids.includes(id), id);
    for (const id of ['building', 'landcover_wood', 'water', 'road_motorway_casing', 'poi_r20']) assert.ok(!ids.includes(id), `${id} hides the picture`);
    const city = hybrid.layers.find((l) => l.id === 'label_city');
    assert.deepEqual(city.layout['text-field'], ['coalesce', ['get', 'name:ru'], ['get', 'name']]);
    assert.equal(city.paint['text-color'], '#ffffff');
    for (const layer of hybrid.layers) assert.ok(!layer.source || hybrid.sources[layer.source], `${layer.id} has its source`);
    assert.ok(!JSON.stringify(hybrid).includes('__TILEJSON_DOMAIN__'));

    const satellite = hybridStyle(liberty, { labels: false });
    assert.deepEqual(satellite.layers.map((l) => l.id), ['background', 'satellite']);
    assert.deepEqual(Object.keys(satellite.sources), ['satellite']);

    const scheme = schemeStyle(liberty);
    assert.equal(scheme.layers.length, liberty.layers.length);
    assert.deepEqual(Object.keys(loadStyles(new URL('../vendor/openfreemap', import.meta.url).pathname)), ['hybrid', 'satellite', 'scheme']);
});
