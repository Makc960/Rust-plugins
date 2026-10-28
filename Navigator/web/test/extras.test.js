import assert from 'node:assert/strict';
import { test } from 'node:test';

import { destination } from '../src/js/geo.js';
import { countByZoom, countInBox, estimateMb, styleUrls, tilesAlong, tilesInBox, tileUrls, tileXY } from '../src/js/offline.js';
import { fromValhalla } from '../src/js/route.js';
import { suggestion } from '../src/js/suggest.js';
import { valhallaTrip } from './helpers.js';

const KRASNODAR = [38.9769, 45.0355];

test('tiles over a town, counted and listed alike, and their addresses', () => {
    assert.deepEqual(tileXY(0, 0, 1), [1, 1]);
    assert.deepEqual(tileXY(KRASNODAR[0], KRASNODAR[1], 14), [9965, 5891]);
    const box = [38.85, 44.95, 39.15, 45.15];
    const tiles = tilesInBox(box, 10, 14);
    assert.equal(tiles.length, countInBox(box, 10, 14));
    assert.equal(new Set(tiles.map((t) => `${t.z}/${t.x}/${t.y}`)).size, tiles.length, 'no tile twice');
    const counts = countByZoom(box, 0, 14);
    assert.equal(counts[0], 1);
    assert.ok(counts[14] > 100 && counts[14] < 300, `${counts[14]} tiles at 14`);
    const mb = estimateMb(counts);
    assert.ok(mb > 5 && mb < 40, `${mb} MB for a town`);
    assert.deepEqual(tileUrls('https://t/planet/v1/{z}/{x}/{y}.pbf', [{ z: 3, x: 4, y: 5 }]), ['https://t/planet/v1/3/4/5.pbf']);
    const style = styleUrls('https://t/fonts/{fontstack}/{range}.pbf', 'https://t/sprites/ofm');
    assert.ok(style.includes('https://t/fonts/Noto%20Sans%20Regular/1024-1279.pbf'), 'Cyrillic letters');
    assert.ok(style.includes('https://t/sprites/ofm@2x.png'));
});

test('the tiles a drive passes over, with a tile either side at the nearest zoom', () => {
    // 20 km east of Krasnodar.
    const trip = valhallaTrip([[1, 90, 20000, 'М-4', 25]], KRASNODAR);
    const [route] = fromValhalla({ trip: trip.trip }, 'car');
    const tiles = tilesAlong(route, [10, 14], 1);
    const at14 = tiles.filter((t) => t.z === 14);
    const xs = new Set(at14.map((t) => t.x));
    const ys = new Set(at14.map((t) => t.y));
    assert.ok(xs.size >= 15 && xs.size <= 20, `${xs.size} columns`);
    assert.equal(ys.size, 3, 'the road\'s row and one either side');
    assert.ok(tiles.filter((t) => t.z === 10).length <= 2);
    const [x, y] = tileXY(...destination(KRASNODAR, 90, 10000), 14);
    assert.ok(at14.some((t) => t.x === x && t.y === y), 'the middle of the road is there');
});

test('a drive offered: to work on a weekday morning from home, home in the evening, the usual place', () => {
    const home = { title: 'Дом', lon: KRASNODAR[0], lat: KRASNODAR[1], id: 'h' };
    const workAt = destination(KRASNODAR, 45, 6000);
    const work = { title: 'Работа', lon: workAt[0], lat: workAt[1], id: 'w' };
    const tuesday = (h) => new Date(2026, 9, 27, h, 10);
    const near = (p) => [p.lon + 0.001, p.lat];
    assert.equal(suggestion({ now: tuesday(8), here: near(home), home, work }).to, 'work');
    assert.equal(suggestion({ now: new Date(2026, 9, 25, 8, 10), here: near(home), home, work }), null, 'not on a Sunday');
    assert.equal(suggestion({ now: tuesday(8), here: near(work), home, work }), null, 'already at work');
    assert.equal(suggestion({ now: tuesday(18), here: near(work), home, work }).to, 'home');
    assert.equal(suggestion({ now: tuesday(18), here: near(home), home, work }), null, 'already home');
    assert.equal(suggestion({ now: tuesday(13), here: near(home), home, work }), null, 'midday: nothing');
    const gymAt = destination(KRASNODAR, 180, 3000);
    const gym = { title: 'Спортзал', lon: gymAt[0], lat: gymAt[1], id: 'g' };
    const trips = [1, 2, 3].map((d) => ({ dest: gym, start: new Date(2026, 9, 20 + d, 13, 5).getTime() }));
    assert.equal(suggestion({ now: tuesday(13), here: near(home), home, work, trips }).place.title, 'Спортзал');
    assert.equal(suggestion({ now: tuesday(13), here: near(home), home, work, trips: trips.slice(1) }), null, 'twice is not a habit');
    assert.equal(suggestion({ now: tuesday(8), here: null, home, work }), null, 'nowhere known');
});
