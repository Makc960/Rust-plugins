// A route as the app uses it, whichever server built it: the line, and the manoeuvres along it.
//
// A step is a manoeuvre at a point of the line and the stretch that follows it to the next one:
//   type      depart, arrive, waypoint (a stop on the way), turn, continue, namechange, ramp,
//             exit, fork, merge, roundabout, roundabout-exit, ferry, ferry-exit, other
//   modifier  uturn, sharp-right, right, slight-right, straight, slight-left, left, sharp-left
//             (for arrive and waypoint: the side the place is on)
//   exit      which exit to take at a roundabout
//   street    the name of the road after the manoeuvre; toward: where the signs point
//   at        metres from the start to the manoeuvre; length and time: of the stretch after it
//   lanes     [{turns: [modifier...], use: true when the lane leads on}], when known

import { bounds, cumulative, distance } from './geo.js';
import { decode } from './polyline.js';

// Steps that need no word from the driver: the road just goes on or changes its name.
const QUIET = new Set(['continue', 'namechange', 'roundabout-exit', 'ferry-exit', 'other']);

export class Route {
    constructor({ coords, steps, duration, mode, provider, toll = false, ferry = false, highway = false }) {
        this.coords = coords;
        this.cum = cumulative(coords);
        this.length = this.cum[this.cum.length - 1];
        this.mode = mode;
        this.provider = provider;
        this.toll = toll;
        this.ferry = ferry;
        this.highway = highway;
        this.bbox = bounds(coords);
        this.steps = steps.map((s, i) => ({
            ...s,
            at: this.cum[Math.min(s.index, coords.length - 1)],
            quiet: QUIET.has(s.type) || (s.type === 'turn' && s.modifier === 'straight' && i > 0),
        }));
        // Lengths and times from the server are per stretch; the line's own measure keeps them consistent.
        for (let i = 0; i < this.steps.length; i++) {
            const next = this.steps[i + 1];
            this.steps[i].length = (next ? next.at : this.length) - this.steps[i].at;
        }
        this.duration = duration ?? this.steps.reduce((t, s) => t + (s.time || 0), 0);
        this.id = `${provider}:${Math.round(this.length)}:${Math.round(this.duration)}:${coords.length}`;
        this.stops = this.steps.filter((s) => s.type === 'waypoint');
    }

    /** Seconds left from [d] metres along, by each stretch's own pace. */
    timeLeft(d) {
        let t = 0;
        for (let i = this.steps.length - 1; i >= 0; i--) {
            const s = this.steps[i];
            const end = s.at + s.length;
            if (end <= d) break;
            const part = s.length > 0 ? (end - Math.max(d, s.at)) / s.length : 0;
            t += (s.time || 0) * Math.min(1, part);
        }
        return t;
    }

    /** The index of the step being driven [d] metres along. */
    stepAt(d) {
        let i = 0;
        while (i + 1 < this.steps.length && this.steps[i + 1].at <= d) i++;
        return i;
    }

    /** The next step after [i] that needs a word to the driver, or the arrival. */
    nextSpoken(i) {
        for (let j = i + 1; j < this.steps.length; j++) {
            if (!this.steps[j].quiet) return j;
        }
        return this.steps.length - 1;
    }

    /** The two or three longest roads of the route, for telling routes apart. */
    via(count = 2) {
        const byName = new Map();
        for (const s of this.steps) {
            if (!s.street || s.type === 'arrive') continue;
            byName.set(s.street, (byName.get(s.street) || 0) + s.length);
        }
        return [...byName.entries()].sort((a, b) => b[1] - a[1]).slice(0, count).map(([name]) => name);
    }
}

// ---- Valhalla ----

const VALHALLA_TYPES = {
    1: ['depart'], 2: ['depart', 'right'], 3: ['depart', 'left'],
    4: ['arrive', 'straight'], 5: ['arrive', 'right'], 6: ['arrive', 'left'],
    7: ['namechange'], 8: ['continue', 'straight'],
    9: ['turn', 'slight-right'], 10: ['turn', 'right'], 11: ['turn', 'sharp-right'],
    12: ['turn', 'uturn'], 13: ['turn', 'uturn'],
    14: ['turn', 'sharp-left'], 15: ['turn', 'left'], 16: ['turn', 'slight-left'],
    17: ['ramp', 'straight'], 18: ['ramp', 'right'], 19: ['ramp', 'left'],
    20: ['exit', 'right'], 21: ['exit', 'left'],
    22: ['fork', 'straight'], 23: ['fork', 'right'], 24: ['fork', 'left'],
    25: ['merge', 'straight'], 37: ['merge', 'right'], 38: ['merge', 'left'],
    26: ['roundabout'], 27: ['roundabout-exit'],
    28: ['ferry'], 29: ['ferry-exit'],
};

// Lane directions as Valhalla's bit masks.
const LANE_BITS = [
    [2, 'straight'], [4, 'sharp-left'], [8, 'left'], [16, 'slight-left'],
    [32, 'slight-right'], [64, 'right'], [128, 'sharp-right'], [256, 'uturn'],
];

function valhallaLanes(lanes) {
    if (!Array.isArray(lanes) || lanes.length === 0) return undefined;
    return lanes.map((l) => {
        const turns = LANE_BITS.filter(([bit]) => l.directions & bit).map(([, name]) => name);
        return { turns: turns.length ? turns : ['straight'], use: Boolean(l.active || l.valid) };
    });
}

function signText(sign) {
    if (!sign) return undefined;
    const pick = (list) => (Array.isArray(list) ? list.map((e) => e.text).filter(Boolean) : []);
    const toward = [...pick(sign.exit_toward_elements), ...pick(sign.exit_branch_elements)];
    return toward.length ? toward.slice(0, 2).join(', ') : undefined;
}

function valhallaStep(m, offset) {
    const [type, modifier] = VALHALLA_TYPES[m.type] || ['other'];
    const step = {
        type,
        modifier: modifier || (m.type === 26 ? undefined : 'straight'),
        index: m.begin_shape_index + offset,
        street: (m.street_names && m.street_names[0]) || (m.begin_street_names && m.begin_street_names[0]) || '',
        time: m.time || 0,
        toll: Boolean(m.toll),
    };
    if (m.type === 12) step.side = 'right';
    if (m.type === 13) step.side = 'left';
    if (type === 'roundabout') step.exit = m.roundabout_exit_count || undefined;
    const toward = signText(m.sign);
    if (toward) step.toward = toward;
    const lanes = valhallaLanes(m.lanes);
    if (lanes) step.lanes = lanes;
    return step;
}

/**
 * Legs joined into one line. Where one leg ends and the next begins, the arrival and the new
 * departure become one step: a stop on the way, on the side the arrival said.
 */
function joinLegs(legs) {
    const coords = [];
    const steps = [];
    let side = null;
    legs.forEach(({ line, stepsOf }, li) => {
        const offset = coords.length ? coords.length - 1 : 0;
        coords.push(...(coords.length ? line.slice(1) : line));
        const last = li === legs.length - 1;
        for (const step of stepsOf(offset)) {
            if (step.type === 'arrive' && !last) {
                side = step.modifier;
                continue;
            }
            if (step.type === 'depart' && li > 0) {
                step.type = 'waypoint';
                step.modifier = side || 'straight';
            }
            steps.push(step);
        }
    });
    return { coords, steps };
}

export function fromValhallaTrip(trip, mode) {
    const { coords, steps } = joinLegs(trip.legs.map((leg) => ({
        line: decode(leg.shape),
        stepsOf: (offset) => leg.maneuvers.map((m) => valhallaStep(m, offset)),
    })));
    const sum = trip.summary || trip.legs[0].summary || {};
    return new Route({
        coords, steps, mode, provider: 'valhalla',
        duration: sum.time,
        toll: Boolean(sum.has_toll), ferry: Boolean(sum.has_ferry), highway: Boolean(sum.has_highway),
    });
}

/** Every route in a Valhalla answer: the best first, then the alternatives. */
export function fromValhalla(json, mode) {
    const out = [fromValhallaTrip(json.trip, mode)];
    for (const alt of json.alternates || []) {
        if (alt && alt.trip) out.push(fromValhallaTrip(alt.trip, mode));
    }
    return out;
}

// ---- OSRM ----

const OSRM_TYPES = {
    depart: 'depart', arrive: 'arrive', turn: 'turn', 'end of road': 'turn', continue: 'continue',
    'new name': 'namechange', merge: 'merge', 'on ramp': 'ramp', 'off ramp': 'exit', fork: 'fork',
    roundabout: 'roundabout', rotary: 'roundabout', 'roundabout turn': 'turn',
    'exit roundabout': 'roundabout-exit', 'exit rotary': 'roundabout-exit',
    notification: 'continue', 'use lane': 'continue',
};

function osrmLanes(intersections) {
    const last = intersections && intersections[0];
    if (!last || !Array.isArray(last.lanes) || last.lanes.length === 0) return undefined;
    return last.lanes.map((l) => ({
        turns: (l.indications || []).map((s) => (s === 'none' ? 'straight' : s.replace(' ', '-'))),
        use: Boolean(l.valid),
    }));
}

function osrmSteps(leg, offset) {
    // A step's line begins where the last one ended; the index is where it begins in the joined line.
    let index = offset;
    return leg.steps.map((s) => {
        const type = OSRM_TYPES[s.maneuver.type] || 'other';
        let modifier = (s.maneuver.modifier || 'straight').replace(' ', '-');
        if (type === 'arrive' && !s.maneuver.modifier) modifier = 'straight';
        const step = { type, modifier, index, street: s.name || s.ref || '', time: s.duration || 0 };
        if (type === 'roundabout') step.exit = s.maneuver.exit || undefined;
        if (s.destinations) step.toward = s.destinations;
        const lanes = osrmLanes(s.intersections);
        if (lanes) step.lanes = lanes;
        index += Math.max(0, decode(s.geometry).length - 1);
        return step;
    });
}

export function fromOsrmRoute(r, mode) {
    const { coords, steps } = joinLegs(r.legs.map((leg) => ({
        line: leg.steps.reduce((line, s) => {
            const part = decode(s.geometry);
            return line.length ? line.concat(part.slice(1)) : part;
        }, []),
        stepsOf: (offset) => osrmSteps(leg, offset),
    })));
    if (coords.length < 2 && r.geometry) coords.splice(0, coords.length, ...decode(r.geometry));
    return new Route({ coords, steps, mode, provider: 'osrm', duration: r.duration });
}

export function fromOsrm(json, mode) {
    return (json.routes || []).map((r) => fromOsrmRoute(r, mode));
}

/** Routes that are the same road as one before them, near enough, are left out. */
export function distinct(routes) {
    const out = [];
    for (const r of routes) {
        const same = out.some((o) => Math.abs(o.length - r.length) < 30 && Math.abs(o.duration - r.duration) < 30 &&
            distance(o.coords[o.coords.length >> 1], r.coords[r.coords.length >> 1]) < 50);
        if (!same) out.push(r);
    }
    return out;
}
