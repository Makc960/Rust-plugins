import assert from 'node:assert/strict';
import { test } from 'node:test';

import { distance } from '../src/js/geo.js';
import { areaAround, boxAround, houseIn, nominatimUrl, normalizeQuery, placeFromNominatim, search, searchUrl, SERVERS, thinLine, useServers } from '../src/js/services.js';

const KRASNODAR = [38.9769, 45.0355];
const RED_120 = [38.9746, 45.0402];

/** A place in Photon's words. */
function feature(name, point, props = {}) {
    return { type: 'Feature', geometry: { type: 'Point', coordinates: point }, properties: { name, osm_type: 'N', osm_id: Math.round(point[0] * 1e4 + point[1] * 1e2), ...props } };
}

// A little of Russia: the same street names in several towns, as it is.
const WORLD = [
    feature(undefined, RED_120, { street: 'Красная улица', housenumber: '120', city: 'Краснодар', osm_key: 'building', osm_value: 'yes' }),
    feature(undefined, [38.9739, 45.0418], { street: 'Красная улица', housenumber: '122', city: 'Краснодар', osm_key: 'building', osm_value: 'yes' }),
    feature('Красная улица', [38.9751, 45.0379], { city: 'Краснодар', osm_key: 'highway', osm_value: 'primary' }),
    feature(undefined, [131.885, 43.116], { street: 'Красная улица', housenumber: '120', city: 'Владивосток', osm_key: 'building', osm_value: 'yes' }),
    feature(undefined, [39.711, 47.236], { street: 'Пушкинская улица', housenumber: '5', city: 'Ростов-на-Дону', osm_key: 'building', osm_value: 'yes' }),
    feature('Сочи', [39.7303, 43.5855], { osm_key: 'place', osm_value: 'city', type: 'city', extent: [39.5, 43.8, 40.0, 43.4] }),
    feature('Краснодар', KRASNODAR, { osm_key: 'place', osm_value: 'city', type: 'city', extent: [38.85, 45.15, 39.15, 44.95] }),
    feature('Сочинская улица', [38.99, 45.05], { city: 'Краснодар', osm_key: 'highway', osm_value: 'residential' }),
];

/**
 * Photon played: words of the query must all be in a place's name, street, house or town;
 * bbox and layer filter as Photon does; [log] gets every URL asked.
 */
function fakePhoton(log = []) {
    return async (url) => {
        log.push(url);
        const u = new URL(url);
        if (u.pathname === '/reverse') {
            return { status: 200, text: JSON.stringify({ features: [feature(undefined, KRASNODAR, { street: 'Красная улица', housenumber: '1', city: 'Краснодар' })] }) };
        }
        const words = (u.searchParams.get('q') || '').toLowerCase().split(/\s+/).filter(Boolean);
        const box = u.searchParams.get('bbox')?.split(',').map(Number);
        const layer = u.searchParams.get('layer');
        const near = u.searchParams.has('lat') ? [Number(u.searchParams.get('lon')), Number(u.searchParams.get('lat'))] : null;
        let found = WORLD.filter((f) => {
            const p = f.properties;
            const text = [p.name, p.street, p.housenumber, p.city].filter(Boolean).join(' ').toLowerCase();
            if (!words.every((w) => text.includes(w))) return false;
            const [x, y] = f.geometry.coordinates;
            if (box && !(x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3])) return false;
            return !layer || p.type === layer;
        });
        if (near) found = [...found].sort((a, b) => distance(near, a.geometry.coordinates) - distance(near, b.geometry.coordinates));
        return { status: 200, text: JSON.stringify({ features: found }) };
    };
}

test('addresses are written as the map has them', () => {
    assert.equal(normalizeQuery('ул. Красная, д. 120, кв. 5'), 'улица Красная 120');
    assert.equal(normalizeQuery('г. Краснодар, ул.Красная 120'), 'Краснодар улица Красная 120');
    assert.equal(normalizeQuery('пр-т Мира 12 корп. 1'), 'проспект Мира 12 к 1');
    assert.equal(normalizeQuery('Ставропольская 4 оф. 12'), 'Ставропольская 4');
    assert.equal(normalizeQuery('мкр. Юбилейный'), 'микрорайон Юбилейный');
    assert.equal(normalizeQuery('Сочи'), 'Сочи');
    assert.equal(normalizeQuery('д'), 'д', 'a lone word stays');
    assert.equal(houseIn('Красная 120'), '120');
    assert.equal(houseIn('проспект Мира 12 к 1'), '12к1');
    assert.equal(houseIn('Северная 324а'), '324а');
    assert.equal(houseIn('Кафе Мир'), null);
    const box = boxAround(KRASNODAR, 25000);
    assert.ok(Math.abs(distance([box[0], KRASNODAR[1]], KRASNODAR) - 25000) < 50);
    assert.match(searchUrl('x', KRASNODAR, { bbox: box, layer: 'city' }), /bbox=38\.\d+%2C44\.\d+%2C39\.\d+%2C45\.\d+&layer=city/);
});

test('the town around: its name from the address, its bounds from the map', async () => {
    const area = await areaAround(fakePhoton(), KRASNODAR);
    assert.equal(area.name, 'Краснодар');
    assert.ok(area.bbox[0] < 38.85 && area.bbox[2] > 39.15, 'the town\'s bounds, a little wider');
    const lost = await areaAround(async () => { throw new Error('offline'); }, KRASNODAR);
    assert.equal(lost.name, null);
    assert.ok(Math.abs(lost.bbox[2] - lost.bbox[0]) > 0.5, 'a circle around the person instead');
});

test('an address is found in the person\'s own town, not 6000 km away', async () => {
    const http = fakePhoton();
    const area = await areaAround(http, KRASNODAR);
    const found = await search(http, 'ул. Красная, д. 120', KRASNODAR, area);
    assert.equal(found.scope, 'local');
    assert.equal(found.places[0].title, 'Красная улица, 120');
    assert.equal(found.places[0].subtitle, 'Краснодар');
    assert.ok(!found.places.some((p) => p.subtitle.includes('Владивосток')), 'Vladivostok\'s Krasnaya 120 is not offered');
});

test('nothing in the town: the nearest elsewhere, and the person is told', async () => {
    const http = fakePhoton();
    const area = await areaAround(http, KRASNODAR);
    const found = await search(http, 'Пушкинская 5', KRASNODAR, area);
    assert.equal(found.scope, 'elsewhere');
    assert.equal(found.places[0].subtitle, 'Ростов-на-Дону');
    const everywhere = await search(http, 'Красная 120', KRASNODAR, area, { everywhere: true });
    assert.equal(everywhere.scope, 'everywhere');
    assert.deepEqual(everywhere.places.map((p) => p.subtitle), ['Краснодар', 'Владивосток'], 'everywhere, the nearest first');
});

test('a town\'s name typed alone goes to that town', async () => {
    const http = fakePhoton();
    const area = await areaAround(http, KRASNODAR);
    const found = await search(http, 'Сочи', KRASNODAR, area);
    assert.equal(found.places[0].title, 'Сочи');
    assert.equal(found.places[0].kind, 'Город');
    assert.ok(found.places.some((p) => p.title === 'Сочинская улица'), 'the street named for it is still there, after');
    const coords = await search(http, '45.04, 38.97', KRASNODAR, area);
    assert.equal(coords.scope, 'coords');
});

/** Nominatim played: it knows the house Photon does not. */
function withNominatim(log = []) {
    const photon = fakePhoton(log);
    const house = {
        lat: '45.0601', lon: '38.9912', name: '', category: 'building', type: 'apartments', osm_type: 'way', osm_id: 777,
        address: { road: 'Северная улица', house_number: '324А', city: 'Краснодар', suburb: 'Центральный округ' },
        display_name: '324А, Северная улица, Краснодар',
    };
    return async (url, options) => {
        if (!url.includes('nominatim')) return photon(url, options);
        log.push(url);
        const q = new URL(url).searchParams.get('q').toLowerCase();
        return { status: 200, text: JSON.stringify(q.includes('северная') && q.includes('324') ? [house] : []) };
    };
}

test('a house Photon does not know is asked of Nominatim, only when the search is sent', async () => {
    const log = [];
    const http = withNominatim(log);
    const area = await areaAround(http, KRASNODAR);
    const typing = await search(http, 'Северная 324а', KRASNODAR, area);
    assert.equal(typing.exact, false);
    assert.ok(!log.some((u) => u.includes('nominatim')), 'never while typing');
    const sent = await search(http, 'ул. Северная, д. 324а', KRASNODAR, area, { precise: true }, );
    assert.equal(sent.exact, true);
    assert.equal(sent.places[0].title, 'Северная улица, 324А');
    assert.equal(sent.places[0].subtitle, 'Центральный округ, Краснодар');
    assert.equal(sent.places[0].kind, 'Жилой дом');
    assert.equal(sent.places[0].id, 'W777');
    assert.equal(sent.scope, 'local');
    const asked = log.find((u) => u.includes('nominatim'));
    assert.match(asked, /viewbox=38\.\d+%2C45\.\d+%2C39\.\d+%2C44\.\d+&bounded=1/);
    // A house Photon has: Nominatim is left alone.
    log.length = 0;
    const known = await search(http, 'Красная 120', KRASNODAR, area, { precise: true });
    assert.equal(known.exact, true);
    assert.ok(!log.some((u) => u.includes('nominatim')));
    assert.match(nominatimUrl('Красная 120', null), /^https:\/\/nominatim\.openstreetmap\.org\/search\?q=/);
    assert.equal(placeFromNominatim({ lat: '1', lon: '2', name: 'Кафе', address: { road: 'Мира', house_number: '5' }, category: 'amenity', type: 'cafe', osm_type: 'node', osm_id: 1 }).subtitle, 'Мира, 5');
});

test('the app\'s server can name other search and route servers; odd addresses are ignored', () => {
    useServers({ photon: 'https://ice-rust.store/photon/', valhalla: 'http://plain.example', overpass: ['https://o.example/api', 'javascript:x'] });
    assert.equal(SERVERS.photon, 'https://ice-rust.store/photon');
    assert.equal(SERVERS.valhalla, 'https://valhalla1.openstreetmap.de', 'not https: the default stays');
    assert.deepEqual(SERVERS.overpass, ['https://o.example/api']);
    assert.match(searchUrl('x', null), /^https:\/\/ice-rust\.store\/photon\/api\?/);
    useServers({});
    assert.equal(SERVERS.photon, 'https://photon.komoot.io');
    assert.equal(SERVERS.overpass.length, 2);
    const line = Array.from({ length: 1000 }, (_, i) => [i / 1000, 0.123456789]);
    const thin = thinLine(line, 100);
    assert.equal(thin.length, 100);
    assert.deepEqual(thin[0], [0, 0.12346]);
    assert.deepEqual(thin[99], [0.999, 0.12346]);
});
