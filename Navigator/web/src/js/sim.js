// A drive along a route without moving: fixes once a second at each stretch's own pace,
// slowing down for the turns, so the whole app can be tried at home.

import { along, bearing } from './geo.js';

const SLOWEST = { car: 5, bike: 3, foot: 1.2 };

export class Simulator {
    constructor(route, { speedup = 1 } = {}) {
        this.route = route;
        this.speedup = speedup;
        this.d = 0;
        this.timer = null;
        this.paces = route.steps.map((s) => (s.time > 0 ? s.length / s.time : 10));
    }

    /** The speed to drive [d] metres along: the stretch's pace, slower close to a manoeuvre. */
    speedAt(d) {
        const r = this.route;
        const i = r.stepAt(d);
        let v = this.paces[i];
        const next = r.steps[i + 1];
        if (next && !next.quiet) {
            const left = next.at - d;
            const slow = next.type === 'turn' || next.type === 'roundabout' ? 0.35 : 0.7;
            if (left < 120) v *= slow + (1 - slow) * (left / 120);
        }
        return Math.max(SLOWEST[r.mode] || 5, v);
    }

    fixAt(d, speed, time) {
        const r = this.route;
        const here = along(r.coords, r.cum, d);
        const ahead = along(r.coords, r.cum, Math.min(r.length, d + 8)).point;
        const heading = d < r.length ? bearing(here.point, ahead) : null;
        return { lon: here.point[0], lat: here.point[1], accuracy: 5, speed, heading, time, simulated: true };
    }

    start(onFix, interval = 1000) {
        this.stop();
        let time = Date.now();
        const tick = () => {
            const v = this.speedAt(this.d);
            time += interval;
            this.d = Math.min(this.route.length, this.d + v * this.speedup * (interval / 1000));
            onFix(this.fixAt(this.d, v, time));
            if (this.d >= this.route.length) this.stop();
        };
        onFix(this.fixAt(this.d, 0, time));
        this.timer = setInterval(tick, interval);
    }

    stop() {
        if (this.timer) clearInterval(this.timer);
        this.timer = null;
    }

    get running() {
        return this.timer != null;
    }
}
