// Distances and shapes on the globe. Points are [lon, lat].

const R = 6371008.8;
const rad = (d) => (d * Math.PI) / 180;

/** Meters between two points. */
export function meters(a, b) {
    const dLat = rad(b[1] - a[1]);
    const dLon = rad(b[0] - a[0]);
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a[1])) * Math.cos(rad(b[1])) * Math.sin(dLon / 2) ** 2;
    return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** A circle of [radius] meters around [center], as a GeoJSON polygon ring. */
export function circle(center, radius, steps = 48) {
    const ring = [];
    const dLat = radius / R;
    const dLon = radius / (R * Math.cos(rad(center[1])));
    for (let i = 0; i <= steps; i++) {
        const t = (i / steps) * 2 * Math.PI;
        ring.push([center[0] + (dLon * 180 / Math.PI) * Math.cos(t), center[1] + (dLat * 180 / Math.PI) * Math.sin(t)]);
    }
    return ring;
}

/** [[west, south], [east, north]] around [points], or null for none. */
export function bounds(points) {
    if (!points.length) return null;
    let [w, s] = points[0];
    let [e, n] = points[0];
    for (const [x, y] of points) {
        w = Math.min(w, x);
        e = Math.max(e, x);
        s = Math.min(s, y);
        n = Math.max(n, y);
    }
    return [[w, s], [e, n]];
}
