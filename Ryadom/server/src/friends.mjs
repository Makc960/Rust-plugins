// Accounts, friends and positions, apart from HTTP.
//
// An account is made on the phone with a name and nothing else: the phone keeps a secret token
// (the server only its hash) and the account has a friend code, XXXX-XXXX, to give to friends.
// Typing a friend's code (or opening their invite link) asks to be friends; once they accept,
// each sees where the other is. Only the last position is kept, never a history, and only friends
// both ways ever see it; anyone can hide (pause) theirs at any moment, or delete the account.

import { createHash, randomBytes, randomInt } from 'node:crypto';

import { JsonFile } from './db.mjs';

export const COLORS = ['#E5484D', '#F76B15', '#FFC53D', '#46A758', '#12A594', '#0090FF', '#3E63DD', '#8E4EC6', '#D6409F', '#7B8794'];
const CODE_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const CODE_LENGTH = 8;
export const MAX_FRIENDS = 100;
const MAX_REQUESTS = 50;
const NAME_MAX = 30;
// Control characters, zero-width ones and the marks that turn text around: not in names.
const INVISIBLE = /[\u0000-\u001f\u007f-\u009f\u200b-\u200f\u2028-\u202e\u2066-\u2069\ufeff]/g;
// A position newer than this is a fresh one; the app shows older ones as "N minutes ago".
const MIN_FIX_GAP = 2000;

export class FriendsError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

export function hashToken(token) {
    return createHash('sha256').update(String(token)).digest('hex');
}

/** A friend code as typed: dashes, spaces and case ignored; null when it cannot be one. */
export function normalCode(code) {
    const c = String(code || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
    if (c.length !== CODE_LENGTH || [...c].some((ch) => !CODE_CHARS.includes(ch))) return null;
    return c;
}

export function showCode(code) {
    return `${code.slice(0, 4)}-${code.slice(4)}`;
}

/** A name as typed: no control characters, spaces squeezed, not too long; null when empty. */
export function cleanName(name) {
    const s = String(name ?? '').replace(INVISIBLE, '').replace(/\s+/g, ' ').trim();
    return s ? [...s].slice(0, NAME_MAX).join('') : null;
}

function finite(v, lo, hi) {
    const n = Number(v);
    return Number.isFinite(n) && n >= lo && n <= hi ? n : null;
}

export class Friends {
    /**
     * [file]: accounts and friendships; [placesFile]: the last positions (written by [snapshot]).
     * Either null keeps them in memory only.
     */
    constructor({ file = null, placesFile = null, now = () => Date.now() } = {}) {
        this.store = new JsonFile(file, { users: {}, codes: {}, tokens: {}, viewers: {} });
        this.placesFile = new JsonFile(placesFile, { places: {} });
        this.places = new Map(Object.entries(this.placesFile.data.places));
        this.placesChanged = false;
        this.now = now;
    }

    get users() {
        return this.store.data.users;
    }

    // ---- Accounts ----

    /** A new account named [name]: answers {token, me}; the token is shown only this once. */
    register(name, color) {
        const clean = cleanName(name);
        if (!clean) throw new FriendsError(400, 'name');
        let id;
        do id = randomBytes(9).toString('base64url');
        while (this.users[id]);
        const token = randomBytes(32).toString('base64url');
        const user = {
            id,
            name: clean,
            color: COLORS.includes(color) ? color : COLORS[randomInt(COLORS.length)],
            code: this.freeCode(),
            sharing: true,
            created: this.now(),
            friends: [],
            incoming: [],
            outgoing: [],
        };
        this.users[id] = user;
        this.store.data.codes[user.code] = id;
        this.store.data.tokens[hashToken(token)] = id;
        this.store.save();
        return { token, me: this.me(user) };
    }

    /** The account a token belongs to, or null. */
    auth(token) {
        if (!token) return null;
        const id = this.store.data.tokens[hashToken(token)];
        return (id && this.users[id]) || null;
    }

    me(user) {
        return { id: user.id, name: user.name, color: user.color, code: showCode(user.code), sharing: user.sharing };
    }

    /**
     * A second key for [user] that only reads where their friends are: for the Navigator app to
     * show them on its map. One at a time; a new one replaces the old.
     */
    viewerToken(user) {
        this.dropViewer(user, false);
        const token = randomBytes(32).toString('base64url');
        this.store.data.viewers[hashToken(token)] = user.id;
        this.store.save();
        return token;
    }

    /** The account a read-only key belongs to, or null. */
    authViewer(token) {
        if (!token) return null;
        const id = this.store.data.viewers[hashToken(token)];
        return (id && this.users[id]) || null;
    }

    dropViewer(user, save = true) {
        for (const [hash, id] of Object.entries(this.store.data.viewers)) if (id === user.id) delete this.store.data.viewers[hash];
        if (save) this.store.save();
    }

    /** What a read-only key sees: the friends and where each is, nothing about requests or codes. */
    friendsOnly(user) {
        const { me, friends, now } = this.view(user);
        return { me: { name: me.name, color: me.color }, friends, now };
    }

    /** Changes the name, the color or whether friends see where [user] is. */
    update(user, { name, color, sharing } = {}) {
        if (name !== undefined) {
            const clean = cleanName(name);
            if (!clean) throw new FriendsError(400, 'name');
            user.name = clean;
        }
        if (color !== undefined) {
            if (!COLORS.includes(color)) throw new FriendsError(400, 'color');
            user.color = color;
        }
        if (sharing !== undefined) user.sharing = Boolean(sharing);
        this.store.save();
        return this.me(user);
    }

    /** A new friend code, so the old one (posted somewhere, say) adds nobody any more. */
    newCode(user) {
        delete this.store.data.codes[user.code];
        user.code = this.freeCode();
        this.store.data.codes[user.code] = user.id;
        this.store.save();
        return this.me(user);
    }

    /** Deletes [user] for good: the account, the friendships both ways, the position. */
    remove(user) {
        for (const other of [...user.friends, ...user.incoming, ...user.outgoing]) {
            const o = this.users[other];
            if (!o) continue;
            o.friends = o.friends.filter((x) => x !== user.id);
            o.incoming = o.incoming.filter((x) => x !== user.id);
            o.outgoing = o.outgoing.filter((x) => x !== user.id);
        }
        delete this.store.data.codes[user.code];
        for (const [hash, id] of Object.entries(this.store.data.tokens)) if (id === user.id) delete this.store.data.tokens[hash];
        this.dropViewer(user, false);
        delete this.users[user.id];
        this.places.delete(user.id);
        this.placesChanged = true;
        this.store.save();
    }

    freeCode() {
        let code;
        do code = Array.from({ length: CODE_LENGTH }, () => CODE_CHARS[randomInt(CODE_CHARS.length)]).join('');
        while (this.store.data.codes[code]);
        return code;
    }

    // ---- Friends ----

    /**
     * Asks the owner of [code] to be friends: {result, name}, the result 'requested', or 'friends'
     * when they had asked [user] already (then both agree), or 'already' when they are friends or
     * the ask is out.
     */
    add(user, code) {
        const c = normalCode(code);
        const otherId = c && this.store.data.codes[c];
        const other = otherId && this.users[otherId];
        if (!other) throw new FriendsError(404, 'no such code');
        if (other.id === user.id) throw new FriendsError(400, 'self');
        const answer = (result) => ({ result, name: other.name });
        if (user.friends.includes(other.id) || user.outgoing.includes(other.id)) return answer('already');
        if (user.incoming.includes(other.id)) {
            this.accept(user, other.id);
            return answer('friends');
        }
        if (user.friends.length >= MAX_FRIENDS || other.friends.length >= MAX_FRIENDS) throw new FriendsError(409, 'too many friends');
        if (user.outgoing.length >= MAX_REQUESTS || other.incoming.length >= MAX_REQUESTS) throw new FriendsError(409, 'too many requests');
        user.outgoing.push(other.id);
        other.incoming.push(user.id);
        this.store.save();
        return answer('requested');
    }

    accept(user, otherId) {
        const other = this.users[otherId];
        if (!other || !user.incoming.includes(otherId)) throw new FriendsError(404, 'no such request');
        user.incoming = user.incoming.filter((x) => x !== otherId);
        other.outgoing = other.outgoing.filter((x) => x !== user.id);
        if (!user.friends.includes(otherId)) user.friends.push(otherId);
        if (!other.friends.includes(user.id)) other.friends.push(user.id);
        this.store.save();
    }

    decline(user, otherId) {
        const other = this.users[otherId];
        if (!user.incoming.includes(otherId)) throw new FriendsError(404, 'no such request');
        user.incoming = user.incoming.filter((x) => x !== otherId);
        if (other) other.outgoing = other.outgoing.filter((x) => x !== user.id);
        this.store.save();
    }

    /** Ends a friendship, or takes back a request [user] sent. */
    unfriend(user, otherId) {
        const other = this.users[otherId];
        const known = user.friends.includes(otherId) || user.outgoing.includes(otherId);
        if (!known) throw new FriendsError(404, 'no such friend');
        user.friends = user.friends.filter((x) => x !== otherId);
        user.outgoing = user.outgoing.filter((x) => x !== otherId);
        if (other) {
            other.friends = other.friends.filter((x) => x !== user.id);
            other.incoming = other.incoming.filter((x) => x !== user.id);
        }
        this.store.save();
    }

    // ---- Positions ----

    /**
     * [user]'s position from the phone: {lat, lon, accuracy, speed?, heading?, battery?, charging?,
     * time?}. Answers whether it was taken (one every two seconds at most).
     */
    locate(user, fix) {
        const lat = finite(fix?.lat, -90, 90);
        const lon = finite(fix?.lon, -180, 180);
        if (lat === null || lon === null) throw new FriendsError(400, 'position');
        const now = this.now();
        const last = this.places.get(user.id);
        if (last && now - last.at < MIN_FIX_GAP) return false;
        const time = finite(fix.time, 0, now) ?? now;
        this.places.set(user.id, {
            lat: Math.round(lat * 1e6) / 1e6,
            lon: Math.round(lon * 1e6) / 1e6,
            accuracy: Math.round(finite(fix.accuracy, 0, 100000) ?? 50),
            speed: finite(fix.speed, 0, 400),
            heading: finite(fix.heading, 0, 360),
            battery: finite(fix.battery, 0, 100),
            charging: fix.charging === true,
            time: Math.max(time, now - 24 * 3600 * 1000),
            at: now,
        });
        this.placesChanged = true;
        return true;
    }

    /** What [user] sees: themselves, their friends with where each is, and the open requests. */
    view(user) {
        const person = (id) => {
            const o = this.users[id];
            return o && { id: o.id, name: o.name, color: o.color };
        };
        const friends = user.friends.map((id) => {
            const o = this.users[id];
            if (!o) return null;
            return { ...person(id), sharing: o.sharing, place: o.sharing ? this.places.get(id) || null : null };
        }).filter(Boolean);
        return {
            me: { ...this.me(user), place: this.places.get(user.id) || null },
            friends,
            incoming: user.incoming.map(person).filter(Boolean),
            outgoing: user.outgoing.map(person).filter(Boolean),
            now: this.now(),
        };
    }

    /** Writes the positions to their file when they changed. */
    snapshot() {
        if (!this.placesChanged) return;
        this.placesFile.data.places = Object.fromEntries(this.places);
        this.placesFile.save();
        this.placesChanged = false;
    }
}
