// A JSON file kept in memory and written whole after every change: to a temporary file first,
// then renamed over the old one, so a crash never leaves half a file. Enough for the accounts and
// friendships of a few thousand people; positions, which change all the time, go to their own
// file now and then (see Friends.snapshot).

import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

export class JsonFile {
    constructor(path, empty) {
        this.path = path;
        this.data = structuredClone(empty);
        if (path) {
            try {
                this.data = { ...this.data, ...JSON.parse(readFileSync(path, 'utf8')) };
            } catch (e) {
                if (e.code !== 'ENOENT') throw e;
            }
        }
    }

    save() {
        if (!this.path) return;
        mkdirSync(dirname(this.path), { recursive: true });
        const tmp = `${this.path}.tmp`;
        writeFileSync(tmp, JSON.stringify(this.data), { mode: 0o600 });
        renameSync(tmp, this.path);
    }
}
