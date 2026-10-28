// The map without the internet: which tiles cover a town, a region or a route, so the phone can
// fetch them beforehand (the Android app keeps every tile it loads; see TileCache.kt). Only the
// scheme is fetched ahead: satellite pictures are too heavy, and Esri does not allow it.

import { along } from './geo.js';

// OpenFreeMap's tiles stop at 14; nearer, the map draws them larger.
export const MAX_ZOOM = 14;

export function tileXY(lon, lat, z) {
    const n = 2 ** z;
    const x = Math.floor(((lon + 180) / 360) * n);
    const rad = (Math.max(-85.0511, Math.min(85.0511, lat)) * Math.PI) / 180;
    const y = Math.floor(((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2) * n);
    return [Math.max(0, Math.min(n - 1, x)), Math.max(0, Math.min(n - 1, y))];
}

/** Every tile over [bbox] ([west, south, east, north]) from zoom [zmin] to [zmax]: [{z, x, y}]. */
export function tilesInBox(bbox, zmin = 0, zmax = MAX_ZOOM) {
    const [w, s, e, n] = bbox;
    const out = [];
    for (let z = zmin; z <= zmax; z++) {
        const [x0, y0] = tileXY(w, n, z);
        const [x1, y1] = tileXY(e, s, z);
        for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) out.push({ z, x, y });
    }
    return out;
}

/** How many tiles [tilesInBox] would give, without making them. */
export function countInBox(bbox, zmin = 0, zmax = MAX_ZOOM) {
    const [w, s, e, n] = bbox;
    let count = 0;
    for (let z = zmin; z <= zmax; z++) {
        const [x0, y0] = tileXY(w, n, z);
        const [x1, y1] = tileXY(e, s, z);
        count += (x1 - x0 + 1) * (y1 - y0 + 1);
    }
    return count;
}

/**
 * The tiles a drive along [route] passes over at each of [zooms], with [buffer] tiles either side
 * at the nearest zoom, so a turn off the road still has its map.
 */
export function tilesAlong(route, zooms = [10, 12, 14], buffer = 1) {
    const seen = new Set();
    const out = [];
    const add = (z, x, y) => {
        const n = 2 ** z;
        if (x < 0 || y < 0 || x >= n || y >= n) return;
        const key = `${z}/${x}/${y}`;
        if (seen.has(key)) return;
        seen.add(key);
        out.push({ z, x, y });
    };
    const top = Math.max(...zooms);
    for (const z of zooms) {
        // A point every quarter of a tile, so no tile the line crosses is missed.
        const tileMeters = 40075016 / 2 ** z;
        const step = Math.max(30, tileMeters / 4);
        const b = z === top ? buffer : 0;
        for (let d = 0; d <= route.length + step; d += step) {
            const [lon, lat] = along(route.coords, route.cum, Math.min(d, route.length)).point;
            const [x, y] = tileXY(lon, lat, z);
            for (let dx = -b; dx <= b; dx++) for (let dy = -b; dy <= b; dy++) add(z, x + dx, y + dy);
        }
    }
    return out;
}

/** The tiles' addresses, from the map source's own pattern (…/{z}/{x}/{y}.pbf). */
export function tileUrls(template, tiles) {
    return tiles.map(({ z, x, y }) => template.replace('{z}', z).replace('{x}', x).replace('{y}', y));
}

// What a vector tile weighs on average, by zoom, in a Russian town and around it (KB).
const WEIGHT = { 14: 55, 13: 70, 12: 80, 11: 90, 10: 100 };

/** About how many megabytes [tiles] (or a count by zoom, {z: n}) come to. */
export function estimateMb(tiles) {
    let kb = 0;
    if (Array.isArray(tiles)) for (const t of tiles) kb += WEIGHT[t.z] || 100;
    else for (const [z, n] of Object.entries(tiles)) kb += (WEIGHT[z] || 100) * n;
    return Math.max(1, Math.round(kb / 1024));
}

/** Counts by zoom for [bbox], for [estimateMb]. */
export function countByZoom(bbox, zmin = 0, zmax = MAX_ZOOM) {
    const out = {};
    for (let z = zmin; z <= zmax; z++) out[z] = countInBox(bbox, z, z);
    return out;
}

// The letters the map's names need: Latin and Cyrillic, digits and punctuation.
const GLYPH_RANGES = ['0-255', '256-511', '1024-1279', '8192-8447'];
const FONTS = ['Noto Sans Regular', 'Noto Sans Bold', 'Noto Sans Italic'];

/** The style's own files the map needs offline besides the tiles: names' letters and icons. */
export function styleUrls(glyphs, sprite) {
    const out = [];
    if (glyphs) {
        for (const font of FONTS) {
            for (const range of GLYPH_RANGES) out.push(glyphs.replace('{fontstack}', encodeURIComponent(font)).replace('{range}', range));
        }
    }
    if (sprite) for (const end of ['.json', '.png', '@2x.json', '@2x.png']) out.push(`${sprite}${end}`);
    return out;
}
