// The shop's records in one JSON file: orders, and what each player (by their purchase code) has.
// Small enough to keep in memory; every change is written to a temporary file and renamed over
// the old one, so a crash never leaves half a file.

import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

export class Db {
    constructor(path) {
        this.path = path;
        this.data = { orders: {}, players: {} };
        if (path) {
            try {
                this.data = { ...this.data, ...JSON.parse(readFileSync(path, 'utf8')) };
            } catch (e) {
                if (e.code !== 'ENOENT') throw e;
            }
        }
    }

    order(id) {
        return this.data.orders[id] || null;
    }

    putOrder(order) {
        this.data.orders[order.id] = order;
        this.save();
    }

    allOrders() {
        return Object.values(this.data.orders);
    }

    /** The orders made under [code] or under a code moved into it. */
    ordersOf(code) {
        const player = this.resolve(code);
        return Object.values(this.data.orders).filter((o) => this.resolve(o.player) === player);
    }

    /** The code [code]'s purchases are kept under now: a code moved to another follows it. */
    resolve(code) {
        let c = code;
        for (let i = 0; i < 100 && this.data.players[c]?.movedTo; i++) c = this.data.players[c].movedTo;
        return c;
    }

    /** What a player has: {owned: [ids], vipUntil: day or null, passes: [days]}. */
    player(code) {
        const p = this.data.players[this.resolve(code)];
        return p && !p.movedTo ? p : { owned: [], vipUntil: null, passes: [] };
    }

    putPlayer(code, player) {
        this.data.players[this.resolve(code)] = player;
        this.save();
    }

    /** From now on [from] means [to] (both already resolved): its purchases are to's. */
    alias(from, to) {
        this.data.players[from] = { movedTo: to };
        this.save();
    }

    save() {
        if (!this.path) return;
        mkdirSync(dirname(this.path), { recursive: true });
        const tmp = `${this.path}.tmp`;
        writeFileSync(tmp, JSON.stringify(this.data), { mode: 0o600 });
        renameSync(tmp, this.path);
    }
}
