// What the road itself says along a route: speed limits (from Valhalla's trace_attributes) and
// speed cameras (from OpenStreetMap through Overpass), placed in metres along the route.

import { along, cumulative, distance, project } from './geo.js';
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

// ---- Cameras ----

// How far a camera may be from the route's line and still watch it.
const CAMERA_REACH = 35;
// Cameras are looked up along the first stretch of a route; a new route brings the next.
export const CAMERA_SPAN = 400000;

/** An Overpass query for speed cameras within a few metres of the first [span] metres of [route]. */
export function cameraQuery(route, span = CAMERA_SPAN) {
    const end = Math.min(route.length, span);
    const step = Math.max(150, end / 1500);
    const pts = [];
    for (let d = 0; d < end; d += step) pts.push(along(route.coords, route.cum, d).point);
    pts.push(along(route.coords, route.cum, end).point);
    const line = pts.map(([lon, lat]) => `${lat.toFixed(5)},${lon.toFixed(5)}`).join(',');
    return `[out:json][timeout:25];node(around:${CAMERA_REACH},${line})["highway"="speed_camera"];out body;`;
}

function speedOf(tag) {
    const m = String(tag || '').match(/\d+/);
    return m ? Number(m[0]) : undefined;
}

/** Cameras from an Overpass answer, placed along [route]: [{at, limit, id}] by distance. */
export function parseCameras(json, route) {
    const out = [];
    for (const el of (json && json.elements) || []) {
        if (el.type !== 'node' || el.lat == null) continue;
        const p = [el.lon, el.lat];
        let best = null;
        for (let i = 0; i < route.coords.length - 1; i++) {
            const a = route.coords[i], b = route.coords[i + 1];
            // Cheap test first: segments far away cannot be the one.
            if (distance(p, a) > distance(a, b) + 200) continue;
            const { t, dist } = project(p, a, b);
            if (!best || dist < best.dist) best = { i, t, dist };
        }
        if (!best || best.dist > CAMERA_REACH) continue;
        const at = route.cum[best.i] + (route.cum[best.i + 1] - route.cum[best.i]) * best.t;
        const tags = el.tags || {};
        out.push({ at, limit: speedOf(tags.maxspeed), id: el.id });
    }
    out.sort((x, y) => x.at - y.at);
    // A camera mapped twice, or one each way at the same spot, is one warning.
    return out.filter((c, i) => i === 0 || c.at - out[i - 1].at > 40);
}

/** The next camera ahead of [d] within [reach] metres, or null. */
export function cameraAhead(cameras, d, reach) {
    for (const c of cameras) {
        if (c.at >= d - 5 && c.at - d <= reach) return c;
        if (c.at - d > reach) break;
    }
    return null;
}
