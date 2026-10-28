import assert from 'node:assert/strict';
import { test } from 'node:test';

import { destination } from '../src/js/geo.js';
import { fromValhalla } from '../src/js/route.js';
import { Simulator } from '../src/js/sim.js';
import { Tracker } from '../src/js/tracker.js';
import { Announcer } from '../src/js/voice.js';
import { CITY, valhallaTrip } from './helpers.js';

function cityRoute(stretches = CITY, mode = 'car') {
    return fromValhalla({ trip: valhallaTrip(stretches).trip }, mode)[0];
}

/** Drives the whole route in simulated seconds and collects what is said, and where. */
function drive(route) {
    const sim = new Simulator(route);
    const tracker = new Tracker(route);
    const voice = new Announcer(route);
    const said = [];
    let time = 0;
    let last = null;
    for (let i = 0; i < 2000 && !(last && last.arrived); i++) {
        const v = sim.speedAt(sim.d);
        sim.d = Math.min(route.length, sim.d + v);
        time += 1000;
        const fix = sim.fixAt(sim.d, v, time);
        last = tracker.update(fix);
        assert.ok(last, 'every fix is placed');
        assert.equal(last.offRoute, false, `off the route at ${sim.d}`);
        const text = voice.update(last, v);
        if (text) said.push({ at: Math.round(last.along), text });
    }
    return { said, last };
}

test('a drive through town is told turn by turn', () => {
    const route = cityRoute();
    const { said, last } = drive(route);
    assert.equal(last.arrived, true);
    const texts = said.map((s) => s.text);
    assert.ok(texts.includes('Поверните направо.'), texts.join(' | '));
    // The right turn 80 m after the left comes with it.
    assert.ok(texts.some((t) => /^Через \d+ метров поверните налево на Садовую улицу, затем направо\.$/.test(t)), texts.join(' | '));
    // The street a turn leads onto is named, as it goes after "на".
    assert.ok(texts.includes('Через 250 метров поверните направо на улицу Ленина.'), texts.join(' | '));
    assert.ok(texts.some((t) => /на круговом движении второй съезд/.test(t)), texts.join(' | '));
    assert.equal(texts[texts.length - 1], 'Вы приехали.');
    // Nothing is said twice in a row, and every turn is told before it, not after.
    for (let i = 1; i < texts.length; i++) assert.notEqual(texts[i], texts[i - 1]);
    const firstRight = said.find((s) => s.text === 'Поверните направо.');
    assert.ok(firstRight.at < 900 && firstRight.at > 820, `said at ${firstRight.at}`);
});

test('a long fast road gets a far warning and a word on how long it goes on', () => {
    const route = cityRoute([
        [1, 0, 300, 'улица Мира', 10],
        [10, 90, 6000, 'М-11', 30],
        [20, 120, 800, '', 20],
        [15, 30, 500, 'Ленинградская улица', 12],
    ]);
    const { said } = drive(route);
    const texts = said.map((s) => s.text);
    assert.ok(texts.includes('Продолжайте движение 6 километров.'), texts.join(' | '));
    assert.ok(texts.some((t) => /^Через (километр|полтора километра|2 километра) съезжайте направо\.$/.test(t)), texts.join(' | '));
    assert.ok(texts.includes('Съезжайте направо.'), texts.join(' | '));
});

test('leaving the route is noticed after a few seconds, and only then', () => {
    const route = cityRoute();
    const tracker = new Tracker(route);
    const sim = new Simulator(route);
    let p = null;
    for (let t = 0; t < 30; t++) {
        sim.d += 10;
        p = tracker.update(sim.fixAt(sim.d, 10, t * 1000));
    }
    assert.equal(p.offRoute, false);
    // Missing the right turn: going on east past it.
    let fix = sim.fixAt(900, 10, 31000);
    let pos = [fix.lon, fix.lat];
    const results = [];
    for (let t = 0; t < 8; t++) {
        pos = destination(pos, 90, 12);
        results.push(tracker.update({ lon: pos[0], lat: pos[1], accuracy: 8, speed: 12, heading: 90, time: 32000 + t * 1000 }).offRoute);
    }
    assert.equal(results[0], false);
    assert.equal(results[results.length - 1], true);
});

test('a single wild fix does not count as leaving the route', () => {
    const route = cityRoute();
    const tracker = new Tracker(route);
    const sim = new Simulator(route);
    for (let t = 0; t < 20; t++) {
        sim.d += 10;
        tracker.update(sim.fixAt(sim.d, 10, t * 1000));
    }
    const far = destination([sim.fixAt(sim.d, 10, 0).lon, sim.fixAt(sim.d, 10, 0).lat], 0, 150);
    assert.equal(tracker.update({ lon: far[0], lat: far[1], accuracy: 60, speed: 10, heading: 90, time: 21000 }).offRoute, false);
    sim.d += 10;
    const back = tracker.update(sim.fixAt(sim.d, 10, 22000));
    assert.equal(back.offRoute, false);
    assert.ok(Math.abs(back.along - sim.d) < 2);
});

test('a start away from the road waits for the car to reach it', () => {
    const route = cityRoute();
    const tracker = new Tracker(route);
    // In a yard 70 m from where the route begins, parked.
    const yard = destination(route.coords[0], 0, 70);
    for (let t = 0; t < 10; t++) {
        const p = tracker.update({ lon: yard[0], lat: yard[1], accuracy: 15, speed: 0, heading: null, time: t * 1000 });
        assert.equal(p.offRoute, false);
    }
});

test('driving the wrong way along the route asks for a new one', () => {
    const route = cityRoute();
    const tracker = new Tracker(route);
    const sim = new Simulator(route);
    sim.d = 500;
    tracker.update(sim.fixAt(sim.d, 10, 0));
    let off = false;
    for (let t = 1; t < 12; t++) {
        sim.d -= 10;
        const fix = sim.fixAt(sim.d, 10, t * 1000);
        off = tracker.update({ ...fix, heading: 270 }).offRoute || off;
    }
    assert.equal(off, true);
});

test('walking directions use short distances', () => {
    const route = cityRoute([
        [1, 90, 200, 'Тверская улица', 1.4],
        [10, 180, 150, 'Камергерский переулок', 1.4],
    ], 'foot');
    const { said } = drive(route);
    const texts = said.map((s) => s.text);
    assert.ok(texts.includes('Через 50 метров поверните направо на Камергерский переулок.'), texts.join(' | '));
    assert.ok(texts.includes('Поверните направо.'), texts.join(' | '));
});

test('with street names off, only the turns are told', () => {
    const route = cityRoute();
    const tracker = new Tracker(route);
    const voice = new Announcer(route, { streets: false });
    const said = [];
    for (let d = 0; d <= route.length; d += 10) {
        const text = voice.update({ ...tracker.progressAt(d), arrived: false }, 10);
        if (text) said.push(text);
    }
    assert.ok(said.includes('Через 250 метров поверните направо.'), said.join(' | '));
    assert.ok(!said.some((t) => / на улицу /.test(t)), said.join(' | '));
});
