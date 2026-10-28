// Follows a position along a route: where on the line it is, what is left, and whether it has
// left the route or arrived.
//
// A fix is {lon, lat, accuracy (m), speed (m/s, or null), heading (deg, or null), time (ms)}.

import { along, bearing, lerp, project, turn } from './geo.js';

const LIMITS = {
    car: { off: 40, arrive: 30, ahead: 400, wrongWay: 7 },
    bike: { off: 35, arrive: 20, ahead: 200, wrongWay: 5 },
    foot: { off: 30, arrive: 15, ahead: 120, wrongWay: Infinity },
};

export class Tracker {
    constructor(route) {
        this.route = route;
        this.limits = LIMITS[route.mode] || LIMITS.car;
        this.along = null;     // metres along the route
        this.seg = 0;          // the segment of the line the position is on
        this.away = 0;         // fixes in a row off the route
        this.awaySince = 0;
        this.backward = 0;     // fixes in a row driving against the route
        this.joined = false;   // has been on the line at least once
        this.firstOffset = null;
        this.arrived = false;
    }

    /** Where on the route [fix] is. Returns the progress, or null if it cannot be placed. */
    update(fix) {
        const r = this.route;
        const p = [fix.lon, fix.lat];
        const moving = fix.speed != null && fix.speed > 2.5 && fix.heading != null;
        const acc = Math.min(fix.accuracy || 10, 100);

        // The stretch of line to search: from a little behind to where the position could be by now.
        let lo = 0, hi = r.coords.length - 2;
        if (this.along != null) {
            // After a gap with no fixes (a tunnel), the car may be far ahead of where it was last seen.
            const gap = this.lastTime ? Math.max(0, (fix.time - this.lastTime) / 1000) : 0;
            const pace = Math.max(fix.speed || 0, this.lastSpeed || 0);
            const reach = this.limits.ahead + pace * (15 + Math.min(gap, 300)) + acc * 2;
            while (lo < this.seg && r.cum[lo + 1] < this.along - 60) lo++;
            hi = this.seg;
            while (hi < r.coords.length - 2 && r.cum[hi] < this.along + reach) hi++;
        }
        let best = null;
        for (let i = lo; i <= hi; i++) {
            const a = r.coords[i], b = r.coords[i + 1];
            const { t, dist } = project(p, a, b);
            let score = dist;
            const segBearing = bearing(a, b);
            if (moving) {
                const off = Math.abs(turn(segBearing, fix.heading));
                if (off > 110) score += 45; else if (off > 60) score += 15;
            }
            // Earlier stretches of a line that comes back on itself lose to the one ahead.
            if (this.along != null && r.cum[i + 1] < this.along - 20) score += 25;
            if (!best || score < best.score) best = { i, t, dist, score, segBearing };
        }
        if (!best) return null;
        this.lastTime = fix.time;
        if (fix.speed != null) this.lastSpeed = fix.speed;

        const along = r.cum[best.i] + (r.cum[best.i + 1] - r.cum[best.i]) * best.t;
        const offset = best.dist;
        const threshold = Math.max(this.limits.off, acc * 1.2 + 15);
        if (this.firstOffset == null) this.firstOffset = offset;
        if (offset <= threshold) this.joined = true;

        // Off the route: away for two fixes and three seconds, or driving the wrong way along it.
        // Before first reaching the line (starting in a yard or a car park), only moving further away counts.
        const away = this.joined ? offset > threshold : offset > Math.max(threshold, this.firstOffset + 60);
        if (away) {
            if (this.away === 0) this.awaySince = fix.time;
            this.away++;
        } else {
            this.away = 0;
        }
        const against = moving && Math.abs(turn(best.segBearing, fix.heading)) > 150 && offset < threshold;
        this.backward = against ? this.backward + 1 : 0;
        const offRoute = (this.away >= 2 && fix.time - this.awaySince >= 3000) || this.backward >= this.limits.wrongWay;

        if (!away) {
            this.seg = best.i;
            this.along = this.along == null ? along : Math.max(along, this.along - 30);
        }
        const d = this.along ?? along;
        if (!this.arrived && this.joined && (Math.max(0, r.length - d) <= this.limits.arrive ||
            (best.i === r.coords.length - 2 && best.t >= 1 && offset < threshold * 1.5))) {
            this.arrived = true;
        }
        return {
            ...this.progressAt(d),
            point: lerp(r.coords[best.i], r.coords[best.i + 1], best.t),
            offset,
            heading: best.segBearing,
            offRoute: offRoute && !this.arrived,
            arrived: this.arrived,
        };
    }

    /** What is left and what comes next from [d] metres along. */
    progressAt(d) {
        const r = this.route;
        const step = r.stepAt(d);
        const next = r.nextSpoken(step);
        return {
            along: d,
            step,
            next,
            toNext: Math.max(0, r.steps[next].at - d),
            remaining: Math.max(0, r.length - d),
            timeLeft: r.timeLeft(d),
        };
    }

    /**
     * Where the car most likely is with no fix (in a tunnel): [metres] further along than the last
     * place it was seen. Nothing is kept, so the next real fix finds it wherever it really is.
     */
    predict(metres) {
        const r = this.route;
        if (this.along == null) return null;
        const d = Math.min(r.length, this.along + metres);
        const here = along(r.coords, r.cum, d);
        const a = r.coords[here.seg], b = r.coords[here.seg + 1];
        return {
            ...this.progressAt(d),
            point: here.point,
            offset: 0,
            heading: bearing(a, b),
            offRoute: false,
            arrived: this.arrived,
            predicted: true,
        };
    }
}
