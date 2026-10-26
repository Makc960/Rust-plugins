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

    /** What a player has: {owned: [ids], vipUntil: day or null, passes: [days]}. */
    player(code) {
        return this.data.players[code] || { owned: [], vipUntil: null, passes: [] };
    }

    putPlayer(code, player) {
        this.data.players[code] = player;
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
