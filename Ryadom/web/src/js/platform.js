// What the phone does for the app: position, network, the account, sharing in the background.
// In the Android app these go through the RyadomNative bridge; in a browser, the web's own APIs.

const bridge = typeof window !== 'undefined' ? window.RyadomNative : undefined;
export const isNative = Boolean(bridge);

const handlers = {
    location: () => {},
    locationStatus: () => {},
    back: () => false,
    intent: () => {},
    resume: () => {},
    pause: () => {},
};

let nextId = 1;
const pending = new Map();

// The bridge calls these from the Android side.
window.__ryadom = {
    location(fix) {
        handlers.location(fix);
    },
    locationStatus(status) {
        handlers.locationStatus(status);
    },
    http(id, status, text) {
        const p = pending.get(id);
        if (!p) return;
        pending.delete(id);
        if (status < 0) p.reject(new Error(text || 'network'));
        else p.resolve({ status, text });
    },
    back() {
        return handlers.back() === true;
    },
    intent(uri) {
        handlers.intent(uri);
    },
    insets(top, right, bottom, left) {
        const s = document.documentElement.style;
        s.setProperty('--inset-top', `${top}px`);
        s.setProperty('--inset-right', `${right}px`);
        s.setProperty('--inset-bottom', `${bottom}px`);
        s.setProperty('--inset-left', `${left}px`);
    },
    resume() {
        handlers.resume();
    },
    pause() {
        handlers.pause();
    },
};

export function on(name, fn) {
    handlers[name] = fn;
}

if (!bridge && typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', () => (document.hidden ? handlers.pause() : handlers.resume()));
}

// ---- Position ----

let watch = null;

export function startLocation() {
    if (bridge) {
        bridge.startLocation();
        return;
    }
    if (!navigator.geolocation || watch != null) return;
    watch = navigator.geolocation.watchPosition(
        (p) => handlers.location({
            lon: p.coords.longitude, lat: p.coords.latitude, accuracy: p.coords.accuracy,
            speed: p.coords.speed, heading: Number.isFinite(p.coords.heading) ? p.coords.heading : null, time: p.timestamp,
        }),
        (e) => handlers.locationStatus(e.code === 1 ? 'denied' : 'unavailable'),
        { enableHighAccuracy: true, maximumAge: 5000 },
    );
}

export function stopLocation() {
    if (bridge) bridge.stopLocation();
    else if (watch != null) {
        navigator.geolocation.clearWatch(watch);
        watch = null;
    }
}

/** Asks for the permission again (or opens the app's settings once it was refused for good). */
export function requestLocation() {
    if (bridge) bridge.requestLocation();
    else startLocation();
}

export function openLocationSettings() {
    if (bridge) bridge.openLocationSettings();
}

// ---- Network ----

/** A request to [url]: resolves to {status, text}, rejects when there is no answer. */
export function http(url, { method = 'GET', body = null, type = '', headers = {}, timeout = 15000 } = {}) {
    const all = { ...headers, ...(type ? { 'Content-Type': type } : {}) };
    if (bridge) {
        return new Promise((resolve, reject) => {
            const id = nextId++;
            pending.set(id, { resolve, reject });
            bridge.http(id, method, url, body || '', timeout, JSON.stringify(all));
        });
    }
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeout);
    return fetch(url, { method, body: body || undefined, headers: all, signal: ctrl.signal })
        .then(async (r) => ({ status: r.status, text: await r.text() }))
        .finally(() => clearTimeout(timer));
}

// ---- The account and sharing ----

const ACCOUNT = 'ryadom.account';

/** {token, id} of this phone's account, or null before there is one. */
export function account() {
    try {
        const raw = bridge ? bridge.account() : localStorage.getItem(ACCOUNT);
        return raw ? JSON.parse(raw) : null;
    } catch (e) {
        return null;
    }
}

export function saveAccount(value) {
    const raw = value ? JSON.stringify(value) : '';
    if (bridge) bridge.saveAccount(raw);
    else if (raw) localStorage.setItem(ACCOUNT, raw);
    else localStorage.removeItem(ACCOUNT);
}

/**
 * Sends this phone's position to [server] in the background too, with the screen off: the phone
 * runs a service with a notification for it. Without the bridge, only the open page sends.
 */
export function startSharing(server, token) {
    if (bridge) bridge.startSharing(server, token);
}

export function stopSharing() {
    if (bridge) bridge.stopSharing();
}

/** {always, notifications, unrestricted}: what lets sharing go on in the background. */
export function background() {
    if (bridge) {
        try {
            return JSON.parse(bridge.background());
        } catch (e) {
            // An old bridge: say all is well.
        }
    }
    return { always: true, notifications: true, unrestricted: true };
}

/** Asks for location «always», for sharing after a restart with the app closed. */
export function requestBackground() {
    if (bridge) bridge.requestBackground();
}

export function requestNotifications() {
    if (bridge) bridge.requestNotifications();
}

/** Opens the phone's battery settings for the app, where it can be let run unrestricted. */
export function openBatterySettings() {
    if (bridge) bridge.openBatterySettings();
}

let batteryState = null;
if (!bridge && typeof navigator !== 'undefined' && navigator.getBattery) {
    navigator.getBattery().then((b) => {
        const read = () => (batteryState = { level: Math.round(b.level * 100), charging: b.charging });
        read();
        b.addEventListener('levelchange', read);
        b.addEventListener('chargingchange', read);
    }).catch(() => {});
}

/** {level, charging}, or null when the phone does not say. */
export function battery() {
    if (bridge) {
        try {
            return JSON.parse(bridge.battery());
        } catch (e) {
            return null;
        }
    }
    return batteryState;
}

// ---- Everything else ----

export function share(text) {
    if (bridge) bridge.share(text);
    else if (navigator.share) navigator.share({ text }).catch(() => {});
    else if (navigator.clipboard) navigator.clipboard.writeText(text).catch(() => {});
}

export function copy(text) {
    if (bridge) bridge.copy(text);
    else if (navigator.clipboard) navigator.clipboard.writeText(text).catch(() => {});
}

export function openUrl(url) {
    if (bridge) bridge.openUrl(url);
    else window.open(url, '_blank');
}

/** Opens a place in the phone's maps or navigator app, for a route there. */
/** A route to a friend: in «Навигатор» when the phone has it, in any navigator otherwise. */
export function openPlace(lat, lon, name) {
    const geo = `geo:${lat},${lon}?q=${lat},${lon}(${encodeURIComponent(name)})`;
    if (bridge && bridge.openRoute) bridge.openRoute(geo);
    else if (bridge) bridge.openUrl(geo);
    else window.open(`https://yandex.ru/maps/?pt=${lon},${lat}&z=17&l=map`, '_blank');
}

/**
 * Hands «Навигатор» a key that only reads friends' places, so it shows them on its map.
 * False when the phone has no «Навигатор» (its page opens instead).
 */
export function showInNavigator(server, token, name) {
    if (!bridge || !bridge.openNavigator) return false;
    const q = new URLSearchParams({ server, token, name: name || '' });
    return bridge.openNavigator(`shturman://friends?${q}`);
}

export function vibrate(ms) {
    if (bridge) bridge.vibrate(ms);
    else if (navigator.vibrate) navigator.vibrate(ms);
}

export function exit() {
    if (bridge) bridge.exit();
}

/** {version, store, server}: the server is the one this build talks to. */
export function appInfo() {
    let info = {};
    if (bridge) {
        try {
            info = JSON.parse(bridge.info());
        } catch (e) {
            info = {};
        }
    }
    const fromPage = typeof location !== 'undefined' ? new URLSearchParams(location.search).get('server') : null;
    return { version: 'web', store: 'github', server: 'https://ice-rust.store/ryadom', ...info, ...(fromPage ? { server: fromPage } : {}) };
}
