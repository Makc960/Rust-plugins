import assert from 'node:assert/strict';
import { test } from 'node:test';

import { along, bearing, cumulative, destination, distance, project, slice, turn } from '../src/js/geo.js';
import { decode, encode } from '../src/js/polyline.js';
import { fromOsrm, fromValhalla } from '../src/js/route.js';
import { isNight, sunElevation } from '../src/js/sun.js';
import { CITY, osrmRoute, START, valhallaTrip } from './helpers.js';

test('distances and bearings on the Earth', () => {
    // One degree of latitude is about 111.2 km.
    assert.ok(Math.abs(distance([37, 55], [37, 56]) - 111195) < 50);
    assert.ok(Math.abs(bearing([37, 55], [37, 56]) - 0) < 1e-6);
    assert.ok(Math.abs(bearing([37, 55], [37.01, 55]) - 90) < 0.01);
    const p = destination([37.6, 55.75], 135, 1000);
    assert.ok(Math.abs(distance([37.6, 55.75], p) - 1000) < 0.5);
    assert.ok(Math.abs(bearing([37.6, 55.75], p) - 135) < 0.1);
    assert.equal(turn(350, 10), 20);
    assert.equal(turn(10, 350), -20);
});

test('a point is projected onto the nearest place of a segment', () => {
    const a = [37.6, 55.75];
    const b = destination(a, 90, 200);
    const side = destination(destination(a, 90, 50), 0, 20);
    const { t, dist } = project(side, a, b);
    assert.ok(Math.abs(t - 0.25) < 0.01);
    assert.ok(Math.abs(dist - 20) < 0.5);
    assert.equal(project(destination(a, 270, 30), a, b).t, 0);
});

test('points along a line and pieces of it', () => {
    const line = [START, destination(START, 90, 100), destination(destination(START, 90, 100), 0, 100)];
    const cum = cumulative(line);
    assert.ok(Math.abs(cum[2] - 200) < 0.1);
    const mid = along(line, cum, 150);
    assert.equal(mid.seg, 1);
    assert.ok(Math.abs(distance(mid.point, line[1]) - 50) < 0.5);
    const piece = slice(line, cum, 50, 150);
    assert.equal(piece.length, 3);
    assert.ok(Math.abs(cumulative(piece)[2] - 100) < 0.5);
});

test('polylines decode what they encode, at six places', () => {
    const pts = [[37.617635, 55.755814], [37.62, 55.76], [-0.1278, 51.507351]];
    const back = decode(encode(pts));
    pts.forEach((p, i) => {
        assert.ok(Math.abs(p[0] - back[i][0]) < 1e-6);
        assert.ok(Math.abs(p[1] - back[i][1]) < 1e-6);
    });
    // Google's example, at five places.
    const g = decode('_p~iF~ps|U_ulLnnqC_mqNvxq`@', 5);
    assert.deepEqual(g.map(([x, y]) => [+x.toFixed(3), +y.toFixed(3)]), [[-120.2, 38.5], [-120.95, 40.7], [-126.453, 43.252]]);
});

test('a Valhalla answer becomes a route with its manoeuvres', () => {
    const { trip } = valhallaTrip(CITY);
    const [r] = fromValhalla({ trip }, 'car');
    assert.ok(Math.abs(r.length - 2980) < 3, `length ${r.length}`);
    assert.deepEqual(r.steps.map((s) => s.type), ['depart', 'turn', 'turn', 'turn', 'roundabout', 'roundabout-exit', 'arrive']);
    assert.deepEqual(r.steps.map((s) => s.modifier).slice(1, 4), ['right', 'left', 'right']);
    assert.equal(r.steps[4].exit, 2);
    assert.equal(r.steps[1].street, 'улица Ленина');
    assert.ok(Math.abs(r.steps[1].at - 900) < 2);
    assert.ok(Math.abs(r.steps[3].length - 1200) < 2);
    // The quiet steps are passed over when looking for the next thing to say.
    assert.equal(r.nextSpoken(4), 6);
    assert.equal(r.steps[5].quiet, true);
    // Time left goes down with the distance, stretch by stretch.
    assert.ok(Math.abs(r.timeLeft(0) - r.duration) < 1);
    assert.ok(Math.abs(r.timeLeft(900) - (r.duration - 75)) < 1);
    assert.ok(Math.abs(r.timeLeft(450) - (r.duration - 37.5)) < 1);
    assert.equal(r.timeLeft(r.length), 0);
    assert.deepEqual(r.via(), ['проспект Мира', 'Тверская улица']);
});

test('Valhalla lanes and signs are read', () => {
    const { trip } = valhallaTrip([
        [1, 0, 500, 'Ленинградское шоссе', 20],
        [20, 30, 300, '', 15, {
            lanes: [{ directions: 2 }, { directions: 2 }, { directions: 66, valid: 64 }, { directions: 64, active: 64 }],
            sign: { exit_toward_elements: [{ text: 'Химки' }], exit_number_elements: [{ text: '12' }] },
        }],
    ]);
    const [r] = fromValhalla({ trip }, 'car');
    const exit = r.steps[1];
    assert.equal(exit.type, 'exit');
    assert.equal(exit.modifier, 'right');
    assert.equal(exit.toward, 'Химки');
    assert.deepEqual(exit.lanes.map((l) => l.use), [false, false, true, true]);
    assert.deepEqual(exit.lanes[2].turns, ['straight', 'right']);
});

test('alternatives come after the best route, and the same road twice is kept once', () => {
    const a = valhallaTrip(CITY).trip;
    const b = valhallaTrip([[1, 0, 2000, 'Ленинский проспект', 15], [10, 90, 1500, 'МКАД', 25]]).trip;
    const routes = fromValhalla({ trip: a, alternates: [{ trip: b }, { trip: a }] }, 'car');
    assert.equal(routes.length, 3);
});

test('an OSRM answer becomes the same route', () => {
    const json = osrmRoute(CITY.slice(0, 4));
    const [r] = fromOsrm(json, 'car');
    assert.deepEqual(r.steps.map((s) => s.type), ['depart', 'turn', 'turn', 'turn', 'arrive']);
    assert.ok(Math.abs(r.length - 2580) < 3);
    assert.ok(Math.abs(r.steps[2].at - 1300) < 2);
    assert.equal(r.steps[3].street, 'проспект Мира');
    assert.equal(r.provider, 'osrm');
});

test('the sun is up at noon in Moscow and down at midnight', () => {
    const noon = new Date(Date.UTC(2026, 5, 21, 9, 30));
    assert.ok(sunElevation(noon, 55.75, 37.62) > 55);
    assert.equal(isNight(new Date(Date.UTC(2026, 5, 21, 21, 0)), 55.75, 37.62), true);
    assert.equal(isNight(new Date(Date.UTC(2026, 11, 21, 9, 0)), 55.75, 37.62), false);
    // A December afternoon in Moscow is already dark by five.
    assert.equal(isNight(new Date(Date.UTC(2026, 11, 21, 14, 0)), 55.75, 37.62), true);
});
