// What the phone does for the app: position, voice, network, keeping the screen on, sharing.
// In the Android app these go through the ShturmanNative bridge; in a browser, the web's own APIs.

const bridge = typeof window !== 'undefined' ? window.ShturmanNative : undefined;
export const isNative = Boolean(bridge);

const handlers = {
    location: () => {},
    locationStatus: () => {},
    back: () => false,
    intent: () => {},
    resume: () => {},
    voiceStatus: () => {},
    command: () => {},
};

let nextId = 1;
const pending = new Map();

// The bridge calls these from the Android side.
window.__shturman = {
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
    voiceStatus(status) {
        handlers.voiceStatus(status);
    },
    command(name) {
        handlers.command(name);
    },
};

export function on(name, fn) {
    handlers[name] = fn;
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
        { enableHighAccuracy: true, maximumAge: 1000 },
    );
}

export function stopLocation() {
    if (bridge) bridge.stopLocation();
    else if (watch != null) {
        navigator.geolocation.clearWatch(watch);
        watch = null;
    }
}

export function requestLocation() {
    if (bridge) bridge.requestLocation();
    else startLocation();
}

/** Turns on the phone's location settings screen, when location is off. */
export function openLocationSettings() {
    if (bridge) bridge.openLocationSettings();
}

// ---- Voice ----

export function speak(text) {
    if (bridge) {
        bridge.speak(text);
        return;
    }
    if (typeof speechSynthesis === 'undefined') return;
    const u = new SpeechSynthesisUtterance(text);
    u.lang = 'ru-RU';
    speechSynthesis.cancel();
    speechSynthesis.speak(u);
}

export function stopSpeaking() {
    if (bridge) bridge.stopSpeaking();
    else if (typeof speechSynthesis !== 'undefined') speechSynthesis.cancel();
}

// ---- Network ----

/** A GET (or [method]) to [url]: resolves to {status, text}, rejects when there is no answer. */
export function http(url, { method = 'GET', body = null, type = '', timeout = 15000 } = {}) {
    if (bridge) {
        return new Promise((resolve, reject) => {
            const id = nextId++;
            pending.set(id, { resolve, reject });
            bridge.http(id, method, url, body || '', timeout, type);
        });
    }
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeout);
    return fetch(url, { method, body, headers: type ? { 'content-type': type } : undefined, signal: ctrl.signal })
        .then(async (r) => ({ status: r.status, text: await r.text() }))
        .finally(() => clearTimeout(timer));
}

// ---- Everything else ----

export function keepScreenOn(on) {
    if (bridge) bridge.keepScreenOn(on);
}

/** What the notification says while driving with the app out of sight. */
export function navStatus(title, text) {
    if (bridge && bridge.navStatus) bridge.navStatus(title, text);
}

/** The status bar's icons follow the map: dark over the day map, light at night. */
export function setTheme(theme) {
    if (bridge && bridge.setTheme) bridge.setTheme(theme);
}

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

export function vibrate(ms) {
    if (bridge) bridge.vibrate(ms);
    else if (navigator.vibrate) navigator.vibrate(ms);
}

export function exit() {
    if (bridge) bridge.exit();
}

export function appInfo() {
    if (bridge) {
        try {
            return JSON.parse(bridge.info());
        } catch (e) {
            // An old bridge: fall through to the defaults.
        }
    }
    return { version: 'web', store: 'github', supportUrl: '' };
}
