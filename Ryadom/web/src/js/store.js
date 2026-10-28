// What the app remembers on this phone besides the account: the map's look and where it was.

const KEY = 'ryadom.v1';
const DEFAULTS = { style: 'hybrid', view: null, askedBackground: false };

export function load() {
    try {
        return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(KEY) || '{}') };
    } catch (e) {
        return { ...DEFAULTS };
    }
}

export function save(value) {
    try {
        localStorage.setItem(KEY, JSON.stringify(value));
    } catch (e) {
        // Storage full or off: the app works on, it only forgets.
    }
}
