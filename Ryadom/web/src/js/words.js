// How the app says times, distances and the rest in Russian.

/** The right form of a word for [n]: plural(5, 'минута', 'минуты', 'минут') → 'минут'. */
export function plural(n, one, few, many) {
    const a = Math.abs(n) % 100;
    const b = a % 10;
    if (a > 10 && a < 20) return many;
    if (b === 1) return one;
    if (b >= 2 && b <= 4) return few;
    return many;
}

function two(n) {
    return String(n).padStart(2, '0');
}

function clock(date) {
    return `${two(date.getHours())}:${two(date.getMinutes())}`;
}

/** How long ago [time] was at [now], both in ms: «только что», «5 мин назад», «вчера в 18:20». */
export function ago(time, now) {
    const s = Math.max(0, (now - time) / 1000);
    if (s < 60) return 'только что';
    const m = Math.floor(s / 60);
    if (m < 60) return `${m} мин назад`;
    const h = Math.floor(m / 60);
    if (h < 6) return `${h} ${plural(h, 'час', 'часа', 'часов')} назад`;
    const then = new Date(time);
    const today = new Date(now);
    const yesterday = new Date(now - 86400000);
    if (then.toDateString() === today.toDateString()) return `сегодня в ${clock(then)}`;
    if (then.toDateString() === yesterday.toDateString()) return `вчера в ${clock(then)}`;
    return `${two(then.getDate())}.${two(then.getMonth() + 1)} в ${clock(then)}`;
}

/** «350 м», «1,2 км», «24 км». */
export function distance(meters) {
    if (!Number.isFinite(meters)) return '';
    if (meters < 1000) return `${Math.max(10, Math.round(meters / 10) * 10)} м`;
    if (meters < 10000) return `${(meters / 1000).toFixed(1).replace('.', ',')} км`;
    return `${Math.round(meters / 1000)} км`;
}

/** A moving speed in km/h, or '' when standing or walking slowly. */
export function speed(metersPerSecond) {
    if (!Number.isFinite(metersPerSecond) || metersPerSecond < 2.5) return '';
    return `${Math.round(metersPerSecond * 3.6)} км/ч`;
}

/** The first letters of the first two words, for the round avatars. */
export function initials(name) {
    const words = String(name || '').trim().split(/\s+/).filter(Boolean);
    if (!words.length) return '?';
    const letters = words.length > 1 ? words[0][0] + words[1][0] : [...words[0]].slice(0, 1).join('');
    return letters.toUpperCase();
}

/** Text for putting into HTML. */
export function escape(s) {
    return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

/** «2 друга», «5 друзей». */
export function friendsCount(n) {
    return `${n} ${plural(n, 'друг', 'друга', 'друзей')}`;
}
