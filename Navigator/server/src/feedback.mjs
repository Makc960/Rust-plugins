// «Не то нашлось»: what a person searched for, where, what the app found and what they wanted
// instead. One JSON line each, for the author to read and make the search better; the place is
// kept roughly (to about a hundred metres), nothing says who sent it.

import { appendFileSync, existsSync, mkdirSync, readFileSync, renameSync, statSync } from 'node:fs';
import { dirname } from 'node:path';

const MAX_FILE = 20 * 1024 * 1024;

const text = (v, max) => String(v ?? '').replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, max);

export class FeedbackError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

/** The note as kept: only the fields the app sends, cut to size. */
export function cleanFeedback(body, now = Date.now()) {
    const query = text(body.query, 200);
    if (!query) throw new FeedbackError(400, 'query');
    const out = { at: new Date(now).toISOString(), query, wanted: text(body.wanted, 300) };
    if (body.town) out.town = text(body.town, 80);
    const lat = Number(body.point?.lat);
    const lon = Number(body.point?.lon);
    if (Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
        out.point = { lat: Math.round(lat * 1000) / 1000, lon: Math.round(lon * 1000) / 1000 };
    }
    if (Array.isArray(body.results)) out.results = body.results.slice(0, 10).map((r) => text(r, 160)).filter(Boolean);
    if (body.scope) out.scope = text(body.scope, 20);
    if (body.version) out.version = text(body.version, 20);
    return out;
}

export class Feedback {
    constructor({ file = null } = {}) {
        this.file = file;
        this.memory = [];
    }

    add(body) {
        const note = cleanFeedback(body);
        if (!this.file) {
            this.memory.push(note);
            return note;
        }
        mkdirSync(dirname(this.file), { recursive: true });
        // One old file is kept beside the current one.
        if (existsSync(this.file) && statSync(this.file).size > MAX_FILE) renameSync(this.file, `${this.file}.1`);
        appendFileSync(this.file, `${JSON.stringify(note)}\n`, { mode: 0o600 });
        return note;
    }

    /** The last [n] notes, the newest last. */
    recent(n = 30) {
        if (!this.file) return this.memory.slice(-n);
        try {
            const lines = readFileSync(this.file, 'utf8').trim().split('\n').filter(Boolean);
            return lines.slice(-n).map((l) => JSON.parse(l));
        } catch (e) {
            if (e.code === 'ENOENT') return [];
            throw e;
        }
    }
}
