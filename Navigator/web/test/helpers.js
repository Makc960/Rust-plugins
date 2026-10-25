// Routes made up for the tests, in the servers' own formats as their documentation gives them.

import { destination } from '../src/js/geo.js';
import { encode } from '../src/js/polyline.js';

export const START = [37.6, 55.75];

/**
 * A Valhalla answer for a drive in stretches: each [type, heading, metres, street, m/s, extra],
 * the type being Valhalla's manoeuvre number at the stretch's start. A destination is added at the end.
 */
export function valhallaTrip(stretches, start = START) {
    const shape = [start];
    const maneuvers = [];
    let p = start;
    for (const [type, heading, metres, street, speed = 14, extra = {}] of stretches) {
        const begin = shape.length - 1;
        // A point every 50 m, as a real road has bends and nodes.
        const n = Math.max(1, Math.round(metres / 50));
        for (let k = 1; k <= n; k++) {
            p = destination(p, heading, metres / n);
            shape.push(p);
        }
        maneuvers.push({
            type, street_names: street ? [street] : undefined, length: metres / 1000, time: metres / speed,
            begin_shape_index: begin, end_shape_index: shape.length - 1, travel_mode: 'drive', ...extra,
        });
    }
    maneuvers.push({ type: 4, length: 0, time: 0, begin_shape_index: shape.length - 1, end_shape_index: shape.length - 1 });
    const length = maneuvers.reduce((s, m) => s + m.length, 0);
    const time = maneuvers.reduce((s, m) => s + m.time, 0);
    return {
        trip: {
            locations: [{ lat: start[1], lon: start[0] }, { lat: p[1], lon: p[0] }],
            legs: [{ maneuvers, shape: encode(shape), summary: { length, time } }],
            summary: { length, time, has_toll: false, has_ferry: false, has_highway: false },
            status: 0, status_message: 'Found route between points', units: 'kilometers', language: 'ru-RU',
        },
        end: p,
    };
}

/** The same drive as OSRM gives it: a step per stretch with its own line. */
export function osrmRoute(stretches, start = START) {
    const steps = [];
    let p = start;
    const kinds = { 1: ['depart'], 10: ['turn', 'right'], 15: ['turn', 'left'], 26: ['roundabout', 'right'] };
    for (const [type, heading, metres, street, speed = 14, extra = {}] of stretches) {
        const line = [p];
        const n = Math.max(1, Math.round(metres / 50));
        for (let k = 1; k <= n; k++) {
            p = destination(p, heading, metres / n);
            line.push(p);
        }
        const [t, modifier] = kinds[type] || ['turn', 'straight'];
        steps.push({
            geometry: encode(line), name: street || '', distance: metres, duration: metres / speed,
            maneuver: { type: t, modifier, location: line[0], exit: extra.roundabout_exit_count },
        });
    }
    steps.push({ geometry: encode([p, p]), name: '', distance: 0, duration: 0, maneuver: { type: 'arrive', location: p } });
    const distance = steps.reduce((s, x) => s + x.distance, 0);
    const duration = steps.reduce((s, x) => s + x.duration, 0);
    return { code: 'Ok', routes: [{ distance, duration, geometry: '', legs: [{ steps, distance, duration }] }] };
}

/** A city drive: 900 m east, right for 400 m, left for 80 m, right again for 1.2 km, then a roundabout. */
export const CITY = [
    [1, 90, 900, 'Тверская улица', 12],
    [10, 180, 400, 'улица Ленина', 10],
    [15, 90, 80, 'Садовая улица', 8],
    [10, 180, 1200, 'проспект Мира', 15],
    [26, 180, 100, '', 8, { roundabout_exit_count: 2 }],
    [27, 200, 300, 'Кольцевая дорога', 12],
];
