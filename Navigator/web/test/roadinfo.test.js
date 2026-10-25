import assert from 'node:assert/strict';
import { test } from 'node:test';

import { destination } from '../src/js/geo.js';
import { encode } from '../src/js/polyline.js';
import { cameraAhead, cameraQuery, limitAt, limitChunks, parseCameras, parseLimits, traceRequest } from '../src/js/roadinfo.js';
import { fromOsrm, fromValhalla } from '../src/js/route.js';
import { alongRoute, nearby, osrmRequest, routes, valhallaRequest } from '../src/js/services.js';
import { Tracker } from '../src/js/tracker.js';
import { Announcer, RoadAlerts } from '../src/js/voice.js';
import { CITY, osrmRoute, START, valhallaTrip } from './helpers.js';

const cityRoute = () => fromValhalla({ trip: valhallaTrip(CITY).trip }, 'car')[0];

test('a route with a stop joins its legs, and the stop is spoken', () => {
    const first = valhallaTrip([[1, 90, 600, 'Тверская улица', 12], [10, 180, 300, 'улица Ленина', 10]]);
    const second = valhallaTrip([[1, 180, 200, 'улица Ленина', 10], [15, 90, 400, 'Садовая улица', 10]], first.end);
    first.trip.legs[0].maneuvers.at(-1).type = 5; // the stop is on the right
    const trip = { ...first.trip, legs: [first.trip.legs[0], second.trip.legs[0]], summary: { time: 200, length: 1.5 } };
    const [r] = fromValhalla({ trip }, 'car');
    assert.deepEqual(r.steps.map((s) => s.type), ['depart', 'turn', 'waypoint', 'turn', 'arrive']);
    assert.equal(r.steps[2].modifier, 'right');
    assert.ok(Math.abs(r.steps[2].at - 900) < 2);
    assert.ok(Math.abs(r.length - 1500) < 3);
    assert.equal(r.stops.length, 1);
    // Driving past the stop: "через N метров промежуточная точка", then "Промежуточная точка справа".
    const tracker = new Tracker(r);
    const voice = new Announcer(r);
    const said = [];
    for (let d = 0; d <= r.length; d += 10) {
        const text = voice.update({ ...tracker.progressAt(d), arrived: false }, 10);
        if (text) said.push(text);
    }
    assert.ok(said.some((s) => /^Через \d+ метров промежуточная точка, справа\.$/.test(s)), said.join(' | '));
    assert.ok(said.includes('Промежуточная точка справа.'), said.join(' | '));
});

test('an OSRM route with a stop joins the same way', () => {
    const a = osrmRoute([[1, 90, 500, 'Тверская улица', 12]]);
    const b = osrmRoute([[1, 0, 300, 'Петровка', 10], [10, 90, 200, 'Садовая улица', 10]], destination(START, 90, 500));
    a.routes[0].legs[0].steps.at(-1).maneuver.modifier = 'left';
    const json = { code: 'Ok', routes: [{ duration: 100, legs: [a.routes[0].legs[0], b.routes[0].legs[0]] }] };
    const [r] = fromOsrm(json, 'car');
    assert.deepEqual(r.steps.map((s) => s.type), ['depart', 'waypoint', 'turn', 'arrive']);
    assert.equal(r.steps[1].modifier, 'left');
    assert.ok(Math.abs(r.steps[2].at - 800) < 2);
});

test('requests carry the stops, and no alternatives with them', () => {
    const body = JSON.parse(decodeURIComponent(valhallaRequest([37.6, 55.7], [37.8, 55.8], 'car', { stops: [[37.7, 55.75]] }).split('json=')[1]));
    assert.equal(body.locations.length, 3);
    assert.deepEqual(body.locations[1], { lat: 55.75, lon: 37.7, type: 'break' });
    assert.equal(body.alternates, undefined);
    assert.match(osrmRequest([37.6, 55.7], [37.8, 55.8], 'car', { stops: [[37.7, 55.75]] }), /37\.600000,55\.700000;37\.700000,55\.750000;37\.800000,55\.800000\?/);
});

test('in a tunnel the car goes on along the route, and is found again after it', () => {
    const r = cityRoute();
    const tracker = new Tracker(r);
    const fixAt = (d, t) => {
        const at = destinationAlong(r, d);
        return { lon: at[0], lat: at[1], accuracy: 6, speed: 15, heading: null, time: t };
    };
    let t = 0;
    for (let d = 1400; d <= 1700; d += 15) tracker.update(fixAt(d, (t += 1000)));
    const guess = tracker.predict(15 * 20);
    assert.ok(Math.abs(guess.along - 2000) < 2, `guessed ${guess.along}`);
    assert.equal(guess.offRoute, false);
    // Out of the tunnel 40 s later, 600 m further on: placed there, not taken for leaving the route.
    const back = tracker.update(fixAt(2300, t + 40000));
    assert.ok(Math.abs(back.along - 2300) < 3, `found at ${back.along}`);
    assert.equal(back.offRoute, false);
});

function destinationAlong(r, d) {
    let i = 0;
    while (i < r.coords.length - 2 && r.cum[i + 1] < d) i++;
    const f = (d - r.cum[i]) / (r.cum[i + 1] - r.cum[i]);
    return [r.coords[i][0] + (r.coords[i + 1][0] - r.coords[i][0]) * f, r.coords[i][1] + (r.coords[i + 1][1] - r.coords[i][1]) * f];
}

test('speed limits from trace_attributes are placed along the route', () => {
    const r = cityRoute();
    const [chunk] = limitChunks(r);
    assert.equal(chunk.from, 0);
    assert.ok(Math.abs(chunk.to - r.length) < 1);
    const req = traceRequest(chunk.coords);
    assert.equal(req.shape_match, 'edge_walk');
    assert.ok(req.filters.attributes.includes('edge.speed_limit'));
    // The matched shape is the route's; edges: 60 on the first 900 m, unknown, then 40, 40 and none.
    const shape = encode(r.coords);
    const idx = (d) => r.cum.findIndex((c) => c >= d - 0.5);
    const json = {
        shape,
        edges: [
            { begin_shape_index: 0, end_shape_index: idx(900), speed_limit: 60 },
            { begin_shape_index: idx(900), end_shape_index: idx(1300) },
            { begin_shape_index: idx(1300), end_shape_index: idx(1380), speed_limit: 40 },
            { begin_shape_index: idx(1380), end_shape_index: idx(2580), speed_limit: 40 },
            { begin_shape_index: idx(2580), end_shape_index: r.coords.length - 1, speed_limit: 'unlimited' },
        ],
    };
    const limits = parseLimits(json, 0, r.length);
    assert.equal(limits.length, 3);
    assert.equal(limitAt(limits, 450), 60);
    assert.equal(limitAt(limits, 1100), undefined);
    assert.equal(limitAt(limits, 2000), 40);
    assert.equal(limitAt(limits, 2700), null);
    assert.deepEqual(parseLimits({ edges: [] }, 0, 100), []);
});

test('speed cameras near the route are kept and the rest left out', () => {
    const r = cityRoute();
    const on = destinationAlong(r, 600);
    const twice = destination(on, 0, 12);
    const aside = destination(destinationAlong(r, 1500), 90, 400);
    const json = { elements: [
        { type: 'node', id: 1, lat: on[1], lon: on[0], tags: { highway: 'speed_camera', maxspeed: '60' } },
        { type: 'node', id: 2, lat: twice[1], lon: twice[0], tags: { highway: 'speed_camera' } },
        { type: 'node', id: 3, lat: aside[1], lon: aside[0], tags: { highway: 'speed_camera', maxspeed: '40' } },
    ] };
    const cams = parseCameras(json, r);
    assert.equal(cams.length, 1);
    assert.ok(Math.abs(cams[0].at - 600) < 3);
    assert.equal(cams[0].limit, 60);
    assert.equal(cameraAhead(cams, 300, 400).id, 1);
    assert.equal(cameraAhead(cams, 100, 400), null);
    const q = cameraQuery(r);
    assert.match(q, /^\[out:json\]\[timeout:25\];node\(around:35,55\.75000,37\.60000,/);
    assert.match(q, /\["highway"="speed_camera"\];out body;$/);
});

test('a camera is told once, with its limit, and a word to slow down when speeding', () => {
    const alerts = new RoadAlerts();
    const cams = [{ at: 1000, limit: 60, id: 7 }];
    assert.equal(alerts.update({ along: 300 }, 15, cams, 40), null);
    assert.equal(alerts.update({ along: 750 }, 15, cams, 40), 'Впереди камера, ограничение 60.');
    assert.equal(alerts.update({ along: 800 }, 15, cams, 40), null);
    const fast = new RoadAlerts();
    assert.equal(fast.update({ along: 700 }, 22, cams, 40), 'Впереди камера, ограничение 60. Сбавьте скорость.');
    assert.equal(new RoadAlerts().update({ along: 750 }, 15, [{ at: 1000, id: 8 }], 50), 'Впереди камера, ограничение 50.');
    assert.equal(new RoadAlerts().update({ along: 750 }, 15, [{ at: 1000, id: 9 }], undefined), 'Впереди камера.');
});

test('places of a kind along the route: near the road and ahead, the nearest first', async () => {
    const r = cityRoute();
    // Off to the side of the stretch at [d]: east of the southbound parts, north of the eastbound.
    const by = (d, off) => destination(destinationAlong(r, d), (d > 900 && d < 1300) || (d > 1380 && d < 2580) ? 90 : 0, off);
    let id = 0;
    const station = (name, p) => ({ type: 'Feature', geometry: { type: 'Point', coordinates: p }, properties: { name, osm_key: 'amenity', osm_value: 'fuel', osm_type: 'N', osm_id: ++id } });
    const answer = { features: [station('Лукойл', by(1200, 30)), station('Роснефть', by(2500, 600)), station('Газпром', by(300, 20)), station('Татнефть', by(2200, 40))] };
    const http = async (url) => {
        assert.match(url, /\/reverse\?lat=.*osm_tag=amenity%3Afuel/);
        return { status: 200, text: JSON.stringify(answer) };
    };
    const fuel = { id: 'fuel', label: 'АЗС', tag: 'amenity:fuel' };
    const found = await alongRoute(http, r, 500, fuel);
    assert.deepEqual(found.map((p) => p.title), ['Лукойл', 'Татнефть', 'Роснефть']);
    assert.ok(Math.abs(found[0].ahead - 700) < 5);
    assert.ok(found[0].off < 35);
    const near = await nearby(http, START, fuel);
    assert.equal(near[0].kind, 'АЗС');
    assert.ok(near[0].away <= near[1].away);
});

test('a failing route server with stops still falls back to OSRM', async () => {
    const http = async (url) => (url.includes('valhalla')
        ? { status: 503, text: '' }
        : { status: 200, text: JSON.stringify(osrmRoute([[1, 90, 500, 'Тверская улица', 12]])) });
    const found = await routes(http, [37.6, 55.75], [37.61, 55.75], 'car', { stops: [] });
    assert.equal(found[0].provider, 'osrm');
});
