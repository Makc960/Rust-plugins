// What the road itself says along a route: speed limits (from Valhalla's trace_attributes), and
// what to warn about on it, placed in metres along the route: speed cameras, speed bumps, railway
// crossings and schools (from OpenStreetMap through Overpass), sharp bends (from the route's own
// line) and what other drivers reported (from the app's server).

import { along, bearing, cumulative, distance, project, turn } from './geo.js';
import { decode, encode } from './polyline.js';

// Valhalla matches a path of at most about 200 km at a time.
export const LIMIT_CHUNK = 150000;

/** The stretches of [route] to ask speed limits for: [{from, to, coords}] in metres along. */
export function limitChunks(route) {
    const out = [];
    for (let from = 0; from < route.length; from += LIMIT_CHUNK) {
        const to = Math.min(route.length, from + LIMIT_CHUNK);
        const a = along(route.coords, route.cum, from);
        const b = along(route.coords, route.cum, to);
        const coords = [a.point, ...route.coords.slice(a.seg + 1, b.seg + 1), b.point];
        out.push({ from, to, coords });
    }
    return out;
}

/** The request for one stretch: walking the route's own edges, asking only for the limits. */
export function traceRequest(coords) {
    return {
        encoded_polyline: encode(coords),
        shape_match: 'edge_walk',
        costing: 'auto',
        filters: { attributes: ['edge.speed_limit', 'edge.begin_shape_index', 'edge.end_shape_index', 'shape'], action: 'include' },
    };
}

/**
 * Speed limits from a trace_attributes answer for the stretch [from, to]: [{from, to, limit}] in
 * metres along the route; limit is km/h, or null where there is none (an autobahn).
 * Edges with no known limit are left out.
 */
export function parseLimits(json, from, to) {
    if (!json || !Array.isArray(json.edges) || !json.shape) return [];
    const shape = decode(json.shape);
    if (shape.length < 2) return [];
    const cum = cumulative(shape);
    // The matched line is the route's own, give or take its ends: scale it onto the stretch.
    const scale = (to - from) / (cum[cum.length - 1] || 1);
    const out = [];
    for (const e of json.edges) {
        if (e.speed_limit == null || e.begin_shape_index == null || e.end_shape_index == null) continue;
        const limit = e.speed_limit === 'unlimited' ? null : Number(e.speed_limit);
        if (limit !== null && !(limit > 0)) continue;
        const a = from + cum[Math.min(e.begin_shape_index, cum.length - 1)] * scale;
        const b = from + cum[Math.min(e.end_shape_index, cum.length - 1)] * scale;
        const last = out[out.length - 1];
        // Neighbouring edges with the same limit make one stretch.
        if (last && last.limit === limit && a - last.to < 1) last.to = b;
        else out.push({ from: a, to: b, limit });
    }
    return out;
}

/** The speed limit [d] metres along, or undefined when not known. */
export function limitAt(limits, d) {
    for (const l of limits) {
        if (d >= l.from && d < l.to) return l.limit;
        if (l.from > d) break;
    }
    return undefined;
}

// ---- Hazards ----

/**
 * What a warning can be about. [reach]: how far from the route's line a mapped thing may be and
 * still be on it. Drivers' reports carry the kind they were sent with (see server/src/reports.mjs).
 */
export const HAZARDS = {
    camera: { label: 'Камера', reach: 35 },
    bump: { label: 'Лежачий полицейский', reach: 12 },
    rail: { label: 'Ж/д переезд', reach: 15 },
    school: { label: 'Школа', reach: 100 },
    curve: { label: 'Крутой поворот' },
    crash: { label: 'ДТП', report: true },
    works: { label: 'Ремонт дороги', report: true },
    police: { label: 'Пост ДПС', report: true },
    mobile: { label: 'Камера', report: true },
    jam: { label: 'Пробка', report: true },
    danger: { label: 'Опасность', report: true },
};

// Drivers report a camera as "camera"; on the route it becomes a "mobile" one, told apart from mapped ones.
export const REPORT_KINDS = ['crash', 'police', 'works', 'camera', 'jam', 'danger'];
const fromReport = (kind) => (kind === 'camera' ? 'mobile' : kind);

// Hazards are looked up along the first stretch of a route; a new route brings the next.
export const HAZARD_SPAN = 400000;
const BUMPS = new Set(['yes', 'bump', 'hump', 'table', 'cushion', 'dip', 'speed_bump', 'speed_hump', 'speed_table']);

function routeLine(route, span, step) {
    const end = Math.min(route.length, span);
    const pts = [];
    for (let d = 0; d < end; d += step) pts.push(along(route.coords, route.cum, d).point);
    pts.push(along(route.coords, route.cum, end).point);
    return pts.map(([lon, lat]) => `${lat.toFixed(5)},${lon.toFixed(5)}`).join(',');
}

/**
 * One Overpass query for everything mapped to warn about near the first [span] metres of [route]:
 * which kinds by [kinds] ({camera, bump, rail, school}: true to ask).
 */
export function hazardQuery(route, kinds = { camera: true }, span = HAZARD_SPAN) {
    const line = routeLine(route, span, Math.max(150, Math.min(route.length, span) / 1500));
    const parts = [];
    if (kinds.camera) parts.push(`node(around:${HAZARDS.camera.reach},${line})["highway"="speed_camera"];`);
    if (kinds.bump) parts.push(`node(around:${HAZARDS.bump.reach},${line})["traffic_calming"];`);
    if (kinds.rail) parts.push(`node(around:${HAZARDS.rail.reach},${line})["railway"="level_crossing"];`);
    const nodes = parts.length ? `(${parts.join('')});out body;` : '';
    const schools = kinds.school ? `nwr(around:${HAZARDS.school.reach},${line})["amenity"~"^(school|kindergarten)$"];out center;` : '';
    return `[out:json][timeout:25];${nodes}${schools}`;
}

function speedOf(tag) {
    const m = String(tag || '').match(/\d+/);
    return m ? Number(m[0]) : undefined;
}

/** What an Overpass element warns about, if anything. */
function kindOf(tags) {
    if (tags.highway === 'speed_camera') return 'camera';
    if (tags.railway === 'level_crossing') return 'rail';
    if (tags.traffic_calming && BUMPS.has(tags.traffic_calming)) return 'bump';
    if (tags.amenity === 'school' || tags.amenity === 'kindergarten') return 'school';
    return null;
}

/** Where [p] is along [route] from [from] metres on: {at, off}, or null past the end. */
export function placeOn(route, p, from = 0) {
    let best = null;
    for (let i = 0; i < route.coords.length - 1; i++) {
        if (route.cum[i + 1] < from) continue;
        const a = route.coords[i], b = route.coords[i + 1];
        // Cheap test first: segments far away cannot be the one.
        if (distance(p, a) > distance(a, b) + 300) continue;
        const { t, dist } = project(p, a, b);
        if (!best || dist < best.dist) best = { i, t, dist };
    }
    if (!best) return null;
    return { at: route.cum[best.i] + (route.cum[best.i + 1] - route.cum[best.i]) * best.t, off: best.dist, seg: best.i };
}

/** Hazards from an Overpass answer, placed along [route]: [{at, kind, id, point, limit?}] by distance. */
export function parseHazards(json, route) {
    const out = [];
    for (const el of (json && json.elements) || []) {
        const tags = el.tags || {};
        const kind = kindOf(tags);
        if (!kind) continue;
        const lat = el.lat ?? el.center?.lat;
        const lon = el.lon ?? el.center?.lon;
        if (lat == null || lon == null) continue;
        const p = [lon, lat];
        const spot = placeOn(route, p);
        if (!spot || spot.off > HAZARDS[kind].reach) continue;
        const h = { at: spot.at, kind, id: `${el.type}${el.id}`, point: p };
        if (kind === 'camera') h.limit = speedOf(tags.maxspeed);
        out.push(h);
    }
    out.sort((x, y) => x.at - y.at);
    // The same thing mapped twice, or a camera each way at the same spot, is one warning.
    return out.filter((h, i) => !out.slice(0, i).some((o) => o.kind === h.kind && h.at - o.at < (h.kind === 'school' ? 250 : 40)));
}

/** Speed cameras only, as earlier versions placed them. */
export function parseCameras(json, route) {
    return parseHazards(json, route).filter((h) => h.kind === 'camera');
}

/**
 * Sharp bends of the road itself, from the route's line: where it turns 65 degrees or more within
 * 90 m on a stretch driven at 50 km/h or faster, away from the manoeuvres the voice tells anyway.
 * [{at, kind: 'curve', side: 'left'|'right', id}].
 */
export function findCurves(route) {
    if (route.mode !== 'car' || route.length < 300) return [];
    const STEP = 10;
    const WINDOW = 9;
    const n = Math.floor(route.length / STEP);
    const points = [];
    for (let k = 0; k <= n; k++) points.push(along(route.coords, route.cum, k * STEP).point);
    const headings = [];
    for (let k = 0; k < points.length - 1; k++) headings.push(bearing(points[k], points[k + 1]));
    const spoken = route.steps.filter((s) => !s.quiet || s.type === 'roundabout' || s.type === 'roundabout-exit');
    const nearManeuver = (d) => spoken.some((s) => Math.abs(s.at - d) < 70);
    const fast = (d) => {
        const s = route.steps[route.stepAt(d)];
        return s.length > 0 && s.time > 0 && s.length / s.time >= 13.5;
    };
    const out = [];
    for (let k = 0; k + WINDOW < headings.length; k++) {
        let sum = 0;
        let start = -1;
        for (let j = k; j < k + WINDOW; j++) {
            const t = turn(headings[j], headings[j + 1]);
            sum += t;
            if (start < 0 && Math.abs(sum) >= 12) start = j;
        }
        if (Math.abs(sum) < 65) continue;
        const at = (start < 0 ? k : start) * STEP;
        if (at < 100 || at > route.length - 100 || nearManeuver(at) || !fast(at)) {
            k += WINDOW;
            continue;
        }
        out.push({ at, kind: 'curve', side: sum > 0 ? 'right' : 'left', id: `curve${Math.round(at)}`, point: points[Math.round(at / STEP)] });
        k += WINDOW * 2;
    }
    return out;
}

/**
 * Drivers' reports on [route]: those close to its line and, when they say which way they were
 * going, going the route's way. [{at, kind, id, point, report}].
 */
export function placeReports(reports, route, from = 0) {
    const out = [];
    for (const r of reports || []) {
        const p = [r.lon, r.lat];
        const spot = placeOn(route, p, from);
        if (!spot || spot.off > 40) continue;
        if (r.heading != null) {
            const way = bearing(route.coords[spot.seg], route.coords[spot.seg + 1]);
            if (Math.abs(turn(way, r.heading)) > 60) continue;
        }
        out.push({ at: spot.at, kind: fromReport(r.kind), id: `r${r.id}`, point: p, report: r });
    }
    return out.sort((a, b) => a.at - b.at);
}

/** All the warnings for a route in one list by distance. */
export function mergeHazards(...lists) {
    return lists.flat().sort((a, b) => a.at - b.at);
}

/** The next hazard ahead of [d] within [reach] metres, of [kinds] when given, or null. */
export function hazardAhead(hazards, d, reach, kinds = null) {
    for (const h of hazards) {
        if (h.at - d > reach) break;
        if (h.at >= d - 5 && (!kinds || kinds.has(h.kind))) return h;
    }
    return null;
}

/** The next speed camera ahead (mapped or reported), as earlier versions had it. */
export function cameraAhead(hazards, d, reach) {
    return hazardAhead(hazards, d, reach, new Set(['camera', 'mobile']));
}
