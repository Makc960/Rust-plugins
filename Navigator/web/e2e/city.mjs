// A made-up town for trying the app without the internet: a street grid, a river, parks and blocks,
// drawn by a plain style with no labels. The routes, search results and addresses follow its streets.
// None of it is real map data.

import { destination } from '../src/js/geo.js';
import { encode } from '../src/js/polyline.js';

export const CENTER = [37.6176, 55.7558];
const STEP = 150; // metres between streets

/** The point [x] streets east and [y] streets north of the centre. */
export function grid(x, y) {
    const east = destination(CENTER, x >= 0 ? 90 : 270, Math.abs(x) * STEP);
    return destination(east, y >= 0 ? 0 : 180, Math.abs(y) * STEP);
}

function line(coords, props) {
    return { type: 'Feature', properties: props, geometry: { type: 'LineString', coordinates: coords } };
}

function poly(ring, props) {
    return { type: 'Feature', properties: props, geometry: { type: 'Polygon', coordinates: [[...ring, ring[0]]] } };
}

export function cityGeojson() {
    const features = [];
    const N = 24;
    for (let i = -N; i <= N; i++) {
        const major = i % 4 === 0;
        features.push(line([grid(-N, i), grid(N, i)], { kind: major ? 'major' : 'minor' }));
        features.push(line([grid(i, -N), grid(i, N)], { kind: major ? 'major' : 'minor' }));
    }
    // A river along a row of blocks, and parks here and there.
    features.push(poly([grid(-N, -9.7), grid(N, -9.7), grid(N, -9.25), grid(-N, -9.25)], { kind: 'water' }));
    for (const [x, y] of [[1, 1], [-3, 2], [5, -3], [-6, -5], [9, 6]]) {
        features.push(poly([grid(x + 0.1, y + 0.1), grid(x + 0.9, y + 0.1), grid(x + 0.9, y + 0.9), grid(x + 0.1, y + 0.9)], { kind: 'park' }));
    }
    for (let x = -12; x < 12; x++) {
        for (let y = -12; y < 12; y++) {
            if ([[1, 1], [-3, 2], [5, -3], [-6, -5], [9, 6]].some(([a, b]) => a === x && b === y) || y === -10) continue;
            const a = 0.14, b = 0.48, c = 0.52, d = 0.86;
            features.push(poly([grid(x + a, y + a), grid(x + b, y + a), grid(x + b, y + b), grid(x + a, y + b)], { kind: 'building' }));
            features.push(poly([grid(x + c, y + a), grid(x + d, y + a), grid(x + d, y + b), grid(x + c, y + b)], { kind: 'building' }));
            features.push(poly([grid(x + a, y + c), grid(x + d, y + c), grid(x + d, y + d), grid(x + a, y + d)], { kind: 'building' }));
        }
    }
    return { type: 'FeatureCollection', features };
}

export function cityStyle(theme = 'day') {
    const night = theme === 'night';
    return {
        version: 8,
        name: `Тестовый город (${theme})`,
        sources: { city: { type: 'geojson', data: '/test/city.geojson' } },
        layers: [
            { id: 'background', type: 'background', paint: { 'background-color': night ? '#1b2230' : '#eef0ec' } },
            { id: 'water', type: 'fill', source: 'city', filter: ['==', ['get', 'kind'], 'water'], paint: { 'fill-color': night ? '#25344d' : '#a9cdf0' } },
            { id: 'park', type: 'fill', source: 'city', filter: ['==', ['get', 'kind'], 'park'], paint: { 'fill-color': night ? '#203427' : '#c8e6c0' } },
            { id: 'building', type: 'fill', source: 'city', filter: ['==', ['get', 'kind'], 'building'], paint: { 'fill-color': night ? '#262f40' : '#dcdad6', 'fill-outline-color': night ? '#2e384b' : '#cfccc6' } },
            { id: 'minor-casing', type: 'line', source: 'city', filter: ['==', ['get', 'kind'], 'minor'], paint: { 'line-color': night ? '#2a3446' : '#d4d4d0', 'line-width': ['interpolate', ['linear'], ['zoom'], 12, 1, 17, 12] } },
            { id: 'minor', type: 'line', source: 'city', filter: ['==', ['get', 'kind'], 'minor'], paint: { 'line-color': night ? '#3a465c' : '#ffffff', 'line-width': ['interpolate', ['linear'], ['zoom'], 12, 0.5, 17, 9] } },
            { id: 'major-casing', type: 'line', source: 'city', filter: ['==', ['get', 'kind'], 'major'], paint: { 'line-color': night ? '#3d3a2a' : '#e6c47a', 'line-width': ['interpolate', ['linear'], ['zoom'], 10, 1.5, 17, 18] } },
            { id: 'major', type: 'line', source: 'city', filter: ['==', ['get', 'kind'], 'major'], paint: { 'line-color': night ? '#5b5536' : '#fde3a1', 'line-width': ['interpolate', ['linear'], ['zoom'], 10, 1, 17, 14] } },
            { id: 'label-anchor', type: 'symbol', source: 'city', filter: ['==', ['get', 'kind'], 'none'], layout: {} },
        ],
    };
}

/** A Valhalla answer driving along grid streets: legs of [type, dx, dy, street, m/s]. */
export function gridTrip(start, legs, extra = {}) {
    const shape = [grid(...start)];
    const maneuvers = [];
    let [x, y] = start;
    for (const [type, dx, dy, street, speed, more = {}] of legs) {
        const begin = shape.length - 1;
        const n = Math.max(Math.abs(dx), Math.abs(dy)) * 3;
        for (let k = 1; k <= n; k++) shape.push(grid(x + (dx * k) / n, y + (dy * k) / n));
        x += dx;
        y += dy;
        const metres = Math.hypot(dx, dy) * STEP;
        maneuvers.push({ type, street_names: street ? [street] : undefined, length: metres / 1000, time: metres / speed, begin_shape_index: begin, end_shape_index: shape.length - 1, ...more });
    }
    maneuvers.push({ type: 5, length: 0, time: 0, begin_shape_index: shape.length - 1, end_shape_index: shape.length - 1 });
    const length = maneuvers.reduce((s, m) => s + m.length, 0);
    const time = maneuvers.reduce((s, m) => s + m.time, 0);
    return {
        locations: [], legs: [{ maneuvers, shape: encode(shape), summary: { length, time } }],
        summary: { length, time, has_toll: false, has_ferry: false, has_highway: false, ...extra },
        status: 0, units: 'kilometers', language: 'ru-RU',
    };
}

export function routeAnswer() {
    const lanes = [{ directions: 8 }, { directions: 2 }, { directions: 2, valid: 2 }, { directions: 66, active: 64 }];
    const best = gridTrip([0, 0], [
        [1, 6, 0, 'Тверская улица', 11],
        [10, 0, -3, 'улица Ленина', 9],
        [15, 1, 0, 'Садовая улица', 7],
        [10, 0, -4, 'проспект Мира', 13, { lanes }],
        [15, 2, 0, 'Набережная улица', 9],
    ]);
    const alt = gridTrip([0, 0], [
        [1, 0, -4, 'Петровка', 10],
        [15, 9, 0, 'Кольцевая улица', 14],
        [10, 0, -3, 'Речная улица', 9],
    ], { has_toll: true });
    return { trip: best, alternates: [{ trip: alt }] };
}

export function searchAnswer() {
    const place = (name, x, y, street, house, key, value) => ({
        type: 'Feature',
        geometry: { type: 'Point', coordinates: grid(x, y) },
        properties: { name, street, housenumber: house, city: 'Тестоград', osm_key: key, osm_value: value, osm_type: 'N', osm_id: Math.round((x + 50) * 1000 + y) },
    });
    return {
        type: 'FeatureCollection',
        features: [
            place('Кофейня «Утро»', 9, -7, 'Набережная улица', '12', 'amenity', 'cafe'),
            place('Кафе «Самовар»', -2, 3, 'Петровка', '5', 'amenity', 'cafe'),
            place('Кафе на углу', 4, 4, 'улица Мира', '30', 'amenity', 'cafe'),
            place(undefined, 3, -2, 'улица Ленина', '17', 'building', 'yes'),
        ],
    };
}

export function reverseAnswer(point) {
    return { type: 'FeatureCollection', features: [{ type: 'Feature', geometry: { type: 'Point', coordinates: point }, properties: { street: 'Садовая улица', housenumber: '8', city: 'Тестоград', osm_key: 'building', osm_value: 'yes' } }] };
}

/** Speed limits for whatever stretch is asked: 60 on its first half, 40 on the rest. */
export function traceAnswer(request) {
    const shape = request.encoded_polyline;
    const n = (shape ? decodeCount(shape) : 2) - 1;
    const mid = Math.floor(n / 2);
    return {
        units: 'kilometers', shape,
        edges: [{ begin_shape_index: 0, end_shape_index: mid, speed_limit: 60 }, { begin_shape_index: mid, end_shape_index: n, speed_limit: 40 }],
    };
}

function decodeCount(polyline) {
    let count = 0;
    for (let i = 0; i < polyline.length;) {
        for (const _ of [0, 1]) {
            let b;
            do b = polyline.charCodeAt(i++) - 63; while (b >= 0x20 && i < polyline.length);
        }
        count++;
    }
    return count;
}

/** A speed camera on Тверская улица, a few blocks from the start. */
export function overpassAnswer() {
    const at = (x, y) => {
        const [lon, lat] = grid(x, y);
        return { lon, lat };
    };
    return { elements: [
        { type: 'node', id: 4242, ...at(4, 0), tags: { highway: 'speed_camera', maxspeed: '60' } },
        { type: 'node', id: 4243, ...at(2, 0), tags: { traffic_calming: 'bump' } },
        { type: 'node', id: 4244, ...at(6, -1.5), tags: { railway: 'level_crossing' } },
        { type: 'way', id: 4245, center: at(7.25, -5), tags: { amenity: 'school', name: 'Школа № 1' } },
    ] };
}

/** What drivers reported in the town: a crash on Тверская and a police post on проспект Мира. */
export function reportsAnswer() {
    const at = (x, y) => {
        const [lon, lat] = grid(x, y);
        return { lon, lat };
    };
    const now = Date.now();
    return { reports: [
        { id: 'r1', kind: 'crash', ...at(5, 0), heading: 90, created: now - 12 * 60000, confirmed: now - 4 * 60000, votes: 3 },
        { id: 'r2', kind: 'police', ...at(7, -6), heading: 180, created: now - 30 * 60000, confirmed: now - 30 * 60000, votes: 1 },
        { id: 'r3', kind: 'works', ...at(2, -4), heading: null, created: now - 3 * 3600000, confirmed: now - 3600000, votes: 2 },
    ], kinds: ['crash', 'works', 'police', 'camera', 'jam', 'danger'] };
}

/** Friends from «Рядом», about town. */
export function friendsAnswer() {
    const place = (x, y, minutes, battery) => {
        const [lon, lat] = grid(x, y);
        return { lon, lat, accuracy: 10, battery, charging: false, time: Date.now() - minutes * 60000, at: Date.now() - minutes * 60000 };
    };
    return {
        me: { id: 'me', name: 'Я', color: '#2f6fed' },
        friends: [
            { id: 'f1', name: 'Аня Смирнова', color: '#e5484d', sharing: true, place: place(3, 2, 2, 81) },
            { id: 'f2', name: 'Боря', color: '#12a150', sharing: true, place: place(-3, -2, 40, 35) },
        ],
        incoming: [], outgoing: [], now: Date.now(),
    };
}

/** Places of a kind near a point: three of them along the streets around it. */
export function nearbyAnswer(point, tag) {
    const names = { 'amenity:fuel': ['Лукойл', 'Роснефть', 'Газпромнефть'], 'amenity:cafe': ['Шоколадница', 'Кофе Хауз', 'Кафе «Пять звёзд»'] }[tag] || ['Место 1', 'Место 2', 'Место 3'];
    const [key, value] = tag.split(':');
    const near = (dx, dy) => [point[0] + dx * 0.0024, point[1] + dy * 0.00135];
    return {
        type: 'FeatureCollection',
        features: names.map((name, i) => ({
            type: 'Feature', geometry: { type: 'Point', coordinates: near([0.2, 1, -0.4][i], [0.05, -0.3, 0.6][i]) },
            properties: { name, street: 'Тестовая улица', housenumber: String(i + 1), city: 'Тестоград', osm_key: key, osm_value: value, osm_type: 'N', osm_id: Math.round(point[0] * 1e4) * 10 + i },
        })),
    };
}
