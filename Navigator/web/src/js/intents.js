// Links other apps open the navigator with: geo:55.75,37.62?q=..., google.navigation:q=..., the
// app's own shortcuts shturman://home, //work, //car, //search, and «Рядом» linking its friends:
// shturman://friends?server=https://...&token=...&name=...
// Returns {point, label} for a place to show, {query} for something to search, {go} for a shortcut,
// {friends: {server, token, name}} for the link to «Рядом», or null.

function coords(text) {
    const m = (text || '').match(/^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)/);
    if (!m) return null;
    const lat = parseFloat(m[1]);
    const lon = parseFloat(m[2]);
    if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat === 0 && lon === 0)) return null;
    return [lon, lat];
}

const SHORTCUTS = new Set(['home', 'work', 'car', 'search']);

export function parseIntent(uri) {
    if (!uri) return null;
    if (uri.startsWith('shturman://friends?')) {
        let q;
        try {
            q = new URLSearchParams(uri.slice('shturman://friends?'.length));
        } catch (e) {
            return null;
        }
        const server = (q.get('server') || '').replace(/\/+$/, '');
        const token = q.get('token') || '';
        if (!/^https:\/\/[a-z0-9.-]+(\/[\w.-]+)*$/i.test(server) || !/^[\w-]{20,200}$/.test(token)) return null;
        return { friends: { server, token, name: (q.get('name') || '').slice(0, 40) } };
    }
    if (uri.startsWith('shturman://')) {
        const go = uri.slice('shturman://'.length).replace(/\/+$/, '');
        return SHORTCUTS.has(go) ? { go } : null;
    }
    let rest;
    if (uri.startsWith('geo:')) rest = uri.slice(4);
    else if (uri.startsWith('google.navigation:')) rest = `0,0?${uri.slice('google.navigation:'.length)}`;
    else return null;
    const [path, queryString = ''] = rest.split('?');
    let q = '';
    try {
        q = (new URLSearchParams(queryString).get('q') || '').trim();
    } catch (e) {
        q = '';
    }
    if (q) {
        // "q=55.75,37.62(Красная площадь)": a point with a name.
        const point = coords(q);
        if (point) {
            const label = (q.match(/\((.*)\)\s*$/) || [])[1];
            return { point, label: label ? label.trim() : '' };
        }
        return { query: q };
    }
    const point = coords(path);
    return point ? { point, label: '' } : null;
}
