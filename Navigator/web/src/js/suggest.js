// A drive the person likely wants now, offered on the map before they ask: to work on a weekday
// morning from home, home in the evening from anywhere else, or where they went at this hour before.

import { distance } from './geo.js';

const near = (here, p, meters) => Boolean(p) && distance(here, [p.lon, p.lat]) < meters;

/**
 * {to: 'work'|'home'|'usual', place} or null, for the person at [here] at [now], with their [home],
 * [work] and past [trips] ({dest, start} each, the newest first).
 */
export function suggestion({ now = new Date(), here, home = null, work = null, trips = [] }) {
    if (!here) return null;
    const hour = now.getHours();
    const weekday = now.getDay() >= 1 && now.getDay() <= 5;
    if (work && weekday && hour >= 6 && hour < 11 && near(here, home, 1500) && !near(here, work, 1000)) return { to: 'work', place: work };
    if (home && hour >= 16 && hour < 23 && !near(here, home, 1000)) return { to: 'home', place: home };
    // Somewhere driven to three times or more around this hour, and not here.
    const counts = new Map();
    for (const t of trips) {
        const h = new Date(t.start).getHours();
        if (Math.abs(h - hour) > 1 || !t.dest || near(here, t.dest, 1000)) continue;
        const key = t.dest.id || `${t.dest.lon},${t.dest.lat}`;
        const c = counts.get(key) || { n: 0, place: t.dest };
        c.n++;
        counts.set(key, c);
    }
    const best = [...counts.values()].sort((a, b) => b.n - a.n)[0];
    return best && best.n >= 3 ? { to: 'usual', place: best.place } : null;
}
