// The Navigator's own server: what the free map services cannot do. Behind nginx on HTTPS; it
// listens on 127.0.0.1 only. No accounts: the app sends an anonymous device id where one is needed.
//
//   GET    /api/config                                   -> {servers, message, latest}
//   POST   /api/feedback      {query, results, wanted, town, point, version}   «Не то нашлось»
//   GET    /api/reports?bbox=west,south,east,north       -> {reports, kinds}
//   POST   /api/reports       {kind, lat, lon, heading, device}    -> {report, confirmed}
//   POST   /api/reports/:id/vote   {device, still}       -> {removed, report}
//   POST   /api/trips         {dest, name, mode, pos, left, remaining, line}   -> {id, key, url}
//   PUT    /api/trips/:id     {key, pos, left, remaining, line, arrived}
//   POST   /api/trips/:id/end {key}                      (or DELETE /api/trips/:id)
//   GET    /api/trips/:id                                -> what the link shows
//   GET    /t/:id             the page a shared drive's link opens
//   GET    /privacy           the privacy policy
//   GET    /health
//
// Settings come from the environment (see deploy/navigator.env.example).

import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';

import { Feedback, FeedbackError } from './feedback.mjs';
import { privacyPage, tripPage } from './pages.mjs';
import { KINDS, ReportError, Reports } from './reports.mjs';
import { TripError, Trips } from './trips.mjs';

const MAX_BODY = 32 * 1024;

class HttpError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

/** At most [max] events per [key] in [windowMs]. */
export function limiter(max, windowMs) {
    const seen = new Map();
    return (key, now = Date.now()) => {
        const times = (seen.get(key) || []).filter((t) => now - t < windowMs);
        if (times.length >= max) {
            seen.set(key, times);
            return false;
        }
        times.push(now);
        seen.set(key, times);
        if (seen.size > 50000) seen.clear();
        return true;
    };
}

/** The servers the app should ask, from the environment: only the ones set. */
export function configFrom(env) {
    const servers = {};
    if (env.PHOTON_URL) servers.photon = env.PHOTON_URL.replace(/\/+$/, '');
    if (env.VALHALLA_URL) servers.valhalla = env.VALHALLA_URL.replace(/\/+$/, '');
    if (env.OSRM_URL) servers.osrm = env.OSRM_URL.replace(/\/+$/, '');
    if (env.NOMINATIM_URL) servers.nominatim = env.NOMINATIM_URL.replace(/\/+$/, '');
    if (env.OVERPASS_URL) servers.overpass = env.OVERPASS_URL.split(',').map((s) => s.trim()).filter(Boolean);
    return { servers, message: env.MESSAGE || '', latest: env.LATEST_VERSION || '' };
}

const BOX_SPAN = 3;

function parseBox(text) {
    const box = String(text || '').split(',').map(Number);
    if (box.length !== 4 || box.some((v) => !Number.isFinite(v))) throw new HttpError(400, 'bbox');
    const [w, s, e, n] = box;
    if (e < w || n < s || e - w > BOX_SPAN || n - s > BOX_SPAN) throw new HttpError(400, 'bbox too large');
    return box;
}

export function makeHandler({ reports, trips, feedback, config = { servers: {}, message: '', latest: '' }, publicUrl = '', contact = '', log = console }) {
    const requests = limiter(600, 60 * 1000);
    const newReports = limiter(30, 60 * 60 * 1000);
    const votes = limiter(120, 60 * 60 * 1000);
    const notes = limiter(30, 60 * 60 * 1000);
    const newTrips = limiter(20, 60 * 60 * 1000);
    const tripUpdates = limiter(40, 60 * 1000);

    return async (req, res) => {
        const url = new URL(req.url, 'http://localhost');
        // Behind the proxy on this machine, the client is the address the proxy added last.
        const local = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress);
        const ip = (local && String(req.headers['x-forwarded-for'] || '').split(',').pop().trim()) || req.socket.remoteAddress;
        const path = url.pathname;
        try {
            if (!requests(ip)) throw new HttpError(429, 'slow down');
            if (path === '/health') return text(res, 200, 'ok');
            if (req.method === 'GET' && path === '/privacy') return html(res, privacyPage({ contact }));
            const shared = path.match(/^\/t\/([A-Za-z0-9_-]{6,16})$/);
            if (req.method === 'GET' && shared) {
                const trip = trips.get(shared[1]);
                return html(res, tripPage({ id: shared[1], title: trip ? trip.dest.title : 'Поездка' }), trip ? 200 : 404);
            }
            if (!path.startsWith('/api/')) return text(res, 404, 'not found');

            if (req.method === 'GET' && path === '/api/config') return json(res, 200, config, 'max-age=600');

            if (req.method === 'POST' && path === '/api/feedback') {
                if (!notes(ip)) throw new HttpError(429, 'too many');
                feedback.add(await readJson(req));
                return json(res, 200, { ok: true });
            }

            if (path === '/api/reports') {
                if (req.method === 'GET') {
                    return json(res, 200, { reports: reports.inBox(parseBox(url.searchParams.get('bbox'))), kinds: Object.keys(KINDS) });
                }
                if (req.method === 'POST') {
                    const body = await readJson(req);
                    if (!newReports(ip) || !newReports(`d:${body.device}`)) throw new HttpError(429, 'too many reports');
                    return json(res, 200, reports.add(body));
                }
            }
            const vote = path.match(/^\/api\/reports\/([A-Za-z0-9_-]{4,16})\/vote$/);
            if (req.method === 'POST' && vote) {
                const body = await readJson(req);
                if (!votes(ip)) throw new HttpError(429, 'too many votes');
                return json(res, 200, reports.vote(vote[1], { device: body.device, still: Boolean(body.still) }));
            }

            if (req.method === 'POST' && path === '/api/trips') {
                if (!newTrips(ip)) throw new HttpError(429, 'too many trips');
                const made = trips.create(await readJson(req));
                return json(res, 200, { ...made, url: `${publicUrl}/t/${made.id}` });
            }
            const trip = path.match(/^\/api\/trips\/([A-Za-z0-9_-]{6,16})(\/end)?$/);
            if (trip) {
                const [, id, end] = trip;
                if (end) {
                    if (req.method !== 'POST') throw new HttpError(405, 'method');
                    return json(res, 200, trips.end(id, (await readJson(req)).key));
                }
                if (req.method === 'GET') {
                    const view = trips.get(id);
                    if (!view) throw new HttpError(404, 'no such trip');
                    return json(res, 200, view);
                }
                const body = await readJson(req);
                if (!tripUpdates(id)) throw new HttpError(429, 'slow down');
                if (req.method === 'PUT') return json(res, 200, trips.update(id, body.key, body));
                if (req.method === 'DELETE') return json(res, 200, trips.end(id, body.key));
            }
            throw new HttpError(404, 'not found');
        } catch (e) {
            const known = e instanceof HttpError || e instanceof ReportError || e instanceof TripError || e instanceof FeedbackError;
            const status = known ? e.status : e instanceof SyntaxError ? 400 : 500;
            if (status >= 500 && status !== 503) log.error(e);
            json(res, status, { error: status >= 500 ? 'server error' : e.message });
        }
    };
}

export function makeServer(options) {
    return createServer(makeHandler(options));
}

async function readJson(req) {
    const body = await new Promise((resolve, reject) => {
        let size = 0;
        const parts = [];
        req.on('data', (chunk) => {
            size += chunk.length;
            if (size > MAX_BODY) {
                reject(new HttpError(413, 'too large'));
                req.destroy();
            } else {
                parts.push(chunk);
            }
        });
        req.on('end', () => resolve(Buffer.concat(parts).toString('utf8')));
        req.on('error', reject);
    });
    const value = JSON.parse(body || '{}');
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new HttpError(400, 'body');
    return value;
}

function json(res, status, body, cache = 'no-store') {
    res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': cache, 'access-control-allow-origin': '*' });
    res.end(JSON.stringify(body));
}

function text(res, status, body) {
    res.writeHead(status, { 'content-type': 'text/plain; charset=utf-8' });
    res.end(body);
}

function html(res, body, status = 200) {
    res.writeHead(status, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
    res.end(body);
}

// ---- Run ----

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const env = process.env;
    const dir = env.DATA_DIR || '/var/lib/navigator';
    const reports = new Reports({ file: `${dir}/reports.json` });
    const trips = new Trips({ file: `${dir}/trips.json` });
    const feedback = new Feedback({ file: `${dir}/feedback.jsonl` });
    const port = Number(env.PORT || 8791);
    const host = env.HOST || '127.0.0.1';
    makeServer({ reports, trips, feedback, config: configFrom(env), publicUrl: (env.PUBLIC_URL || '').replace(/\/+$/, ''), contact: env.CONTACT || '' })
        .listen(port, host, () => console.log(`navigator on ${host}:${port}`));
    setInterval(() => {
        reports.prune();
        trips.prune();
    }, 5 * 60 * 1000);
}
