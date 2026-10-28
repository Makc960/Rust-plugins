// What the phone does for the app: position, voice both ways, network, the map kept for offline,
// keeping the screen on, sharing. In the Android app these go through the ShturmanNative bridge; in a
// browser, the web's own APIs where there are any.

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
    download: () => {},
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
    heard(text) {
        const done = listening;
        listening = null;
        if (done) done(text || null);
    },
    download(tag, done, total, failed) {
        handlers.download(tag, done, total, failed);
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

// ---- Listening: a destination said instead of typed ----

let listening = null;
const Recognition = typeof window !== 'undefined' ? window.SpeechRecognition || window.webkitSpeechRecognition : undefined;

export function canListen() {
    if (bridge) return typeof bridge.listen === 'function' && bridge.canListen();
    return Boolean(Recognition);
}

/** What the person says, as text, or null when nothing was heard. Android shows its own listening window. */
export function listen(prompt = 'Куда едем?') {
    if (listening) listening(null);
    return new Promise((resolve) => {
        listening = resolve;
        if (bridge) {
            bridge.listen(prompt);
            return;
        }
        if (!Recognition) {
            window.__shturman.heard(null);
            return;
        }
        const r = new Recognition();
        r.lang = 'ru-RU';
        r.interimResults = false;
        r.onresult = (e) => window.__shturman.heard(e.results[0][0].transcript);
        r.onerror = () => window.__shturman.heard(null);
        r.onend = () => window.__shturman.heard(null);
        r.start();
    });
}

// ---- Network ----

/**
 * A GET (or [method]) to [url]: resolves to {status, text}, rejects when there is no answer.
 * [type] is the body's; [headers] any others ({Authorization: ...}).
 */
export function http(url, { method = 'GET', body = null, type = '', timeout = 15000, headers = {} } = {}) {
    const all = { ...headers };
    if (type) all['Content-Type'] = type;
    if (bridge) {
        return new Promise((resolve, reject) => {
            const id = nextId++;
            pending.set(id, { resolve, reject });
            bridge.http(id, method, url, body || '', timeout, JSON.stringify(all));
        });
    }
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeout);
    return fetch(url, { method, body, headers: all, signal: ctrl.signal })
        .then(async (r) => ({ status: r.status, text: await r.text() }))
        .finally(() => clearTimeout(timer));
}

// ---- Everything else ----

export function keepScreenOn(on) {
    if (bridge) bridge.keepScreenOn(on);
}

/** How often to ask for a position: every second while driving, sparingly while just looking. */
export function setLocationMode(mode) {
    if (bridge && bridge.setLocationMode) bridge.setLocationMode(mode);
}

/** The windscreen view: the screen as bright as it goes, or back as it was. */
export function hud(on) {
    if (bridge && bridge.hud) bridge.hud(on);
}

// ---- The map kept on the phone ----

/** {online, metered}: whether there is a network, and whether it is paid by the megabyte (mobile data). */
export function network() {
    if (bridge && bridge.network) {
        try {
            return JSON.parse(bridge.network());
        } catch (e) {
            // An old bridge: as below.
        }
    }
    return { online: navigator.onLine, metered: false };
}

/** Fetches [urls] onto the phone to keep, as [tag]; news comes to the 'download' handler. False without the app. */
export function prefetch(tag, urls, keep = true) {
    if (!bridge || !bridge.prefetch) return false;
    bridge.prefetch(tag, JSON.stringify(urls), keep);
    return true;
}

export function cancelPrefetch(tag) {
    if (bridge && bridge.cancelPrefetch) bridge.cancelPrefetch(tag);
}

/** {cache, offline}: megabytes of map kept as it was seen, and fetched for the road. */
export function storage() {
    if (bridge && bridge.storage) {
        try {
            return JSON.parse(bridge.storage());
        } catch (e) {
            // Nothing known.
        }
    }
    return null;
}

/** Forgets the map kept on the phone: 'cache', 'offline' or 'all'. */
export function clearMaps(which) {
    if (bridge && bridge.clearMaps) bridge.clearMaps(which);
}

/** «Рядом», if the phone has it; its page to get it otherwise. */
export function openRyadom() {
    if (bridge && bridge.openRyadom) bridge.openRyadom();
    else window.open('https://github.com/Makc960/Rust-plugins/releases/tag/ryadom', '_blank');
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
