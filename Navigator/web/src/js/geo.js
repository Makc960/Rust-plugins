// Geometry on the Earth's surface. Points are [lon, lat] in degrees, distances in metres.

export const EARTH = 6371008.8;
const RAD = Math.PI / 180;

export function distance(a, b) {
    const dLat = (b[1] - a[1]) * RAD;
    const dLon = (b[0] - a[0]) * RAD;
    const s = Math.sin(dLat / 2) ** 2 + Math.cos(a[1] * RAD) * Math.cos(b[1] * RAD) * Math.sin(dLon / 2) ** 2;
    return 2 * EARTH * Math.asin(Math.min(1, Math.sqrt(s)));
}

/** The direction from [a] to [b], clockwise from north, 0 to 360. */
export function bearing(a, b) {
    const f1 = a[1] * RAD;
    const f2 = b[1] * RAD;
    const dl = (b[0] - a[0]) * RAD;
    const y = Math.sin(dl) * Math.cos(f2);
    const x = Math.cos(f1) * Math.sin(f2) - Math.sin(f1) * Math.cos(f2) * Math.cos(dl);
    return (Math.atan2(y, x) / RAD + 360) % 360;
}

/** How far [b] turns from [a], -180 to 180, positive clockwise. */
export function turn(a, b) {
    let d = (b - a) % 360;
    if (d > 180) d -= 360;
    if (d < -180) d += 360;
    return d;
}

/** The point [dist] metres from [p] heading [deg]. */
export function destination(p, deg, dist) {
    const d = dist / EARTH;
    const t = deg * RAD;
    const f1 = p[1] * RAD;
    const l1 = p[0] * RAD;
    const f2 = Math.asin(Math.sin(f1) * Math.cos(d) + Math.cos(f1) * Math.sin(d) * Math.cos(t));
    const l2 = l1 + Math.atan2(Math.sin(t) * Math.sin(d) * Math.cos(f1), Math.cos(d) - Math.sin(f1) * Math.sin(f2));
    return [((l2 / RAD + 540) % 360) - 180, f2 / RAD];
}

export function lerp(a, b, t) {
    return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
}

/**
 * The nearest point to [p] on the segment from [a] to [b], in a flat projection around [p]
 * that is exact enough for the few hundred metres a segment spans.
 * Returns the fraction along the segment and the distance to it.
 */
export function project(p, a, b) {
    const k = Math.cos(p[1] * RAD);
    const ax = (a[0] - p[0]) * k, ay = a[1] - p[1];
    const bx = (b[0] - p[0]) * k, by = b[1] - p[1];
    const dx = bx - ax, dy = by - ay;
    const len = dx * dx + dy * dy;
    let t = len > 0 ? -(ax * dx + ay * dy) / len : 0;
    t = Math.max(0, Math.min(1, t));
    const x = ax + dx * t, y = ay + dy * t;
    return { t, dist: Math.sqrt(x * x + y * y) * RAD * EARTH };
}

/** Running distance from the first point to each point of [line]. */
export function cumulative(line) {
    const cum = new Float64Array(line.length);
    for (let i = 1; i < line.length; i++) cum[i] = cum[i - 1] + distance(line[i - 1], line[i]);
    return cum;
}

/** The point [d] metres along [line], given its running distances [cum], and the segment it lies on. */
export function along(line, cum, d) {
    if (line.length === 1 || d <= 0) return { point: line[0], seg: 0 };
    const last = line.length - 1;
    if (d >= cum[last]) return { point: line[last], seg: last - 1 };
    let lo = 0, hi = last;
    while (hi - lo > 1) {
        const mid = (lo + hi) >> 1;
        if (cum[mid] <= d) lo = mid; else hi = mid;
    }
    const span = cum[hi] - cum[lo];
    return { point: lerp(line[lo], line[hi], span > 0 ? (d - cum[lo]) / span : 0), seg: lo };
}

/** The part of [line] from [from] to [to] metres along it. */
export function slice(line, cum, from, to) {
    const a = along(line, cum, from);
    const b = along(line, cum, to);
    const out = [a.point];
    for (let i = a.seg + 1; i <= b.seg; i++) out.push(line[i]);
    out.push(b.point);
    return out;
}

export function bounds(line) {
    let w = Infinity, s = Infinity, e = -Infinity, n = -Infinity;
    for (const [x, y] of line) {
        if (x < w) w = x;
        if (x > e) e = x;
        if (y < s) s = y;
        if (y > n) n = y;
    }
    return [[w, s], [e, n]];
}
