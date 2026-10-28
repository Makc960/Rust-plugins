// A drive shared with someone: a link that shows where the car is going, where it is now and when
// it gets there, kept up to date by the app while it drives. It ends at the arrival, or when the
// driver stops sharing, and is forgotten soon after.

import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';

import { JsonFile } from './db.mjs';

const MIN = 60 * 1000;
const HOUR = 60 * MIN;
// A drive's link lives this long at most; after its end, a little while to see that it ended.
const LIFE = 24 * HOUR;
const AFTER_END = 30 * MIN;
const MAX_TRIPS = 20000;
const MAX_LINE = 600;

export class TripError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

const hash = (key) => createHash('sha256').update(key).digest('hex');

function point(v) {
    if (!v || typeof v !== 'object') return null;
    const lat = Number(v.lat);
    const lon = Number(v.lon);
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
    return { lat: Math.round(lat * 1e5) / 1e5, lon: Math.round(lon * 1e5) / 1e5 };
}

function line(v) {
    if (!Array.isArray(v)) return undefined;
    const out = [];
    for (const p of v.slice(0, MAX_LINE)) {
        if (!Array.isArray(p) || p.length < 2) continue;
        const [lon, lat] = p.map(Number);
        if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
        out.push([Math.round(lon * 1e5) / 1e5, Math.round(lat * 1e5) / 1e5]);
    }
    return out;
}

const text = (v, max) => String(v ?? '').replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, max);
const seconds = (v) => (Number.isFinite(Number(v)) && Number(v) >= 0 ? Math.min(Math.round(Number(v)), 7 * 24 * 3600) : null);
const metersLeft = (v) => (Number.isFinite(Number(v)) && Number(v) >= 0 ? Math.min(Math.round(Number(v)), 2e7) : null);

export class Trips {
    constructor({ file = null, now = () => Date.now() } = {}) {
        this.store = new JsonFile(file, { trips: {} });
        this.now = now;
    }

    get all() {
        return this.store.data.trips;
    }

    /** A new shared drive: {dest: {title, lat, lon}, name?, ...state}. Returns its id and the key to update it. */
    create(body) {
        const dest = point(body.dest);
        if (!dest) throw new TripError(400, 'destination');
        this.prune();
        if (Object.keys(this.all).length >= MAX_TRIPS) throw new TripError(503, 'full');
        const id = randomBytes(6).toString('base64url');
        const key = randomBytes(18).toString('base64url');
        const now = this.now();
        const trip = {
            id, key: hash(key), created: now, updated: now, ended: null, arrived: false,
            name: text(body.name, 40), dest: { ...dest, title: text(body.dest.title, 120) || 'Точка на карте' },
            mode: ['car', 'bike', 'foot'].includes(body.mode) ? body.mode : 'car',
        };
        this.apply(trip, body);
        this.all[id] = trip;
        this.store.save();
        return { id, key };
    }

    /** The app's news about a drive: where it is, time and distance left, the road ahead. */
    update(id, key, body) {
        const trip = this.owned(id, key);
        if (trip.ended) throw new TripError(410, 'ended');
        this.apply(trip, body);
        trip.updated = this.now();
        if (body.arrived) {
            trip.arrived = true;
            trip.ended = trip.updated;
        }
        this.store.save();
        return this.view(trip);
    }

    end(id, key) {
        const trip = this.owned(id, key);
        if (!trip.ended) trip.ended = this.now();
        this.store.save();
        return this.view(trip);
    }

    /** What anyone with the link sees, or null when there is no such drive (any more). */
    get(id) {
        const trip = this.all[id];
        if (!trip || this.expired(trip)) return null;
        return this.view(trip);
    }

    apply(trip, body) {
        const at = point(body.pos);
        if (at) trip.pos = at;
        const left = seconds(body.left);
        if (left != null) trip.left = left;
        const remaining = metersLeft(body.remaining);
        if (remaining != null) trip.remaining = remaining;
        const road = line(body.line);
        if (road) trip.line = road;
        if (Number.isFinite(Number(body.speed))) trip.speed = Math.max(0, Math.min(Math.round(Number(body.speed)), 400));
    }

    owned(id, key) {
        const trip = this.all[id];
        if (!trip || this.expired(trip)) throw new TripError(404, 'no such trip');
        const a = Buffer.from(trip.key, 'hex');
        const b = Buffer.from(hash(String(key || '')), 'hex');
        if (a.length !== b.length || !timingSafeEqual(a, b)) throw new TripError(403, 'not yours');
        return trip;
    }

    expired(trip) {
        const now = this.now();
        return now - trip.created > LIFE || (trip.ended && now - trip.ended > AFTER_END);
    }

    view(trip) {
        const eta = trip.left != null ? trip.updated + trip.left * 1000 : null;
        return {
            id: trip.id, name: trip.name, dest: trip.dest, mode: trip.mode,
            pos: trip.pos || null, left: trip.left ?? null, remaining: trip.remaining ?? null, eta,
            speed: trip.speed ?? null, line: trip.line || [], updated: trip.updated, ended: trip.ended, arrived: trip.arrived,
            now: this.now(),
        };
    }

    prune() {
        let gone = 0;
        for (const [id, trip] of Object.entries(this.all)) {
            if (this.expired(trip)) {
                delete this.all[id];
                gone++;
            }
        }
        if (gone) this.store.save();
        return gone;
    }
}
