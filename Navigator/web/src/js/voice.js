// What the voice says and when: each manoeuvre is announced far off (on fast roads), near, and as
// it comes, and a turn close behind another is added with "затем".

import { cameraAhead } from './roadinfo.js';
import { capital, forDistance, forTime, inDistance, roundSpoken, stepSaid, thenSaid } from './words.js';

const STAGE = { none: 0, far: 1, near: 2, now: 3 };

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

/** How far ahead of a manoeuvre each announcement comes, at [v] metres a second. */
export function thresholds(mode, v) {
    if (mode === 'foot') return { now: 12, near: 50, far: 250, then: 30, slowest: 1.3 };
    if (mode === 'bike') {
        v = Math.max(v, 4);
        return { now: Math.max(18, v * 4), near: clamp(v * 14, 70, 220), far: 500, then: 60, slowest: 4 };
    }
    // The warnings come as the distance passes a round number, so "через километр" is a kilometre.
    // Below 58 km/h one warning is enough; faster roads get one further off too.
    const far = v >= 16 ? roundSpoken(clamp(v * 45, 900, 2000)) : 0;
    v = Math.max(v, 8);
    return { now: Math.max(30, v * 4.5), near: roundSpoken(clamp(v * 20, 250, 700)), far, then: Math.max(150, v * 7), slowest: 8 };
}

export class Announcer {
    constructor(route) {
        this.route = route;
        this.stages = new Map();
        this.lastStep = -1;
        this.arrivedSaid = false;
        this.told = new Set();
    }

    intro() {
        return `Маршрут построен. Время в пути ${forTime(this.route.duration)}.`;
    }

    /** What to say now, if anything, for [progress] from the tracker at [speed] m/s. */
    update(progress, speed) {
        const r = this.route;
        if (progress.arrived) {
            if (this.arrivedSaid) return null;
            this.arrivedSaid = true;
            const end = r.steps[r.steps.length - 1];
            const side = end.modifier === 'right' ? ' Место назначения справа.' : end.modifier === 'left' ? ' Место назначения слева.' : '';
            return `Вы приехали.${side}`;
        }
        const t = thresholds(r.mode, speed || 0);
        const j = progress.next;
        const s = r.steps[j];
        const d = progress.toNext;
        const stage = this.stages.get(j) || STAGE.none;
        const passed = this.lastStep !== progress.step;
        this.lastStep = progress.step;

        if (d <= t.now && stage < STAGE.now) {
            this.stages.set(j, STAGE.now);
            if (s.type === 'arrive') return null;
            if (s.type === 'waypoint') {
                return s.modifier === 'right' ? 'Промежуточная точка справа.' : s.modifier === 'left' ? 'Промежуточная точка слева.' : 'Промежуточная точка.';
            }
            return this.phrase(j, '', t);
        }
        if (d <= t.near && stage < STAGE.near && d > t.now + t.slowest * 2) {
            this.stages.set(j, STAGE.near);
            return this.phrase(j, inDistance(d), t);
        }
        const prev = r.steps[Math.max(0, progress.step)];
        const approach = s.at - prev.at;
        if (t.far > 0 && d <= t.far && stage < STAGE.far && approach > t.far * 1.3 && d > t.near + t.slowest * 5) {
            this.stages.set(j, STAGE.far);
            return this.phrase(j, inDistance(d), t);
        }
        // Just past a manoeuvre with a long road ahead: say how long it goes on.
        if (passed && progress.step > 0 && stage === STAGE.none && !this.told.has(j) &&
            d > Math.max(t.far, t.near) + (r.mode === 'car' ? 1500 : 400)) {
            this.told.add(j);
            return `Продолжайте движение ${forDistance(d)}.`;
        }
        return null;
    }

    phrase(j, distance, t) {
        const r = this.route;
        const s = r.steps[j];
        let text;
        if (s.type === 'arrive' || s.type === 'waypoint') {
            const side = s.modifier === 'right' ? ', справа' : s.modifier === 'left' ? ', слева' : '';
            text = `${distance} ${s.type === 'arrive' ? 'место назначения' : 'промежуточная точка'}${side}`;
        } else {
            text = distance ? `${distance} ${stepSaid(s)}` : stepSaid(s);
            const k = r.nextSpoken(j);
            const next = r.steps[k];
            if (k !== j && next.at - s.at <= t.then) {
                const then = thenSaid(next);
                if (then) {
                    text += `, затем ${then}`;
                    if ((this.stages.get(k) || 0) < STAGE.near) this.stages.set(k, STAGE.near);
                }
            }
        }
        return `${capital(text.trim())}.`;
    }
}

/**
 * Warnings about the road rather than the route: a speed camera ahead, with its limit, and a word
 * to slow down when going faster than [tolerance] km/h over it.
 */
export class RoadAlerts {
    constructor() {
        this.told = new Set();
    }

    update(progress, speed, cameras, limit, tolerance = 10) {
        if (!cameras || !cameras.length) return null;
        const v = Math.max(speed || 0, 8);
        const camera = cameraAhead(cameras, progress.along, clamp(v * 18, 250, 500));
        if (!camera || this.told.has(camera.id)) return null;
        this.told.add(camera.id);
        const max = camera.limit ?? limit;
        let text = 'Впереди камера';
        if (max) text += `, ограничение ${max}`;
        if (max && speed != null && speed * 3.6 > max + tolerance) text += '. Сбавьте скорость';
        return `${text}.`;
    }
}
