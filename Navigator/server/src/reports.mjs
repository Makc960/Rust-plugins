// What drivers tell each other about the road: a crash, roadworks, a police post, a mobile camera,
// a jam, something dangerous. A report lives for a while by its kind; drivers passing by are asked
// whether it is still there, and their answers keep it or take it off the map.

import { randomBytes } from 'node:crypto';

import { JsonFile } from './db.mjs';

const MIN = 60 * 1000;
const HOUR = 60 * MIN;

/** The kinds of report and how long each stays without anyone saying it is still there. */
export const KINDS = {
    crash: { label: 'ДТП', ttl: 3 * HOUR },
    works: { label: 'Ремонт дороги', ttl: 3 * 24 * HOUR },
    police: { label: 'Пост ДПС', ttl: 2 * HOUR },
    camera: { label: 'Камера', ttl: 7 * 24 * HOUR },
    jam: { label: 'Пробка', ttl: HOUR },
    danger: { label: 'Опасность', ttl: 6 * HOUR },
};

// The same kind reported this close to an existing one is the same thing, said again.
const SAME_PLACE = 150;
// Two drivers saying it is gone take it off the map; so does its own author.
const GONE_VOTES = 2;
const MAX_REPORTS = 50000;

export class ReportError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

function meters(a, b) {
    const r = 6371000;
    const rad = Math.PI / 180;
    const dLat = (b.lat - a.lat) * rad;
    const dLon = (b.lon - a.lon) * rad;
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) ** 2;
    return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
}

function angleBetween(a, b) {
    const d = Math.abs(a - b) % 360;
    return d > 180 ? 360 - d : d;
}

/** An anonymous id the app makes up on its first launch: letters and digits, nothing about the person. */
export function deviceOk(device) {
    return typeof device === 'string' && /^[A-Za-z0-9_-]{12,64}$/.test(device);
}

function coordinate(v, max) {
    const n = Number(v);
    if (!Number.isFinite(n) || Math.abs(n) > max) throw new ReportError(400, 'bad coordinates');
    return Math.round(n * 1e6) / 1e6;
}

export class Reports {
    constructor({ file = null, now = () => Date.now() } = {}) {
        this.store = new JsonFile(file, { reports: {} });
        this.now = now;
    }

    get all() {
        return this.store.data.reports;
    }

    /**
     * A new report at {lat, lon}, heading the way the car went when it is known. The same kind
     * close by and going the same way is the one already there: it is confirmed instead.
     */
    add({ kind, lat, lon, heading = null, device }) {
        if (!KINDS[kind]) throw new ReportError(400, 'unknown kind');
        if (!deviceOk(device)) throw new ReportError(400, 'device');
        const at = { lat: coordinate(lat, 90), lon: coordinate(lon, 180) };
        const way = heading == null || heading === '' || !Number.isFinite(Number(heading)) ? null : ((Math.round(Number(heading)) % 360) + 360) % 360;
        const now = this.now();
        this.prune();
        for (const r of Object.values(this.all)) {
            if (r.kind !== kind || meters(r, at) > SAME_PLACE) continue;
            if (way != null && r.heading != null && angleBetween(way, r.heading) > 60) continue;
            this.confirm(r, device, now);
            this.store.save();
            return { report: this.view(r), confirmed: true };
        }
        if (Object.keys(this.all).length >= MAX_REPORTS) throw new ReportError(503, 'full');
        const id = randomBytes(6).toString('base64url');
        const r = { id, kind, ...at, heading: way, created: now, confirmed: now, expires: now + KINDS[kind].ttl, author: device, up: [device], gone: [] };
        this.all[id] = r;
        this.store.save();
        return { report: this.view(r), confirmed: false };
    }

    confirm(r, device, now) {
        r.confirmed = now;
        r.expires = Math.max(r.expires, now + KINDS[r.kind].ttl);
        if (!r.up.includes(device)) r.up.push(device);
        r.gone = r.gone.filter((d) => d !== device);
    }

    /** A driver passing by answers: [still] there, or gone. */
    vote(id, { device, still }) {
        if (!deviceOk(device)) throw new ReportError(400, 'device');
        const r = this.all[id];
        const now = this.now();
        if (!r || r.expires <= now) throw new ReportError(404, 'no such report');
        if (still) {
            this.confirm(r, device, now);
        } else {
            if (!r.gone.includes(device)) r.gone.push(device);
            r.up = r.up.filter((d) => d !== device);
            if (r.gone.length >= GONE_VOTES || device === r.author) {
                delete this.all[id];
                this.store.save();
                return { removed: true };
            }
        }
        this.store.save();
        return { removed: false, report: this.view(r) };
    }

    /** Reports in the box [west, south, east, north] that are still on, the newest first. */
    inBox([w, s, e, n], limit = 500) {
        const now = this.now();
        const out = [];
        for (const r of Object.values(this.all)) {
            if (r.expires <= now) continue;
            if (r.lon < w || r.lon > e || r.lat < s || r.lat > n) continue;
            out.push(this.view(r));
        }
        return out.sort((a, b) => b.confirmed - a.confirmed).slice(0, limit);
    }

    /** What the app sees of a report: never who made it or voted. */
    view(r) {
        return { id: r.id, kind: r.kind, lat: r.lat, lon: r.lon, heading: r.heading, created: r.created, confirmed: r.confirmed, votes: r.up.length };
    }

    /** Drops what has run out. Returns how many went. */
    prune() {
        const now = this.now();
        let gone = 0;
        for (const [id, r] of Object.entries(this.all)) {
            if (r.expires <= now) {
                delete this.all[id];
                gone++;
            }
        }
        if (gone) this.store.save();
        return gone;
    }
}
