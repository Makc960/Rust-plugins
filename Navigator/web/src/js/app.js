// Навигатор: the screens and what happens on them. Browsing the map, searching, a place, choosing a
// route, and driving it with the banner, the voice, road warnings and drivers' reports, and new
// routes when the car goes another way; settings, past drives and the map kept for offline.

import { along, distance, slice } from './geo.js';
import { HAZARD_COLORS, ICONS, laneIcon, maneuverIcon } from './icons.js';
import { parseIntent } from './intents.js';
import { MapView } from './mapview.js';
import { countByZoom, estimateMb, MAX_ZOOM, styleUrls, tilesAlong, tilesInBox, tileUrls } from './offline.js';
import * as platform from './platform.js';
import { findCurves, HAZARDS, hazardAhead, limitAt, mergeHazards, placeReports, REPORT_KINDS } from './roadinfo.js';
import {
    alongRoute, areaAround, boxAround, CATEGORIES, endTrip, fetchConfig, hazards as findHazards, nearby, reportsIn, reverse,
    routes as findRoutes, search, sendFeedback, sendReport, shareTrip, speedLimits, thinLine, updateTrip, useServers, voteReport,
} from './services.js';
import { Simulator } from './sim.js';
import { store } from './store.js';
import { suggestion } from './suggest.js';
import { isNight } from './sun.js';
import { Tracker } from './tracker.js';
import { Announcer, RoadAlerts } from './voice.js';
import { clockText, distanceText, durationText, plural, stepText } from './words.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

const MOSCOW = [37.6176, 55.7558];
const MODES = [['car', 'Авто'], ['bike', 'Вело'], ['foot', 'Пешком']];
const DEMO_SPEEDS = [1, 2, 4, 8];

const S = {
    screen: 'browse',          // browse, search, place, preview, nav
    fix: null,
    fixAt: 0,
    locStatus: 'waiting',      // waiting, ok, denied, off
    centeredOnce: false,
    place: null,
    placeLoading: false,
    preview: null,             // {dest, routes, selected, loading, error, fromCenter}
    nav: null,
    query: '',
    results: null,
    searching: false,
    searchError: '',
    searchSeq: 0,
    everywhere: false,         // the last search was asked to look past the town
    resultsScope: null,        // local, elsewhere, everywhere (see services.search)
    resultsArea: null,
    area: null,                // the town around the person: {name, bbox, point}
    areaAsked: null,
    routeSeq: 0,
    theme: 'day',
    dialog: null,
    along: null,               // the "along the route" sheet while driving: {cat, results, loading, error}
    exact: true,               // the last search found the very house asked for
    precise: false,            // the last search was sent, not typed (Nominatim was asked too)
    hazard: null,              // a warning or report tapped on the map, shown in the sheet
    friend: null,              // a friend from «Рядом» tapped on the map
    friends: [],
    viewReports: [],           // drivers' reports around what the map shows, while browsing
    reportsAt: null,           // {box, time} of the last look for them
    suggest: null,             // {to, place, route?} offered on the map
    suggestAt: null,
    report: null,              // the "what is on the road" sheet while driving
    hud: false,
    downloads: {},             // tag -> {done, total, failed, name}
};

// ---- Starting up ----

// The servers last named by the app's own server, at once; the town last searched in, if still here.
if (store.data.config) useServers(store.data.config.servers);
if (store.data.area) S.area = store.data.area;

const view = store.data.view;
S.theme = themeFor(view ? view.center : MOSCOW);
document.documentElement.dataset.theme = S.theme;

const map = new MapView($('map'), {
    theme: S.theme,
    mapType: store.settings.mapType,
    center: view ? view.center : MOSCOW,
    zoom: view ? view.zoom : 11,
});

$('menu-btn').innerHTML = ICONS.menu;
$('search-open').innerHTML = `${ICONS.search}<span>Куда едем?</span>`;
$('voice-btn').innerHTML = ICONS.mic;
$('search-voice').innerHTML = ICONS.mic;
$('voice-btn').hidden = $('search-voice').hidden = !platform.canListen();
$('locate-btn').innerHTML = ICONS.locate;
$('layers-btn').innerHTML = ICONS.layers;
$('report-btn').innerHTML = ICONS.report;
$('compass-btn').innerHTML = ICONS.compass;
$('search-back').innerHTML = ICONS.back;
$('search-clear').innerHTML = ICONS.close;
$('resume-btn').innerHTML = `${ICONS.navigate}<span>Продолжить</span>`;

platform.on('location', onFix);
platform.on('locationStatus', (status) => {
    S.locStatus = status;
    renderTop();
});
platform.on('back', onBack);
platform.on('intent', openIntent);
platform.on('resume', () => applyTheme());
// The notification's buttons while driving.
platform.on('command', (name) => {
    if (name === 'end' && S.nav) endNav();
});
platform.on('voiceStatus', (status) => {
    if (status === 'missing') toast('Нет русского голоса: установите его в настройках телефона (Синтез речи)', 6000);
});
platform.on('download', onDownload);

// ---- Position ----

function onFix(fix) {
    S.fix = fix;
    S.fixAt = Date.now();
    if (S.locStatus !== 'ok') {
        S.locStatus = 'ok';
        renderTop();
    }
    if (S.nav) {
        if (!S.nav.sim) navUpdate(fix);
        return;
    }
    const moving = fix.speed != null && fix.speed > 1 && fix.heading != null;
    map.moveTo(fix, [fix.lon, fix.lat], moving ? fix.heading : null);
    if (!S.centeredOnce) {
        S.centeredOnce = true;
        if (S.screen === 'browse') {
            map.setMode('follow');
            updateLocateButton();
        }
    }
    refreshSuggestion();
}

function here() {
    return S.fix ? [S.fix.lon, S.fix.lat] : null;
}

function themeFor(point) {
    const t = store.settings.theme;
    if (t === 'day' || t === 'night') return t;
    return isNight(new Date(), point[1], point[0]) ? 'night' : 'day';
}

function applyTheme() {
    const t = themeFor(here() || map.center);
    if (t === S.theme) return;
    S.theme = t;
    document.documentElement.dataset.theme = t;
    map.setTheme(t);
    applyBars();
}

/** The status bar's icons: light over the night map and over satellite pictures, dark otherwise. */
function applyBars() {
    platform.setTheme(S.theme === 'night' || store.settings.mapType !== 'scheme' ? 'night' : 'day');
}

const MAP_TYPES = [['scheme', 'Схема'], ['hybrid', 'Гибрид'], ['satellite', 'Спутник']];

function setMapType(type) {
    store.set('mapType', type);
    map.setMapType(type);
    applyBars();
}

let tickCount = 0;
function tick() {
    tickCount++;
    if (tickCount % 30 === 0) applyTheme();
    if (tickCount % 30 === 5) loadFriends();
    if (S.nav && !S.nav.sim && !S.nav.arrived) {
        deadReckon();
        renderBanner();
    }
    if (S.nav && !S.nav.arrived) {
        if (tickCount % 120 === 60) loadNavReports(S.nav);
        if (tickCount % 20 === 10) sendTripNews(S.nav);
    }
}

// ---- The map's own events ----

map.on('userMoved', (was) => {
    updateLocateButton();
    if (S.nav && (was === 'nav' || was === 'overview')) $('resume-btn').hidden = false;
});
map.on('rotate', (b) => {
    $('compass-btn').hidden = S.screen === 'nav' || Math.abs(b) < 2;
    $('compass-btn').firstChild.style.transform = `rotate(${-b}deg)`;
});
map.on('moveend', () => {
    if (S.nav) return;
    clearTimeout(S.saveTimer);
    S.saveTimer = setTimeout(() => {
        store.setView(map.center, map.zoom);
        loadViewReports();
    }, 1000);
});
map.on('styleReady', () => showHazards());
map.on('hazardTap', (id) => {
    if (S.screen === 'search') return;
    const h = shownHazards().find((x) => x.id === id);
    if (h) openHazard(h);
});
map.on('friendTap', (id) => {
    const f = S.friends.find((x) => x.id === id);
    if (f && S.screen !== 'nav' && S.screen !== 'preview') openFriend(f);
});
map.on('routeTap', (i) => {
    if (S.screen === 'preview' && S.preview && S.preview.routes[i]) selectRoute(i);
});
map.on('longPress', (point) => {
    if (S.screen === 'nav' || S.screen === 'preview') return;
    platform.vibrate(20);
    openPlace({ title: 'Точка на карте', subtitle: '', kind: '', lon: point[0], lat: point[1], id: `${point[0]},${point[1]}` }, { lookup: true, fly: false });
});
map.on('poiTap', ({ name, point }) => {
    if (S.screen === 'nav' || S.screen === 'preview') return;
    openPlace({ title: name, subtitle: '', kind: '', lon: point[0], lat: point[1], id: `${name}@${point[0].toFixed(5)},${point[1].toFixed(5)}` }, { lookup: true, keepTitle: true, fly: false });
});
map.on('tap', () => {
    if (S.screen === 'place') closePlace();
    else if (S.hazard && S.screen !== 'nav') closeHazard();
});
let mapErrorShown = 0;
map.on('error', () => {
    if (Date.now() - mapErrorShown > 30000 && !navigator.onLine) {
        mapErrorShown = Date.now();
        toast('Нет интернета: карта загрузится, когда он появится');
    }
});

// ---- Static buttons ----

function wireStatic() {
    $('menu-btn').onclick = openSettings;
    $('search-open').onclick = () => openSearch('');
    $('voice-btn').onclick = () => voiceSearch();
    $('search-voice').onclick = () => voiceSearch();
    $('report-btn').onclick = () => openReport();
    $('speed').addEventListener('click', (e) => {
        const chip = e.target.closest('.hz-chip');
        const h = chip && S.nav && S.nav.hazards.find((x) => x.id === chip.dataset.id);
        if (h) openHazard(h);
    });
    $('suggest').onclick = (e) => {
        if (e.target.closest('[data-act="hideSuggest"]')) {
            S.suggestHidden = Date.now();
            S.suggest = null;
            renderSuggestion();
            return;
        }
        if (S.suggest) openPreview(S.suggest.place);
    };
    $('locate-btn').onclick = () => {
        if (!S.fix) {
            if (S.locStatus === 'denied') platform.requestLocation();
            else if (S.locStatus === 'off') platform.openLocationSettings();
            toast('Ищу ваше местоположение…');
            return;
        }
        if (S.nav) map.setMode('nav');
        else map.setMode('follow');
        updateLocateButton();
    };
    $('compass-btn').onclick = () => map.resetNorth();
    // Схема → Гибрид → Спутник → Схема.
    $('layers-btn').onclick = () => {
        const i = MAP_TYPES.findIndex(([v]) => v === store.settings.mapType);
        const [type, label] = MAP_TYPES[(i + 1) % MAP_TYPES.length];
        setMapType(type);
        toast(label);
    };
    $('search-back').onclick = () => closeSearch();
    $('search-clear').onclick = () => {
        $('search-input').value = '';
        onQuery('');
        $('search-input').focus();
    };
    let timer = null;
    $('search-input').addEventListener('input', (e) => {
        clearTimeout(timer);
        const q = e.target.value;
        $('search-clear').hidden = !q;
        timer = setTimeout(() => onQuery(q), 350);
    });
    $('search-input').addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            clearTimeout(timer);
            // Sent, not typed: a house number Photon misses may be asked of Nominatim too.
            onQuery(e.target.value, { precise: true });
            e.target.blur();
        }
    });
    $('resume-btn').onclick = () => {
        map.setMode('nav');
        $('resume-btn').hidden = true;
    };
    delegate($('chips'), chipActions);
    delegate($('notice'), {
        allow: () => platform.requestLocation(),
        settings: () => platform.openLocationSettings(),
    });
    delegate($('search-results'), resultActions);
    delegate($('sheet'), sheetActions);
    delegate($('navbar'), navActions);
    delegate($('banner'), {});
    delegate($('settings'), settingsActions);
    delegate($('steps'), { closeSteps });
    delegate($('history'), historyActions);
    delegate($('prompt'), promptActions);
    delegate($('hud'), hudActions);
    delegate($('dialog'), dialogActions);
}

/** Clicks inside [root] on anything with data-act run actions[act](element). */
function delegate(root, actions) {
    root.addEventListener('click', (e) => {
        const el = e.target.closest('[data-act]');
        if (!el || !root.contains(el)) return;
        const fn = actions[el.dataset.act];
        if (fn) fn(el, e);
    });
}

function updateLocateButton() {
    $('locate-btn').classList.toggle('on', map.mode === 'follow' || map.mode === 'nav');
}

function setScreen(screen) {
    S.screen = screen;
    const driving = screen === 'nav';
    document.body.classList.toggle('driving', driving);
    $('top').hidden = screen !== 'browse';
    $('search').hidden = screen !== 'search';
    $('sheet').hidden = !(screen === 'place' || screen === 'preview' || (screen === 'nav' && (S.along || S.report || S.hazard)) || (screen === 'browse' && S.hazard));
    for (const id of ['banner', 'navbar']) $(id).hidden = !driving;
    if (!driving) {
        $('speed').hidden = true;
        $('lanes').hidden = true;
        $('resume-btn').hidden = true;
    }
    $('fabs').hidden = screen === 'search';
    $('compass-btn').hidden = driving || Math.abs(map.map.getBearing()) < 2;
    $('report-btn').hidden = !driving;
    if (!driving) $('prompt').hidden = true;
    renderSuggestion();
    requestAnimationFrame(layoutPanels);
}

/** Moves the map's buttons and credits above whatever panel is at the bottom. */
function layoutPanels() {
    const root = document.documentElement.style;
    let bottom = 0;
    if (!$('suggest').hidden) bottom = $('suggest').offsetHeight + 12;
    if (!$('sheet').hidden) bottom = $('sheet').offsetHeight;
    if (!$('navbar').hidden) bottom = $('navbar').offsetHeight;
    const landscape = matchMedia('(orientation: landscape) and (max-height: 600px)').matches;
    root.setProperty('--fab-bottom', `${(landscape ? 0 : bottom) + 20}px`);
    root.setProperty('--map-bottom', `${landscape ? 0 : bottom}px`);
    root.setProperty('--nav-height', `${$('navbar').hidden ? 0 : $('navbar').offsetHeight}px`);
    root.setProperty('--toast-bottom', `${bottom + 24}px`);
    const h = window.innerHeight;
    map.setNavPadding(landscape
        ? { top: Math.round(h * 0.3), bottom: 0, left: $('navbar').offsetWidth || 0, right: 0 }
        : { top: Math.round(h * 0.42), bottom: 0, left: 0, right: 0 });
    positionLanes();
}
window.addEventListener('resize', () => requestAnimationFrame(layoutPanels));

/** Room on the map around the panels, for fitting a route into view. */
function mapPadding() {
    const landscape = matchMedia('(orientation: landscape) and (max-height: 600px)').matches;
    const sheet = $('sheet').hidden ? 0 : $('sheet').offsetHeight;
    const nav = $('navbar').hidden ? 0 : $('navbar').offsetHeight;
    const banner = $('banner').hidden ? 0 : $('banner').offsetHeight;
    if (landscape) {
        const panel = Math.max($('sheet').hidden ? 0 : $('sheet').offsetWidth, $('navbar').hidden ? 0 : $('navbar').offsetWidth);
        return { top: 40, bottom: 40, left: panel + 40, right: 70 };
    }
    return { top: Math.max(70, banner + 40), bottom: Math.max(sheet, nav) + 40, left: 40, right: 70 };
}

// ---- Browsing: the bar and chips ----

function renderTop() {
    const d = store.data;
    const chips = [];
    chips.push(d.home
        ? `<button class="chip" data-act="go" data-which="home">${ICONS.home}<span>Дом</span></button>`
        : `<button class="chip muted" data-act="setHint" data-which="home">${ICONS.home}<span>Дом</span></button>`);
    chips.push(d.work
        ? `<button class="chip" data-act="go" data-which="work">${ICONS.work}<span>Работа</span></button>`
        : `<button class="chip muted" data-act="setHint" data-which="work">${ICONS.work}<span>Работа</span></button>`);
    if (d.parked) chips.push(`<button class="chip" data-act="car">${ICONS.car}<span>Машина</span></button>`);
    d.saved.slice(0, 4).forEach((p, i) => chips.push(`<button class="chip" data-act="go" data-which="saved" data-i="${i}">${ICONS.starFilled}<span>${esc(p.title)}</span></button>`));
    d.recent.filter((r) => !d.saved.some((s) => s.id === r.id)).slice(0, 4).forEach((p) => {
        const i = d.recent.indexOf(p);
        chips.push(`<button class="chip" data-act="go" data-which="recent" data-i="${i}">${ICONS.clock}<span>${esc(p.title)}</span></button>`);
    });
    $('chips').innerHTML = chips.join('');

    const n = $('notice');
    if (S.locStatus === 'denied') {
        n.hidden = false;
        n.innerHTML = `${ICONS.gps}<div class="grow"><b>Нет доступа к геопозиции</b>Без неё не построить маршрут от вас</div><button class="btn small primary" data-act="allow">Разрешить</button>`;
    } else if (S.locStatus === 'off') {
        n.hidden = false;
        n.innerHTML = `${ICONS.gps}<div class="grow"><b>Геопозиция выключена</b>Включите её в настройках телефона</div><button class="btn small primary" data-act="settings">Включить</button>`;
    } else {
        n.hidden = true;
    }
}

const chipActions = {
    car() {
        openParked();
    },
    go(el) {
        const d = store.data;
        const which = el.dataset.which;
        const place = which === 'home' ? d.home : which === 'work' ? d.work : d[which][+el.dataset.i];
        if (place) openPreview(place);
    },
    setHint(el) {
        openSearch('');
        toast(el.dataset.which === 'home'
            ? 'Найдите свой адрес, откройте его и выберите «Сделать домом»'
            : 'Найдите адрес работы, откройте его и выберите «Сделать работой»', 4500);
    },
};

// ---- Search ----

function openSearch(query) {
    setScreen('search');
    const input = $('search-input');
    input.value = query;
    $('search-clear').hidden = !query;
    S.query = '';
    onQuery(query);
    setTimeout(() => input.focus(), 50);
}

function closeSearch() {
    $('search-input').blur();
    setScreen(S.place ? 'place' : 'browse');
    if (S.place) renderSheet();
}

/**
 * The town search looks in first: the one around the person (or the map, without a position),
 * asked again only after moving 3 km.
 */
async function currentArea() {
    const point = here() || map.center;
    if (S.area && distance(S.area.point, point) < 3000) return S.area;
    if (!S.areaAsked || distance(S.areaAsked.point, point) >= 3000) {
        S.areaAsked = { point, promise: areaAround(platform.http, point) };
    }
    S.area = await S.areaAsked.promise.catch(() => ({ name: null, bbox: boxAround(point, 25000), point }));
    // Kept for the next start, when the first search should not wait for it.
    if (S.area.name) store.put('area', S.area);
    return S.area;
}

async function onQuery(q, { everywhere = false, precise = false } = {}) {
    q = q.trim();
    if (q === S.query && S.results && everywhere === S.everywhere && (!precise || S.precise || S.exact)) return;
    S.query = q;
    S.everywhere = everywhere;
    S.precise = precise;
    S.exact = true;
    S.resultsKind = null;
    S.resultsScope = null;
    const seq = ++S.searchSeq;
    if (q.length < 2) {
        S.results = null;
        S.searching = false;
        renderResults();
        return;
    }
    S.searching = true;
    S.searchError = '';
    renderResults();
    try {
        const area = everywhere ? null : await currentArea();
        const found = await search(platform.http, q, here() || map.center, area, { everywhere, precise });
        if (seq !== S.searchSeq) return;
        S.results = found.places;
        S.resultsScope = found.scope;
        S.resultsArea = found.area;
        S.exact = found.exact !== false;
    } catch (e) {
        if (seq !== S.searchSeq) return;
        S.results = null;
        S.searchError = e.message || 'Поиск не удался';
    }
    S.searching = false;
    renderResults();
}

function distanceTo(p) {
    const from = here();
    return from ? distanceText(distance(from, [p.lon, p.lat])) : '';
}

function placeRow(p, act, extra = '', icon = ICONS.place, grey = false) {
    const sub = [p.kind, p.subtitle].filter(Boolean).join(' · ');
    return `<button class="row" data-act="${act}" ${extra}>
        <span class="ico ${grey ? 'grey' : ''}">${icon}</span>
        <span class="text"><div class="title">${esc(p.title)}</div>${sub ? `<div class="sub">${esc(sub)}</div>` : ''}</span>
        <span class="side">${esc(distanceTo(p))}</span>
    </button>`;
}

/** The kinds of places as round coloured buttons, for nearby and along the route. */
const CAT_COLORS = {
    fuel: '#e5484d', cafe: '#a0522d', food: '#f97316', pharmacy: '#12a150', atm: '#0b9bd7',
    parking: '#2f6fed', shop: '#8e4ec6', charging: '#10b981', repair: '#64748b', toilets: '#0ea5a4',
};

function catButton(c, act, on = false) {
    return `<button class="cat ${on ? 'on' : ''}" data-act="${act}" data-id="${c.id}" style="--c:${CAT_COLORS[c.id] || 'var(--accent)'}"><span class="cat-ico">${ICONS[c.id]}</span><span>${c.label}</span></button>`;
}

/** The last rows of found places: a closer look for a house, and «Не то нашлось». */
function resultsFooter() {
    const parts = [];
    if (!S.exact && !S.precise) {
        parts.push(`<button class="row more" data-act="precise"><span class="ico">${ICONS.search}</span><span class="text"><div class="title">Найти этот дом точнее</div><div class="sub">Поищу номер дома в другом справочнике</div></span></button>`);
    }
    parts.push(`<button class="feedback-link" data-act="feedback">${ICONS.info}<span>Не то нашлось? Сообщите — поиск станет точнее</span></button>`);
    return parts.join('');
}

function renderResults() {
    const box = $('search-results');
    const d = store.data;
    if (!S.query || S.query.length < 2) {
        const parts = [];
        parts.push(`<div class="group-title">Рядом</div><div class="cats">${CATEGORIES.map((c) => catButton(c, 'cat')).join('')}</div>`);
        parts.push(d.home ? placeRow({ ...d.home, title: 'Дом', subtitle: d.home.title }, 'pick', 'data-which="home"', ICONS.home)
            : `<button class="row" data-act="noHome"><span class="ico grey">${ICONS.home}</span><span class="text"><div class="title">Дом</div><div class="sub">Не задан</div></span></button>`);
        parts.push(d.work ? placeRow({ ...d.work, title: 'Работа', subtitle: d.work.title }, 'pick', 'data-which="work"', ICONS.work)
            : `<button class="row" data-act="noWork"><span class="ico grey">${ICONS.work}</span><span class="text"><div class="title">Работа</div><div class="sub">Не задана</div></span></button>`);
        if (d.saved.length) {
            parts.push('<div class="group-title">Избранное</div>');
            d.saved.forEach((p, i) => parts.push(placeRow(p, 'pick', `data-which="saved" data-i="${i}"`, ICONS.starFilled)));
        }
        if (d.recent.length) {
            parts.push('<div class="group-title">Недавние</div>');
            d.recent.forEach((p, i) => parts.push(placeRow(p, 'pick', `data-which="recent" data-i="${i}"`, ICONS.clock, true)
                .replace('</button>', `<span class="side-btn" data-act="forget" data-i="${i}" aria-label="Убрать">${ICONS.close}</span></button>`)));
        }
        if (!d.saved.length && !d.recent.length) {
            parts.push(`<div class="empty">${ICONS.search}Введите адрес, название места или координаты.<br>Долгое нажатие на карте ставит точку.</div>`);
        }
        box.innerHTML = parts.join('');
        return;
    }
    if (S.searching && !S.results) {
        box.innerHTML = `<div class="loading" style="padding:22px 18px"><span class="spinner"></span>Ищу…</div>`;
        return;
    }
    if (S.searchError) {
        box.innerHTML = `<div class="empty">${ICONS.warning}${esc(S.searchError)}<br><br><button class="btn small" data-act="retry">Повторить</button></div>`;
        return;
    }
    const town = S.resultsArea?.name;
    const inTown = town ? `в городе ${town}` : 'рядом с вами';
    if (!S.results || !S.results.length) {
        const where = S.everywhere || S.resultsScope === 'everywhere' ? 'нигде' : `ни ${inTown}, ни в других местах`;
        box.innerHTML = `<div class="empty">${ICONS.search}«${esc(S.query)}» не нашлось ${esc(where)}.<br>Проверьте, как написан адрес, или добавьте город: «Сочи, Курортный проспект 50».</div>${S.resultsKind ? '' : resultsFooter()}`;
        return;
    }
    const icon = (S.resultsKind && ICONS[S.resultsKind]) || ICONS.place;
    const rows = S.results.map((p, i) => placeRow(p, 'pick', `data-which="results" data-i="${i}"`, icon)).join('');
    const scope = S.resultsKind ? null : S.resultsScope;
    const footer = S.resultsKind ? '' : resultsFooter();
    if (scope === 'local') {
        box.innerHTML = `<div class="group-title">${esc(town ? `В городе ${town}` : 'Рядом с вами')}</div>${rows}
            <button class="row more" data-act="everywhere"><span class="ico grey">${ICONS.search}</span><span class="text"><div class="title">Искать в других городах</div></span></button>${footer}`;
    } else if (scope === 'elsewhere') {
        box.innerHTML = `<div class="notice-row">${ICONS.warning}<span>${esc(`«${S.query}» ${inTown} не нашлось. Ближайшее в других местах:`)}</span></div>${rows}${footer}`;
    } else if (scope === 'everywhere') {
        box.innerHTML = `<div class="group-title">Везде</div>${rows}
            ${town ? `<button class="row more" data-act="local"><span class="ico grey">${ICONS.place}</span><span class="text"><div class="title">${esc(`Только в городе ${town}`)}</div></span></button>` : ''}${footer}`;
    } else {
        box.innerHTML = rows + footer;
    }
}

/** A destination said aloud: what was heard goes into the search as if typed and sent. */
async function voiceSearch() {
    platform.vibrate(15);
    const heard = await platform.listen('Куда едем?');
    if (!heard) {
        if (S.screen === 'search') $('search-input').focus();
        return;
    }
    if (S.screen !== 'search') openSearch('');
    $('search-input').value = heard;
    $('search-clear').hidden = false;
    $('search-input').blur();
    onQuery(heard, { precise: true });
}

/** «Не то нашлось»: what was wanted, sent with the query and what was found. */
async function searchFeedback() {
    const wanted = await ask('Что вы искали?', 'Например: «дом 324А на Северной, у парка» или «нашёл не тот город». Это поможет сделать поиск точнее.', 'Отправить');
    if (wanted == null) return;
    const point = here() || map.center;
    try {
        await sendFeedback(platform.http, {
            query: S.query, wanted, town: S.resultsArea?.name || S.area?.name || '', scope: S.resultsScope || '',
            point: { lat: point[1], lon: point[0] }, version: platform.appInfo().version,
            results: (S.results || []).slice(0, 10).map((p) => [p.title, p.subtitle].filter(Boolean).join(', ')),
        });
        toast('Спасибо! Посмотрим и поправим поиск');
    } catch (e) {
        toast('Не отправилось: нет связи с сервером');
    }
}

const resultActions = {
    pick(el, e) {
        if (e.target.closest('[data-act="forget"]')) return;
        const d = store.data;
        const which = el.dataset.which;
        const place = which === 'home' ? d.home : which === 'work' ? d.work : which === 'results' ? S.results[+el.dataset.i] : d[which][+el.dataset.i];
        if (!place) return;
        $('search-input').blur();
        store.remember(place);
        openPlace(place, { fly: true });
    },
    forget(el) {
        store.forget(store.data.recent[+el.dataset.i]);
        renderResults();
        renderTop();
    },
    retry() {
        S.query = '';
        onQuery($('search-input').value, { everywhere: S.everywhere });
    },
    everywhere() {
        onQuery($('search-input').value, { everywhere: true });
    },
    precise() {
        S.query = '';
        onQuery($('search-input').value, { everywhere: S.everywhere, precise: true });
    },
    feedback() {
        searchFeedback();
    },
    local() {
        onQuery($('search-input').value);
    },
    async cat(el) {
        const cat = CATEGORIES.find((c) => c.id === el.dataset.id);
        $('search-input').value = cat.label;
        $('search-clear').hidden = false;
        $('search-input').blur();
        S.query = cat.label;
        S.resultsKind = cat.id;
        const seq = ++S.searchSeq;
        S.results = null;
        S.searching = true;
        S.searchError = '';
        renderResults();
        try {
            const found = await nearby(platform.http, here() || map.center, cat);
            if (seq !== S.searchSeq) return;
            S.results = found;
        } catch (e) {
            if (seq !== S.searchSeq) return;
            S.searchError = e.message || 'Поиск не удался';
        }
        S.searching = false;
        renderResults();
    },
    noHome() {
        toast('Найдите свой адрес, откройте его и выберите «Сделать домом»', 4500);
    },
    noWork() {
        toast('Найдите адрес работы, откройте его и выберите «Сделать работой»', 4500);
    },
};

// ---- A place ----

async function openPlace(place, { lookup = false, keepTitle = false, fly = true } = {}) {
    S.place = { ...place };
    S.preview = null;
    map.clearRoutes();
    map.setDestination([place.lon, place.lat]);
    setScreen('place');
    renderSheet();
    if (fly) {
        if (place.extent && place.extent.length === 4) {
            const [w, n, e, s] = place.extent;
            map.fitBounds([[w, s], [e, n]], mapPadding());
        } else {
            map.flyTo([place.lon, place.lat], 16);
        }
    }
    updateLocateButton();
    if (lookup) {
        S.placeLoading = true;
        renderSheet();
        const found = await reverse(platform.http, [place.lon, place.lat]);
        if (!S.place || S.place.id !== place.id) return;
        S.placeLoading = false;
        if (keepTitle) {
            const address = found.title !== 'Точка на карте' ? [found.title, found.subtitle].filter(Boolean).join(', ') : found.subtitle;
            S.place.subtitle = address;
        } else {
            Object.assign(S.place, { title: found.title, subtitle: found.subtitle, kind: found.kind });
            if (found.title === 'Точка на карте') S.place.subtitle = found.subtitle;
        }
        renderSheet();
    }
}

/** The place where the car was left, as a place to go to. */
function openParked() {
    const p = store.data.parked;
    if (!p) return;
    openPlace({ title: 'Моя машина', subtitle: `Оставлена в ${clockText(new Date(p.time))}`, kind: '', lon: p.lon, lat: p.lat, id: 'parked' }, { fly: true });
}

function closePlace() {
    S.place = null;
    S.preview = null;
    map.setDestination(null);
    map.clearRoutes();
    setScreen('browse');
}

function renderSheet() {
    const sheet = $('sheet');
    if (S.screen === 'nav' && S.report) renderReport(sheet);
    else if (S.screen === 'nav' && S.along) renderAlong(sheet);
    else if (S.hazard) renderHazard(sheet);
    else if (S.screen === 'preview') renderPreview(sheet);
    else if (S.place) renderPlace(sheet);
    requestAnimationFrame(layoutPanels);
}

/** How long ago [time] was, in words: «только что», «5 мин назад», «2 ч назад». */
function agoText(time) {
    const s = Math.max(0, (Date.now() - time) / 1000);
    if (s < 60) return 'только что';
    if (s < 3600) return `${Math.round(s / 60)} мин назад`;
    if (s < 86400) return `${Math.round(s / 3600)} ч назад`;
    return `${Math.round(s / 86400)} дн назад`;
}

function friendInitials(name) {
    const words = String(name || '').split(/\s+/).filter(Boolean);
    return ((words[0]?.[0] || '') + (words[1]?.[0] || '')).toUpperCase() || '?';
}

function renderPlace(sheet) {
    const p = S.place;
    const saved = store.isSaved(p);
    const sub = [p.kind, p.subtitle].filter(Boolean).join(' · ');
    const dist = distanceTo(p);
    const f = p.friend;
    const face = f ? `<div class="avatar" style="--c:${esc(f.color || '#2f6fed')}">${esc(friendInitials(f.name))}</div>` : '';
    const friendLine = f && f.place ? `<div class="sub">${f.place.battery != null ? `Заряд ${f.place.battery}%${f.place.charging ? ' ⚡' : ''} · ` : ''}обновлено ${agoText(f.place.at || f.place.time)}</div>` : '';
    sheet.innerHTML = `
        <div class="grip"></div>
        <div class="sheet-head">
            ${face}
            <div class="text">
                <h2>${esc(p.title)}</h2>
                <div class="sub">${S.placeLoading && !sub ? 'Узнаю адрес…' : esc(sub)}${dist ? `${sub ? ' · ' : ''}${esc(dist)}` : ''}</div>
                ${friendLine}
            </div>
            <button class="icon-btn" data-act="close" aria-label="Закрыть">${ICONS.close}</button>
        </div>
        <div class="actions">
            <button class="btn primary grow" data-act="route">${ICONS.route}Маршрут</button>
            <button class="ghost ${saved ? 'on' : ''}" data-act="save" aria-label="В избранное">${saved ? ICONS.starFilled : ICONS.star}</button>
            <button class="ghost" data-act="share" aria-label="Поделиться">${ICONS.share}</button>
            <button class="ghost" data-act="more" aria-label="Ещё">${ICONS.more}</button>
        </div>`;
}

const sheetActions = {
    close() {
        if (S.hazard) closeHazard();
        else if (S.screen === 'preview') backToPlace();
        else closePlace();
    },
    still() {
        voteOn(S.hazard, true);
        closeHazard();
    },
    gone() {
        voteOn(S.hazard, false);
        closeHazard();
    },
    sendReport(el) {
        reportHere(el.dataset.kind);
    },
    closeReport() {
        closeReport();
    },
    route() {
        openPreview(S.place);
    },
    save() {
        const on = store.toggleSaved(S.place);
        toast(on ? 'Сохранено в избранное' : 'Убрано из избранного');
        renderSheet();
        renderTop();
    },
    share() {
        const p = S.place;
        platform.share(`${p.title}${p.subtitle ? `, ${p.subtitle}` : ''}\nhttps://www.openstreetmap.org/?mlat=${p.lat.toFixed(6)}&mlon=${p.lon.toFixed(6)}#map=17/${p.lat.toFixed(6)}/${p.lon.toFixed(6)}`);
    },
    async more() {
        const p = S.place;
        const items = [
            ['home', ICONS.home, 'Сделать домом'],
            ['work', ICONS.work, 'Сделать работой'],
            ['copy', ICONS.copy, 'Скопировать координаты'],
        ];
        if (p.id === 'parked') items.push(['forgetCar', ICONS.trash, 'Забыть место машины']);
        const choice = await menu(items);
        if (choice === 'forgetCar') {
            store.setParked(null);
            closePlace();
            renderTop();
            return;
        }
        if (choice === 'home') {
            store.setHome(p);
            toast('Теперь это дом');
        } else if (choice === 'work') {
            store.setWork(p);
            toast('Теперь это работа');
        } else if (choice === 'copy') {
            platform.copy(`${p.lat.toFixed(6)}, ${p.lon.toFixed(6)}`);
            toast('Координаты скопированы');
        }
        renderTop();
    },
    mode(el) {
        store.set('mode', el.dataset.mode);
        if (S.preview) S.preview.mode = null;
        buildRoutes();
    },
    pickRoute(el) {
        selectRoute(+el.dataset.i);
    },
    avoid(el) {
        const key = el.dataset.key;
        store.set(key, !store.settings[key]);
        buildRoutes();
    },
    go() {
        startNav(false);
    },
    demo() {
        startNav(true);
    },
    retry() {
        buildRoutes();
    },
    steps() {
        const pv = S.preview;
        if (pv && pv.routes[pv.selected]) openSteps(pv.routes[pv.selected], 0, pv.dest);
    },
    // The "along the route" sheet while driving.
    alongCat(el) {
        searchAlong(CATEGORIES.find((c) => c.id === el.dataset.id));
    },
    addStop(el) {
        addStop(S.along.results[+el.dataset.i]);
    },
    dropStops() {
        dropStops();
    },
    closeAlong() {
        closeAlong();
    },
};

// ---- Warnings and reports on the map ----

/** The kinds of warning turned on in the settings. */
function enabledKinds() {
    const st = store.settings;
    const k = new Set();
    if (st.cameras) k.add('camera');
    if (st.bumps) {
        k.add('bump');
        k.add('rail');
    }
    if (st.schools) k.add('school');
    if (st.curves) k.add('curve');
    if (st.reports) for (const kind of ['crash', 'works', 'police', 'jam', 'danger', 'mobile']) k.add(kind);
    return k;
}

/** What drivers report, as the map's warnings: their camera is a "mobile" one. */
function reportHazard(r) {
    return { kind: r.kind === 'camera' ? 'mobile' : r.kind, id: `r${r.id}`, point: [r.lon, r.lat], report: r };
}

/** The warnings the map shows now: the route's while driving, reports around while looking. */
function shownHazards() {
    const kinds = enabledKinds();
    const list = S.nav ? S.nav.hazards : S.viewReports.map(reportHazard);
    return list.filter((h) => kinds.has(h.kind));
}

function showHazards() {
    map.setHazards(shownHazards());
}

/** Drivers' reports around what the map shows, looked for again after a minute or a move away. */
async function loadViewReports({ force = false } = {}) {
    if (S.nav || !store.settings.reports) return;
    if (map.zoom < 10) {
        if (S.viewReports.length) {
            S.viewReports = [];
            showHazards();
        }
        return;
    }
    const [w, s, e, n] = map.viewBox();
    const pad = 0.15;
    const box = [w - (e - w) * pad, s - (n - s) * pad, e + (e - w) * pad, n + (n - s) * pad];
    if (box[2] - box[0] > 3 || box[3] - box[1] > 3) return;
    const last = S.reportsAt;
    const inside = last && box[0] >= last.box[0] && box[1] >= last.box[1] && box[2] <= last.box[2] && box[3] <= last.box[3];
    if (!force && inside && Date.now() - last.time < 60000) return;
    // A little wider than the screen, so a small move needs no new look.
    const wide = [w - (e - w) * 0.6, s - (n - s) * 0.6, e + (e - w) * 0.6, n + (n - s) * 0.6];
    const ask = wide[2] - wide[0] <= 3 && wide[3] - wide[1] <= 3 ? wide : box;
    S.reportsAt = { box: ask, time: Date.now() };
    try {
        S.viewReports = await reportsIn(platform.http, ask);
        if (!S.nav) showHazards();
    } catch (e) {
        S.reportsAt = null;
    }
}

function openHazard(h) {
    S.hazard = h;
    if (S.screen === 'place') {
        S.place = null;
        map.setDestination(null);
        setScreen('browse');
    }
    $('sheet').hidden = false;
    renderSheet();
}

function closeHazard() {
    S.hazard = null;
    if (S.screen === 'nav' && (S.along || S.report)) {
        renderSheet();
        return;
    }
    $('sheet').hidden = !(S.screen === 'place' || S.screen === 'preview' || (S.screen === 'nav' && S.along));
    if (!$('sheet').hidden) renderSheet();
    requestAnimationFrame(layoutPanels);
}

function hazardTitle(h) {
    if (h.kind === 'curve') return `Крутой поворот ${h.side === 'left' ? 'налево' : 'направо'}`;
    if (h.kind === 'mobile') return 'Камера';
    return HAZARDS[h.kind]?.label || 'На дороге';
}

function badge(kind, size = '') {
    return `<span class="hz-badge ${size}" style="--c:${HAZARD_COLORS[kind] || '#e5a000'}">${ICONS[kind] || ICONS.warning}</span>`;
}

function renderHazard(sheet) {
    const h = S.hazard;
    const r = h.report;
    let sub;
    if (r) {
        const votes = r.votes > 1 ? ` · подтвердили ${r.votes}` : '';
        sub = `Отметили ${agoText(r.created)}${votes}`;
    } else if (h.kind === 'curve') {
        sub = 'По форме дороги на маршруте';
    } else {
        sub = `По данным OpenStreetMap${h.limit ? ` · ограничение ${h.limit}` : ''}`;
    }
    const ahead = S.nav && S.nav.progress && h.at != null ? Math.max(0, h.at - S.nav.progress.along) : null;
    const dist = ahead != null ? `${distanceText(ahead)} впереди` : distanceTo({ lon: h.point[0], lat: h.point[1] });
    sheet.innerHTML = `
        <div class="grip"></div>
        <div class="sheet-head">
            ${badge(h.kind, 'big')}
            <div class="text"><h2>${esc(hazardTitle(h))}</h2><div class="sub">${esc(sub)}${dist ? ` · ${esc(dist)}` : ''}</div></div>
            <button class="icon-btn" data-act="close" aria-label="Закрыть">${ICONS.close}</button>
        </div>
        ${r ? `<div class="actions vote">
            <button class="btn grow" data-act="still">${ICONS.thumbUp}Ещё там</button>
            <button class="btn grow" data-act="gone">${ICONS.thumbDown}Уже нет</button>
        </div>` : ''}`;
}

/** A driver's answer about a report: still there, or gone. */
async function voteOn(h, still) {
    if (!h || !h.report) return;
    try {
        const answer = await voteReport(platform.http, h.report.id, store.device, still);
        toast(still ? 'Спасибо, отметка подтверждена' : answer.removed ? 'Спасибо, отметка убрана' : 'Спасибо, учтём');
        const drop = (list) => list.filter((x) => (x.id ?? '') !== h.report.id && x.report?.id !== h.report.id);
        if (answer.removed) {
            S.viewReports = drop(S.viewReports);
            if (S.nav) S.nav.hazards = drop(S.nav.hazards);
            showHazards();
        }
    } catch (e) {
        toast('Не отправилось: нет связи с сервером');
    }
}

// ---- Friends from «Рядом» ----

/** Friends' places from the «Рядом» server, every half minute while the map is in sight. */
async function loadFriends() {
    const link = store.data.ryadom;
    if (!link || !store.settings.friends || document.hidden || S.screen === 'search') {
        if (S.friends.length && (!link || !store.settings.friends)) {
            S.friends = [];
            map.setFriends([]);
        }
        return;
    }
    try {
        const res = await platform.http(`${link.server}/api/friends`, { timeout: 12000, headers: { Authorization: `Bearer ${link.token}` } });
        if (res.status === 401) {
            store.put('ryadom', null);
            S.friends = [];
            map.setFriends([]);
            toast('«Рядом» отключён от Навигатора');
            return;
        }
        if (res.status !== 200) return;
        const view = JSON.parse(res.text);
        S.friends = (view.friends || []).filter((f) => f.place);
        map.setFriends(S.friends);
    } catch (e) {
        // Next time.
    }
}

function openFriend(f) {
    openPlace({ title: f.name, subtitle: '', kind: 'Друг из «Рядом»', lon: f.place.lon, lat: f.place.lat, id: `friend:${f.id}`, friend: f }, { lookup: true, keepTitle: true, fly: true });
}

// ---- Choosing a route ----

function openPreview(place) {
    S.place = { ...place };
    // To the parked car one usually walks: on foot for this route, unless changed on the sheet.
    S.preview = { dest: place, routes: [], selected: 0, loading: true, error: '', fromCenter: false, mode: place.id === 'parked' ? 'foot' : null };
    map.setDestination([place.lon, place.lat]);
    setScreen('preview');
    buildRoutes();
}

function backToPlace() {
    S.preview = null;
    map.clearRoutes();
    setScreen('place');
    renderSheet();
}

async function buildRoutes() {
    const pv = S.preview;
    if (!pv) return;
    const seq = ++S.routeSeq;
    const from = here() || map.center;
    pv.fromCenter = !here();
    pv.loading = true;
    pv.error = '';
    pv.routes = [];
    map.clearRoutes();
    renderSheet();
    const st = store.settings;
    try {
        const found = await findRoutes(platform.http, from, [pv.dest.lon, pv.dest.lat], pv.mode || st.mode, {
            avoidTolls: st.avoidTolls, avoidHighways: st.avoidHighways, avoidFerries: st.avoidFerries,
        });
        if (seq !== S.routeSeq || S.preview !== pv) return;
        pv.routes = found;
        pv.selected = 0;
        pv.loading = false;
        renderSheet();
        requestAnimationFrame(() => map.showRoutes(found, 0, mapPadding()));
    } catch (e) {
        if (seq !== S.routeSeq || S.preview !== pv) return;
        pv.loading = false;
        pv.error = e.message || 'Не удалось построить маршрут';
        renderSheet();
    }
}

function selectRoute(i) {
    S.preview.selected = i;
    map.selectRoute(i);
    renderSheet();
}

function renderPreview(sheet) {
    const pv = S.preview;
    const st = store.settings;
    const mode = pv.mode || st.mode;
    const modes = MODES.map(([m, label]) => `<button class="${mode === m ? 'on' : ''}" data-act="mode" data-mode="${m}">${ICONS[m]}${label}</button>`).join('');
    let body;
    if (pv.loading) {
        body = `<div class="loading"><span class="spinner"></span>Строю маршрут…</div>`;
    } else if (pv.error) {
        body = `<div class="error-box"><b>${esc(pv.error)}</b><div class="sub">Проверьте интернет или выберите другую точку.</div>
            <button class="btn small" data-act="retry">Повторить</button></div>`;
    } else {
        const cards = pv.routes.map((r, i) => {
            const via = r.via(1)[0];
            const tags = [r.toll ? 'Платная дорога' : '', r.ferry ? 'Паром' : ''].filter(Boolean).map((t) => `<span class="tag">${t}</span>`).join('');
            return `<button class="option ${i === pv.selected ? 'on' : ''}" data-act="pickRoute" data-i="${i}">
                <div class="time">${durationText(r.duration)}</div>
                <div class="dist">${distanceText(r.length)}</div>
                ${via ? `<div class="via">${ICONS.route}<span>${esc(via)}</span></div>` : ''}${tags}
            </button>`;
        }).join('');
        const avoid = mode === 'car' ? `<div class="avoid">
            <button class="toggle-chip ${st.avoidTolls ? 'on' : ''}" data-act="avoid" data-key="avoidTolls">${st.avoidTolls ? ICONS.check : ''}Без платных</button>
            <button class="toggle-chip ${st.avoidHighways ? 'on' : ''}" data-act="avoid" data-key="avoidHighways">${st.avoidHighways ? ICONS.check : ''}Без трасс</button>
            <button class="toggle-chip ${st.avoidFerries ? 'on' : ''}" data-act="avoid" data-key="avoidFerries">${st.avoidFerries ? ICONS.check : ''}Без паромов</button>
        </div>` : '';
        const note = pv.fromCenter ? `<div class="from-note">${ICONS.warning}Нет геопозиции: маршрут от центра карты</div>` : '';
        body = `<div class="options">${cards}</div>${avoid}${note}
            <button class="link" data-act="steps">${ICONS.list}Маршрут по шагам</button>
            <div class="actions">
                <button class="btn primary grow" data-act="go">${ICONS.navigate}Поехали</button>
                <button class="btn" data-act="demo">${ICONS.play}Демо</button>
            </div>`;
    }
    sheet.innerHTML = `
        <div class="grip"></div>
        <div class="sheet-head">
            <div class="text"><h2>${esc(pv.dest.title)}</h2>${pv.dest.subtitle ? `<div class="sub">${esc(pv.dest.subtitle)}</div>` : ''}</div>
            <button class="icon-btn" data-act="close" aria-label="Назад">${ICONS.close}</button>
        </div>
        <div class="modes">${modes}</div>
        ${body}`;
}

// ---- Driving ----

function say(text) {
    if (!text || !store.settings.voice || (S.nav && S.nav.muted)) return;
    platform.speak(text);
}

function startNav(demo) {
    const pv = S.preview;
    const route = pv && pv.routes[pv.selected];
    if (!route) return;
    if (!demo && !here()) toast('Жду сигнал GPS…');
    const st = store.settings;
    S.nav = {
        route,
        dest: pv.dest,
        tracker: new Tracker(route),
        voice: new Announcer(route, { streets: st.streetNames }),
        alerts: new RoadAlerts(),
        sim: demo ? new Simulator(route, { speedup: st.demoSpeed }) : null,
        progress: null,
        fix: null,
        lastFixAt: Date.now(),
        predicting: false,
        rerouting: false,
        lastReroute: 0,
        muted: false,
        arrived: false,
        stops: [],          // stops on the way, in order, for the route being driven
        stopsPassed: 0,     // how many of them are behind
        limits: [],
        hazards: [],        // everything to warn about ahead, by distance along the route
        mapped: [],         // from OpenStreetMap: cameras, bumps, crossings, schools
        curves: [],
        reports: [],        // drivers' reports placed on the route
        asked: new Set(),   // reports the driver was asked about
        limit: undefined,
        started: Date.now(),
        odometer: 0,        // metres actually driven
        share: null,        // {id, key, url, sent}: the drive shared by a link
    };
    S.preview = null;
    S.along = null;
    S.report = null;
    S.hazard = null;
    setScreen('nav');
    useRoute(S.nav, route);
    map.setDestination([S.nav.dest.lon, S.nav.dest.lat]);
    map.setNavZoom(null, route.mode);
    map.setMode('nav');
    platform.keepScreenOn(true);
    platform.setLocationMode('nav');
    renderNav();
    say(S.nav.voice.intro());
    if (demo) S.nav.sim.start((fix) => navUpdate(fix));
    else {
        if (S.fix && Date.now() - S.fixAt < 10000) navUpdate(S.fix);
        prefetchRoute(route);
    }
    updateLocateButton();
}

/** Drives [route] from now on: the map, the stops and what the road says about it. */
function useRoute(n, route) {
    n.route = route;
    n.tracker = new Tracker(route);
    n.voice = new Announcer(route, { streets: store.settings.streetNames });
    n.progress = null;
    n.limits = [];
    n.mapped = [];
    n.reports = [];
    n.curves = store.settings.curves ? findCurves(route) : [];
    n.limit = undefined;
    if (n.share) n.share.sent = 0;
    map.drive(route);
    mixHazards(n);
    n.stopsPassed = 0;
    map.setStops(n.stops.map((p) => [p.lon, p.lat]));
    loadRoadInfo(n, route);
    loadNavReports(n);
}

/** Everything to warn about on the route, in one list, and on the map. */
function mixHazards(n) {
    n.hazards = mergeHazards(n.mapped, n.curves, n.reports);
    if (S.nav === n) showHazards();
}

/**
 * Speed limits, and cameras, bumps, crossings and schools for a car route, fetched alongside;
 * the drive goes on without them.
 */
async function loadRoadInfo(n, route) {
    const st = store.settings;
    if (route.mode !== 'car') return;
    const kinds = { camera: st.cameras, bump: st.bumps, rail: st.bumps, school: st.schools };
    const [limits, mapped] = await Promise.all([
        st.cameras ? speedLimits(platform.http, route).catch(() => []) : [],
        findHazards(platform.http, route, kinds).catch(() => []),
    ]);
    if (S.nav !== n || n.route !== route) return;
    n.limits = limits;
    n.mapped = mapped;
    mixHazards(n);
    if (n.progress) renderNav();
}

/** Drivers' reports within some 50 km of the car, placed on the route ahead; again every two minutes. */
async function loadNavReports(n) {
    if (!store.settings.reports || n.route.mode === 'foot') return;
    const route = n.route;
    const from = n.progress ? n.progress.along : 0;
    const at = along(route.coords, route.cum, from).point;
    const box = [at[0] - 0.7, at[1] - 0.45, at[0] + 0.7, at[1] + 0.45];
    try {
        const found = await reportsIn(platform.http, box);
        if (S.nav !== n || n.route !== route) return;
        n.reports = placeReports(found, route, Math.max(0, from - 50));
        mixHazards(n);
    } catch (e) {
        // The reports already placed stay.
    }
}

function navUpdate(fix) {
    const n = S.nav;
    if (!n || n.arrived) return;
    const p = n.tracker.update(fix);
    if (!p) return;
    // The way actually driven, for the drive's history: good fixes only.
    if (n.fix && !n.sim && (fix.accuracy || 0) < 40) {
        const step = distance([n.fix.lon, n.fix.lat], [fix.lon, fix.lat]);
        if (step < 500) n.odometer += step;
    } else if (n.sim && n.progress) {
        n.odometer += Math.max(0, p.along - n.progress.along);
    }
    n.fix = fix;
    n.lastFixAt = Date.now();
    n.predicting = false;
    const onLine = !p.offRoute && p.offset < 45;
    const moving = fix.speed != null && fix.speed > 1;
    const heading = onLine ? p.heading : moving ? fix.heading : null;
    show(p, fix, onLine ? p.point : [fix.lon, fix.lat], heading);
    if (p.arrived) arrive();
    else if (p.offRoute) reroute(fix);
    renderNav();
}

/** Puts [p] on the map and the voice, for a real fix or a guessed one. */
function show(p, fix, point, heading) {
    const n = S.nav;
    n.progress = p;
    map.moveTo(fix, point, heading);
    map.setNavZoom(fix.speed, n.route.mode);
    map.setProgress(p.along);
    map.setManeuver(n.route.steps[p.next]);
    n.limit = limitAt(n.limits, p.along);
    const st = store.settings;
    say(n.voice.update(p, fix.speed) || n.alerts.update(p, fix.speed, n.hazards, n.limit, st.speedTolerance, { kinds: enabledKinds() }));
    askAboutReport(n, p);
    // Stops passed come off the map.
    const passed = n.route.stops.filter((s) => s.at < p.along - 25).length;
    if (passed !== n.stopsPassed) {
        n.stopsPassed = passed;
        map.setStops(n.stops.slice(passed).map((x) => [x.lon, x.lat]));
    }
}

/**
 * No fix for a few seconds while moving (a tunnel, a deep street): the car is carried on along
 * the route at its last speed for up to a minute, so the turns inside are still told.
 */
function deadReckon() {
    const n = S.nav;
    if (!n || n.sim || n.arrived || n.rerouting || !n.fix || !n.progress) return;
    const gap = (Date.now() - n.lastFixAt) / 1000;
    const v = n.fix.speed || 0;
    if (gap < 2.5 || gap > 60 || v < 4) {
        n.predicting = false;
        return;
    }
    const p = n.tracker.predict(v * gap);
    if (!p) return;
    n.predicting = true;
    const guess = { lon: p.point[0], lat: p.point[1], accuracy: 30, speed: v, heading: p.heading, time: Date.now() };
    show(p, guess, p.point, p.heading);
    renderNav();
}

/** Stops still ahead on the route being driven. */
function stopsLeft(n) {
    return n.stops.slice(n.stopsPassed);
}

/** A new route from where the car is: after leaving the route, or with stops changed ([force]). */
async function reroute(fix, { force = false, message = 'Маршрут перестроен.' } = {}) {
    const n = S.nav;
    if (!n || n.rerouting || (!force && Date.now() - n.lastReroute < 6000)) return false;
    n.rerouting = true;
    renderBanner();
    const st = store.settings;
    const stops = stopsLeft(n);
    let ok = false;
    try {
        const moving = fix.speed != null && fix.speed > 3 && fix.heading != null;
        const [route] = await findRoutes(platform.http, [fix.lon, fix.lat], [n.dest.lon, n.dest.lat], n.route.mode, {
            avoidTolls: st.avoidTolls, avoidHighways: st.avoidHighways, avoidFerries: st.avoidFerries,
            heading: moving ? fix.heading : null, alternates: 0, stops: stops.map((p) => [p.lon, p.lat]),
        });
        if (S.nav !== n) return false;
        n.stops = stops;
        useRoute(n, route);
        say(message);
        if (n.sim) {
            n.sim.stop();
            n.sim = new Simulator(route, { speedup: n.sim.speedup });
            n.sim.start((f) => navUpdate(f));
        } else if (n.fix) {
            navUpdate(n.fix);
        }
        ok = true;
    } catch (e) {
        if (S.nav === n) toast(e.kind === 'network' ? 'Нет интернета: не могу перестроить маршрут' : 'Не удалось перестроить маршрут');
    } finally {
        n.rerouting = false;
        n.lastReroute = Date.now();
        if (S.nav === n) renderNav();
    }
    return ok;
}

/** Where the car is for a new route: its last fix, or where it is drawn. */
function currentFix(n) {
    if (n.fix) return n.fix;
    const p = n.progress ? n.progress.point : n.route.coords[0];
    return { lon: p[0], lat: p[1], speed: null, heading: null, time: Date.now() };
}

function arrive() {
    const n = S.nav;
    n.arrived = true;
    n.arrivedAt = Date.now();
    if (n.sim) n.sim.stop();
    map.setManeuver(null);
    keepTrip(n, true);
    if (n.share) updateTrip(platform.http, n.share.id, n.share.key, { arrived: true, left: 0, remaining: 0, pos: { lat: n.dest.lat, lon: n.dest.lon } }).catch(() => {});
    $('prompt').hidden = true;
    // A real drive by car: remember where the car was left. Walking back to it, forget it.
    if (!n.sim && n.route.mode === 'car' && n.fix && n.dest.id !== 'parked') {
        store.setParked({ lon: n.fix.lon, lat: n.fix.lat, time: Date.now() });
    } else if (n.dest.id === 'parked' && n.route.mode !== 'car') {
        store.setParked(null);
    }
    renderNav();
}

async function confirmEnd() {
    const n = S.nav;
    if (!n) return;
    if (!n.arrived && !(await confirm('Завершить маршрут?', 'Ведение по маршруту остановится.', 'Завершить', true))) return;
    endNav();
}

function endNav() {
    const n = S.nav;
    if (!n) return;
    if (n.sim) n.sim.stop();
    if (!n.arrived) keepTrip(n, false);
    if (n.share && !n.arrived) endTrip(platform.http, n.share.id, n.share.key).catch(() => {});
    if (S.hud) closeHud();
    platform.stopSpeaking();
    platform.keepScreenOn(false);
    platform.setLocationMode('browse');
    S.nav = null;
    S.place = null;
    S.along = null;
    S.report = null;
    S.hazard = null;
    $('prompt').hidden = true;
    map.clearRoutes();
    map.setStops([]);
    map.setDestination(null);
    showHazards();
    loadViewReports({ force: true });
    setScreen('browse');
    renderTop();
    if (S.fix) {
        map.moveTo(S.fix, [S.fix.lon, S.fix.lat], null);
        map.setMode('follow');
    } else {
        map.hidePuck();
        map.setMode('free');
    }
    updateLocateButton();
}

function renderNav() {
    renderBanner();
    renderNavbar();
    renderSpeed();
    renderLanes();
    if (S.hud) renderHud();
    const n = S.nav;
    // The notification while the app is out of sight says the same as the banner.
    if (n && n.progress && !n.arrived) {
        const s = n.route.steps[n.progress.next];
        const eta = `Прибытие в ${clockText(new Date(Date.now() + n.progress.timeLeft * 1000))}`;
        const title = `${distanceText(n.progress.toNext)} · ${stepText(s)}`;
        const text = [bannerStreet(n, s), eta].filter(Boolean).join(' · ');
        if (title + text !== n.status) {
            n.status = title + text;
            platform.navStatus(title, text);
        }
    }
}

/** The speed, the limit beside it, and the nearest warning ahead with how far it is. */
function renderSpeed() {
    const n = S.nav;
    const box = $('speed');
    if (!n || n.arrived || !n.fix || n.fix.speed == null) {
        box.hidden = true;
        return;
    }
    const kmh = Math.round(n.fix.speed * 3.6);
    const limit = n.limit;
    const over = limit != null && kmh > limit + store.settings.speedTolerance;
    const h = n.progress ? hazardAhead(n.hazards, n.progress.along, 800, enabledKinds()) : null;
    // A row of bumps far apart from the speed matters less than a camera: cameras show from further.
    const chip = h && (h.kind !== 'bump' || h.at - n.progress.along < 300)
        ? `<button class="hz-chip" data-id="${esc(h.id)}" style="--c:${HAZARD_COLORS[h.kind]}">${badge(h.kind, 'small')}<span><b>${distanceText(Math.max(0, h.at - n.progress.along))}</b>${h.report || h.kind === 'school' || h.kind === 'rail' || h.kind === 'curve' ? `<small>${esc(hazardTitle(h))}</small>` : ''}</span></button>` : '';
    box.hidden = false;
    box.innerHTML = `<div class="speed ${over ? 'over' : ''}"><b>${kmh}</b><small>км/ч</small></div>
        ${limit != null ? `<div class="limit"><b>${limit}</b></div>` : ''}${chip}`;
}

function bannerStreet(n, s) {
    if (s.type === 'arrive') return n.dest.title;
    if (s.type === 'waypoint') {
        const stop = n.stops[n.route.stops.indexOf(s)];
        return stop ? stop.title : '';
    }
    return s.street;
}

/** "700 м" as a big number and a small unit. */
function bigDistance(m) {
    const [num, unit] = distanceText(m).split(' ');
    return `${num}<small>${unit}</small>`;
}

function renderBanner() {
    const n = S.nav;
    const b = $('banner');
    if (!n) return;
    const p = n.progress;
    const weak = !n.sim && !n.arrived && Date.now() - n.lastFixAt > 7000;
    const warn = n.predicting ? `<div class="warn">${ICONS.gps}Нет сигнала GPS — веду по маршруту</div>`
        : weak ? `<div class="warn">${ICONS.gps}Слабый сигнал GPS</div>` : '';
    b.classList.toggle('soon', false);
    if (n.arrived) {
        b.innerHTML = `<div class="status">${ICONS.flag}Вы на месте</div>`;
    } else if (n.rerouting) {
        b.innerHTML = `<div class="status"><span class="spinner"></span>Строю маршрут…</div>`;
    } else if (!p) {
        b.innerHTML = `<div class="status">${ICONS.gps}Ищу спутники…</div>${warn}`;
    } else {
        const r = n.route;
        const s = r.steps[p.next];
        const street = bannerStreet(n, s);
        const k = r.nextSpoken(p.next);
        const then = k !== p.next && r.steps[k].at - s.at < (r.mode === 'car' ? 250 : 60)
            ? `<div class="then">Затем <span class="then-ico">${maneuverIcon(r.steps[k])}</span>${esc(stepText(r.steps[k]))}</div>` : '';
        // A bar that fills as the manoeuvre comes, over its last few hundred metres.
        const span = r.mode === 'car' ? 400 : 120;
        const fill = Math.max(0, Math.min(1, 1 - p.toNext / span));
        b.classList.toggle('soon', p.toNext < (r.mode === 'car' ? 120 : 30));
        b.innerHTML = `<div class="main">
                <div class="arrow">${maneuverIcon(s)}</div>
                <div class="what">
                    <div class="dist">${bigDistance(p.toNext)}</div>
                    ${street ? `<div class="street">${esc(street)}</div><div class="act">${esc(stepText(s))}</div>` : `<div class="street">${esc(stepText(s))}</div>`}
                    ${s.toward ? `<div class="toward">→ ${esc(s.toward)}</div>` : ''}
                </div>
            </div><div class="approach"><i style="transform:scaleX(${fill.toFixed(3)})"></i></div>${then}${warn}`;
    }
    positionLanes();
}

function renderLanes() {
    const n = S.nav;
    const box = $('lanes');
    const p = n && n.progress;
    const s = p && n.route.steps[p.next];
    if (!s || !s.lanes || n.arrived || n.rerouting || p.toNext > 900 || s.lanes.length < 2) {
        box.hidden = true;
        return;
    }
    const wanted = [s.modifier];
    box.innerHTML = s.lanes.map((l) => `<div class="lane">${laneIcon(l, wanted)}</div>`).join('');
    box.hidden = false;
    positionLanes();
}

function positionLanes() {
    const box = $('lanes');
    if ($('banner').hidden) return;
    const r = $('banner').getBoundingClientRect();
    if (!box.hidden) box.style.top = `${r.bottom - 2}px`;
    // What sits under the banner (the question about a report) goes below it and the lanes.
    const below = box.hidden ? r.bottom : box.getBoundingClientRect().bottom;
    document.documentElement.style.setProperty('--below-banner', `${Math.round(below + 10)}px`);
}

function renderNavbar() {
    const n = S.nav;
    const bar = $('navbar');
    if (!n) return;
    $('report-btn').hidden = n.arrived;
    if (n.arrived) {
        const parked = store.data.parked && !n.sim && n.route.mode === 'car';
        const took = Math.max(60, ((n.arrivedAt || Date.now()) - n.started) / 1000);
        bar.innerHTML = `<div class="arrived">
            <div class="flag">${ICONS.flag}</div>
            <h2>${esc(n.dest.title)}</h2>
            ${n.dest.subtitle ? `<div class="sub">${esc(n.dest.subtitle)}</div>` : ''}
            <div class="trip-sum"><div><b>${durationText(took)}</b><small>в пути</small></div><div><b>${distanceText(Math.max(n.odometer, 0))}</b><small>проехали</small></div><div><b>${clockText(new Date(n.arrivedAt || Date.now()))}</b><small>прибытие</small></div></div>
            ${parked ? `<div class="sub hint">${ICONS.car}Место машины запомнено: кнопка «Машина» на карте</div>` : ''}
            <button class="btn primary" data-act="done">Готово</button>
        </div>`;
        requestAnimationFrame(layoutPanels);
        return;
    }
    const p = n.progress;
    const left = p ? p.timeLeft : n.route.duration;
    const dist = p ? p.remaining : n.route.length;
    const done = p ? Math.max(0, Math.min(1, p.along / n.route.length)) : 0;
    const eta = new Date(Date.now() + left * 1000);
    const demo = n.sim ? `<button class="demo-badge" data-act="demoSpeed">${ICONS.play}Демо ×${n.sim.speedup}</button>` : '';
    const shared = n.share ? `<span class="live" title="Поездкой делятся">●</span>` : '';
    bar.innerHTML = `${demo}<div class="trip-line"><i style="transform:scaleX(${done.toFixed(4)})"></i></div>
        <button class="round end" data-act="end" aria-label="Завершить">${ICONS.close}</button>
        <button class="eta" data-act="more"><div class="clock">${clockText(eta)}${shared}</div><div class="left">${durationText(left)} · ${distanceText(dist)}</div></button>
        <button class="round" data-act="along" aria-label="По пути">${ICONS.search}</button>
        <button class="round" data-act="overview" aria-label="Весь маршрут">${ICONS.overview}</button>
        <button class="round" data-act="more" aria-label="Ещё">${ICONS.more}</button>`;
    requestAnimationFrame(layoutPanels);
}

const navActions = {
    end: confirmEnd,
    done: endNav,
    overview() {
        map.overview(mapPadding());
        $('resume-btn').hidden = false;
        updateLocateButton();
    },
    along() {
        S.report = null;
        openAlong();
    },
    async more() {
        const n = S.nav;
        const items = [
            ['share', ICONS.share, n.share ? 'Ссылка на поездку' : 'Поделиться поездкой'],
            ['mute', n.muted ? ICONS.soundOn : ICONS.soundOff, n.muted ? 'Включить голос' : 'Выключить голос'],
            ['hud', ICONS.hud, 'Режим HUD (на лобовое стекло)'],
            ['steps', ICONS.list, 'Маршрут по шагам'],
            ['settings', ICONS.settings, 'Настройки'],
        ];
        if (n.share) items.splice(1, 0, ['unshare', ICONS.close, 'Перестать делиться']);
        const choice = await menu(items);
        if (!S.nav || S.nav !== n) return;
        if (choice === 'mute') {
            n.muted = !n.muted;
            if (n.muted) platform.stopSpeaking();
            toast(n.muted ? 'Голос выключен' : 'Голос включён');
        } else if (choice === 'share') {
            shareDrive(n);
        } else if (choice === 'unshare') {
            endTrip(platform.http, n.share.id, n.share.key).catch(() => {});
            n.share = null;
            renderNavbar();
            toast('Поездку больше не видно по ссылке');
        } else if (choice === 'hud') {
            openHud();
        } else if (choice === 'steps') {
            openSteps(n.route, n.progress ? n.progress.along : 0, n.dest);
        } else if (choice === 'settings') {
            openSettings();
        }
    },
    demoSpeed() {
        const n = S.nav;
        const next = DEMO_SPEEDS[(DEMO_SPEEDS.indexOf(n.sim.speedup) + 1) % DEMO_SPEEDS.length];
        n.sim.speedup = next;
        store.set('demoSpeed', next);
        renderNavbar();
    },
};

// ---- Sharing a drive: a link that shows the car and when it gets there ----

/** What the link's page needs now: where the car is, what is left, the road ahead now and then. */
function tripNews(n, withLine) {
    const p = n.progress;
    const pos = p ? p.point : n.fix ? [n.fix.lon, n.fix.lat] : n.route.coords[0];
    const body = {
        pos: { lon: pos[0], lat: pos[1] },
        left: Math.round(p ? p.timeLeft : n.route.duration),
        remaining: Math.round(p ? p.remaining : n.route.length),
        speed: n.fix && n.fix.speed != null ? Math.round(n.fix.speed * 3.6) : null,
    };
    if (withLine) body.line = thinLine(slice(n.route.coords, n.route.cum, p ? p.along : 0, n.route.length), 300);
    return body;
}

async function shareDrive(n) {
    if (n.share) {
        platform.share(shareText(n));
        return;
    }
    try {
        const made = await shareTrip(platform.http, { dest: { title: n.dest.title, lat: n.dest.lat, lon: n.dest.lon }, mode: n.route.mode, ...tripNews(n, true) });
        if (S.nav !== n) return;
        n.share = { ...made, sent: Date.now() };
        renderNavbar();
        platform.share(shareText(n));
    } catch (e) {
        // No server: the arrival time as words, as before.
        const left = n.progress ? n.progress.timeLeft : n.route.duration;
        const d = n.dest;
        platform.share(`Еду в «${d.title}», буду в ${clockText(new Date(Date.now() + left * 1000))} (через ${durationText(left)}).\n` +
            `https://www.openstreetmap.org/?mlat=${d.lat.toFixed(6)}&mlon=${d.lon.toFixed(6)}#map=17/${d.lat.toFixed(6)}/${d.lon.toFixed(6)}`);
    }
}

function shareText(n) {
    const left = n.progress ? n.progress.timeLeft : n.route.duration;
    return `Еду в «${n.dest.title}», буду в ${clockText(new Date(Date.now() + left * 1000))}. Где я сейчас — по ссылке:\n${n.share.url}`;
}

/** Every 20 seconds while shared; the road ahead with every fifth, and after a new route. */
function sendTripNews(n) {
    if (!n.share || n.arrived) return;
    n.share.count = (n.share.count || 0) + 1;
    const withLine = !n.share.sent || n.share.count % 5 === 0;
    n.share.sent = n.share.sent || Date.now();
    const share = n.share;
    updateTrip(platform.http, share.id, share.key, tripNews(n, withLine)).then(() => {
        share.fails = 0;
    }, (e) => {
        // The server keeps saying no (the link ended elsewhere, or a day passed): stop sending.
        share.fails = (share.fails || 0) + (e && e.kind === 'server' ? 1 : 0);
        if (share.fails >= 5 && S.nav === n && n.share === share) {
            n.share = null;
            renderNavbar();
        }
    });
}

// ---- Reporting what is on the road ----

const REPORT_LABELS = { crash: 'ДТП', police: 'ДПС', works: 'Ремонт', camera: 'Камера', jam: 'Пробка', danger: 'Опасность' };

function openReport() {
    if (!S.nav) return;
    S.report = true;
    S.along = null;
    S.hazard = null;
    $('sheet').hidden = false;
    renderSheet();
}

function closeReport() {
    S.report = null;
    $('sheet').hidden = !S.along;
    if (S.along) renderSheet();
    requestAnimationFrame(layoutPanels);
}

function renderReport(sheet) {
    const kinds = REPORT_KINDS.map((k) => {
        const shown = k === 'camera' ? 'mobile' : k;
        return `<button class="report-kind" data-act="sendReport" data-kind="${k}" style="--c:${HAZARD_COLORS[shown]}"><span class="hz-badge big">${ICONS[shown]}</span><span>${REPORT_LABELS[k]}</span></button>`;
    }).join('');
    sheet.innerHTML = `
        <div class="grip"></div>
        <div class="sheet-head">
            <div class="text"><h2>Что на дороге?</h2><div class="sub">Отметка появится у всех водителей здесь</div></div>
            <button class="icon-btn" data-act="closeReport" aria-label="Закрыть">${ICONS.close}</button>
        </div>
        <div class="report-grid">${kinds}</div>`;
}

/** Sends a report of [kind] where the car is now, going the way it goes. */
async function reportHere(kind) {
    const n = S.nav;
    closeReport();
    if (!n) return;
    const fix = n.fix || S.fix;
    const point = n.progress ? n.progress.point : fix ? [fix.lon, fix.lat] : null;
    if (!point) {
        toast('Жду сигнал GPS, чтобы поставить отметку');
        return;
    }
    const heading = n.progress ? n.progress.heading : fix && fix.heading;
    platform.vibrate(20);
    try {
        const { confirmed } = await sendReport(platform.http, { kind, point, heading, device: store.device });
        toast(confirmed ? 'Спасибо! Вы подтвердили отметку другого водителя' : 'Спасибо! Отметку увидят другие водители');
    } catch (e) {
        toast(e.message || 'Не отправилось: нет связи с сервером');
    }
}

/** Just past a report from someone else: is it still there? Asked once, for a few seconds. */
function askAboutReport(n, p) {
    if (!store.settings.reports || n.sim) return;
    for (const h of n.hazards) {
        if (!h.report || n.asked.has(h.id)) continue;
        if (h.at > p.along) break;
        if (p.along - h.at < 15 || p.along - h.at > 250) continue;
        n.asked.add(h.id);
        // Not about one made a minute ago: that is likely this very driver's.
        if (Date.now() - h.report.created < 90 * 1000) continue;
        showPrompt(h);
        return;
    }
}

let promptTimer = null;
function showPrompt(h) {
    const box = $('prompt');
    box.innerHTML = `${badge(h.kind)}<div class="q">${esc(hazardTitle(h))} ещё там?</div>
        <button class="btn small" data-act="no">Нет</button><button class="btn small primary" data-act="yes">Да</button><i class="timer"></i>`;
    box.dataset.id = h.id;
    box.hidden = false;
    clearTimeout(promptTimer);
    promptTimer = setTimeout(() => {
        box.hidden = true;
    }, 15000);
}

const promptActions = {
    yes() {
        answerPrompt(true);
    },
    no() {
        answerPrompt(false);
    },
};

function answerPrompt(still) {
    const box = $('prompt');
    box.hidden = true;
    clearTimeout(promptTimer);
    const h = S.nav && S.nav.hazards.find((x) => x.id === box.dataset.id);
    if (h) voteOn(h, still);
}

// ---- The windscreen view (HUD): big, bright, mirrored to be read in the glass ----

function openHud() {
    S.hud = true;
    $('hud').hidden = false;
    platform.hud(true);
    renderHud();
}

function closeHud() {
    S.hud = false;
    $('hud').hidden = true;
    platform.hud(false);
}

function renderHud() {
    const n = S.nav;
    const box = $('hud');
    if (!n) return;
    const p = n.progress;
    const s = p && n.route.steps[p.next];
    const kmh = n.fix && n.fix.speed != null ? Math.round(n.fix.speed * 3.6) : null;
    const over = n.limit != null && kmh != null && kmh > n.limit + store.settings.speedTolerance;
    const eta = p ? clockText(new Date(Date.now() + p.timeLeft * 1000)) : '';
    box.classList.toggle('mirror', store.settings.hudMirror);
    box.innerHTML = `<div class="hud-view">
            ${n.arrived ? `<div class="hud-arrow">${ICONS.flag}</div><div class="hud-dist">На месте</div>`
        : s ? `<div class="hud-arrow">${maneuverIcon(s)}</div><div class="hud-dist">${bigDistance(p.toNext)}</div><div class="hud-street">${esc(bannerStreet(n, s) || stepText(s))}</div>`
            : '<div class="hud-dist">…</div>'}
            <div class="hud-foot"><span class="${over ? 'over' : ''}">${kmh ?? '—'}<small>км/ч</small></span><span>${eta}<small>прибытие</small></span></div>
        </div>
        <div class="hud-tools"><button class="btn small" data-act="mirror">${ICONS.swap}${store.settings.hudMirror ? 'Без зеркала' : 'Зеркально'}</button><button class="btn small" data-act="exit">${ICONS.close}Выйти</button></div>`;
}

const hudActions = {
    mirror() {
        store.set('hudMirror', !store.settings.hudMirror);
        renderHud();
    },
    exit() {
        closeHud();
    },
};

// ---- Past drives ----

/** A real drive goes into the history: where to, when, how long and how far. */
function keepTrip(n, arrived) {
    if (n.sim || n.kept || n.odometer < 300) return;
    n.kept = true;
    store.addTrip({ dest: n.dest, start: n.started, end: Date.now(), length: Math.round(n.odometer), mode: n.route.mode, arrived });
}

// ---- The map along the route, fetched before the drive ----

/** Before a drive, the scheme along the route comes onto the phone, as the settings allow. */
function prefetchRoute(route) {
    const how = store.settings.prefetch;
    if (how === 'off' || route.length < 3000) return;
    const net = platform.network();
    if (!net.online || (how === 'wifi' && net.metered)) return;
    const { tiles } = map.sources();
    if (!tiles) return;
    // Long drives: the near zoom along the first 300 km only, the rest from further out.
    const zooms = route.length > 300000 ? [8, 10, 12] : [10, 12, 14];
    const list = tilesAlong(route, zooms, 1).slice(0, 2500);
    platform.prefetch('route', tileUrls(tiles, list), false);
}

function onDownload(tag, done, total, failed) {
    S.downloads[tag] = { ...(S.downloads[tag] || {}), done, total, failed };
    if (tag === 'route') return;
    const d = S.downloads[tag];
    if (done + failed >= total) {
        const kept = store.data.offline.map((a) => (a.tag === tag ? { ...a, ready: true, failed, at: Date.now() } : a));
        store.put('offline', kept);
        delete S.downloads[tag];
        toast(failed > total * 0.1 ? `Карта «${d.name || 'района'}» скачана не вся: нет связи. Повторите позже` : `Карта «${d.name || 'района'}» сохранена на телефоне`, 4000);
    }
    if (!$('settings').hidden) renderSettings();
}

// ---- Along the route: places to stop at on the way ----

function openAlong() {
    S.along = { cat: null, results: [], loading: false, error: '' };
    S.hazard = null;
    $('sheet').hidden = false;
    renderSheet();
}

function closeAlong() {
    S.along = null;
    $('sheet').hidden = true;
    requestAnimationFrame(layoutPanels);
}

async function searchAlong(cat) {
    const n = S.nav;
    const a = S.along;
    if (!n || !a) return;
    a.cat = cat;
    a.loading = true;
    a.error = '';
    a.results = [];
    renderSheet();
    try {
        const found = await alongRoute(platform.http, n.route, n.progress ? n.progress.along : 0, cat);
        if (S.along !== a) return;
        a.results = found;
    } catch (e) {
        if (S.along !== a) return;
        a.error = e.message || 'Поиск не удался';
    }
    a.loading = false;
    renderSheet();
}

async function addStop(place) {
    const n = S.nav;
    if (!n || !place) return;
    closeAlong();
    // The new stop comes first: it is the nearest thing ahead.
    const before = { stops: n.stops, passed: n.stopsPassed };
    n.stops = [place, ...stopsLeft(n)];
    n.stopsPassed = 0;
    const ok = await reroute(currentFix(n), { force: true, message: 'Заезд добавлен.' });
    if (!ok && S.nav === n) {
        n.stops = before.stops;
        n.stopsPassed = before.passed;
    }
}

async function dropStops() {
    const n = S.nav;
    if (!n) return;
    closeAlong();
    n.stops = [];
    n.stopsPassed = 0;
    await reroute(currentFix(n), { force: true, message: 'Заезды убраны.' });
}

function renderAlong(sheet) {
    const a = S.along;
    const n = S.nav;
    const cats = CATEGORIES.map((c) => `<button class="cat ${a.cat === c ? 'on' : ''}" data-act="alongCat" data-id="${c.id}">${ICONS[c.id]}<span>${c.label}</span></button>`).join('');
    let body = '';
    if (a.loading) {
        body = `<div class="loading"><span class="spinner"></span>Ищу по пути…</div>`;
    } else if (a.error) {
        body = `<div class="error-box"><b>${esc(a.error)}</b></div>`;
    } else if (a.cat && !a.results.length) {
        body = `<div class="empty" style="padding:22px 8px">Впереди ничего не нашлось.</div>`;
    } else if (a.results.length) {
        body = `<div class="along-list">${a.results.map((p, i) => `<button class="row" data-act="addStop" data-i="${i}">
            <span class="ico">${ICONS[a.cat.id]}</span>
            <span class="text"><div class="title">${esc(p.title)}</div><div class="sub">${esc([p.subtitle].filter(Boolean).join(''))}</div></span>
            <span class="side"><b>${distanceText(p.ahead)}</b><br>${p.off > 60 ? `${distanceText(p.off)} в сторону` : 'у дороги'}</span>
        </button>`).join('')}</div>`;
    } else {
        body = `<div class="empty" style="padding:18px 8px">Выберите, что найти впереди по маршруту</div>`;
    }
    const left = stopsLeft(n);
    const stops = left.length ? `<div class="stops-line">${ICONS.stop}<span>Заезды: ${esc(left.map((p) => p.title).join(', '))}</span>
        <button class="btn small" data-act="dropStops">Убрать</button></div>` : '';
    sheet.innerHTML = `
        <div class="grip"></div>
        <div class="sheet-head">
            <div class="text"><h2>По пути</h2></div>
            <button class="icon-btn" data-act="closeAlong" aria-label="Закрыть">${ICONS.close}</button>
        </div>
        ${stops}
        <div class="cats">${cats}</div>
        ${body}`;
}

// ---- The route step by step ----

function openSteps(route, from, dest) {
    const rows = [];
    let first = true;
    route.steps.forEach((s, i) => {
        if (s.quiet || s.at < from - 5) return;
        const next = route.steps[i + 1];
        const street = s.type === 'arrive' ? dest.title : s.street;
        const ahead = s.at - from;
        rows.push(`<div class="row step-row">
            <span class="ico step-ico">${maneuverIcon(s)}</span>
            <span class="text"><div class="title">${esc(stepText(s))}</div>${street ? `<div class="sub">${esc(street)}</div>` : ''}</span>
            <span class="side">${first && from > 0 ? 'сейчас' : ahead > 0 ? distanceText(ahead) : 'старт'}${next && s.type !== 'arrive' ? `<br><small>дальше ${distanceText(s.length)}</small>` : ''}</span>
        </div>`);
        first = false;
    });
    const page = $('steps');
    page.innerHTML = `
        <div class="page-head">
            <button class="icon-btn" data-act="closeSteps" aria-label="Назад">${ICONS.back}</button>
            <h1>Маршрут по шагам</h1>
        </div>
        <div class="page-body">
            <div class="steps-sum">${durationText(route.timeLeft(from))} · ${distanceText(route.length - from)}</div>
            ${rows.join('')}
        </div>`;
    page.hidden = false;
}

function closeSteps() {
    $('steps').hidden = true;
}

// ---- A drive offered on the map: «На работу · 18 мин» ----

/** Looks again for a drive to offer, at most every ten minutes or after moving half a kilometre. */
async function refreshSuggestion() {
    const at = here();
    if (!at || S.nav) return;
    if (S.suggestHidden && Date.now() - S.suggestHidden < 3 * 3600 * 1000) return;
    const last = S.suggestAt;
    if (last && Date.now() - last.time < 10 * 60 * 1000 && distance(last.point, at) < 500) return;
    S.suggestAt = { time: Date.now(), point: at };
    const d = store.data;
    const offer = suggestion({ here: at, home: d.home, work: d.work, trips: d.trips });
    if (!offer) {
        S.suggest = null;
        renderSuggestion();
        return;
    }
    S.suggest = offer;
    renderSuggestion();
    try {
        const [route] = await findRoutes(platform.http, at, [offer.place.lon, offer.place.lat], 'car', {
            avoidTolls: store.settings.avoidTolls, avoidHighways: store.settings.avoidHighways, avoidFerries: store.settings.avoidFerries, alternates: 0,
        });
        if (S.suggest !== offer) return;
        offer.route = route;
        renderSuggestion();
    } catch (e) {
        // Offered without the time.
    }
}

function renderSuggestion() {
    const card = $('suggest');
    const offer = S.suggest;
    const show = Boolean(offer) && S.screen === 'browse' && !S.hazard;
    if (!show) {
        if (!card.hidden) {
            card.hidden = true;
            requestAnimationFrame(layoutPanels);
        }
        return;
    }
    const title = offer.to === 'work' ? 'На работу' : offer.to === 'home' ? 'Домой' : offer.place.title;
    const icon = offer.to === 'work' ? ICONS.work : offer.to === 'home' ? ICONS.home : ICONS.history;
    const r = offer.route;
    const eta = r ? `${durationText(r.duration)} · ${distanceText(r.length)} · будете в ${clockText(new Date(Date.now() + r.duration * 1000))}` : 'Строю маршрут…';
    card.innerHTML = `<span class="suggest-ico">${icon}</span><span class="text"><b>${esc(title)}</b><small>${esc(eta)}</small></span>
        <span class="go">${ICONS.navigate}</span><span class="x" data-act="hideSuggest" aria-label="Скрыть">${ICONS.close}</span>`;
    const was = card.hidden;
    card.hidden = false;
    if (was) requestAnimationFrame(layoutPanels);
}

// ---- Past drives ----

function openHistory() {
    renderHistory();
    $('history').hidden = false;
}

function closeHistory() {
    $('history').hidden = true;
}

function dayText(time) {
    const d = new Date(time);
    const today = new Date();
    const yesterday = new Date(Date.now() - 86400000);
    const same = (a, b) => a.toDateString() === b.toDateString();
    if (same(d, today)) return 'Сегодня';
    if (same(d, yesterday)) return 'Вчера';
    const months = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
    return `${d.getDate()} ${months[d.getMonth()]}${d.getFullYear() !== today.getFullYear() ? ` ${d.getFullYear()}` : ''}`;
}

function renderHistory() {
    const trips = store.data.trips;
    const rows = [];
    let day = '';
    let total = 0;
    trips.forEach((t, i) => {
        total += t.length || 0;
        const label = dayText(t.start);
        if (label !== day) {
            day = label;
            rows.push(`<div class="group-title">${esc(label)}</div>`);
        }
        const took = Math.max(60, (t.end - t.start) / 1000);
        rows.push(`<button class="row" data-act="again" data-i="${i}">
            <span class="ico">${ICONS[t.mode] || ICONS.car}</span>
            <span class="text"><div class="title">${esc(t.dest.title)}</div><div class="sub">${clockText(new Date(t.start))}–${clockText(new Date(t.end))} · ${durationText(took)}${t.arrived === false ? ' · не доехали' : ''}</div></span>
            <span class="side"><b>${distanceText(t.length || 0)}</b></span>
        </button>`);
    });
    $('history').innerHTML = `
        <div class="page-head">
            <button class="icon-btn" data-act="closeHistory" aria-label="Назад">${ICONS.back}</button>
            <h1>Поездки</h1>
            ${trips.length ? `<button class="icon-btn" data-act="clearHistory" aria-label="Очистить">${ICONS.trash}</button>` : ''}
        </div>
        <div class="page-body">
            ${trips.length ? `<div class="stats"><div><b>${trips.length}</b><small>${plural(trips.length, ['поездка', 'поездки', 'поездок'])}</small></div><div><b>${distanceText(total)}</b><small>всего</small></div></div>${rows.join('')}`
        : `<div class="empty">${ICONS.history}Здесь будут ваши поездки: куда, когда и сколько ехали.<br>Нажмите на поездку, чтобы поехать туда снова.</div>`}
        </div>`;
}

const historyActions = {
    closeHistory,
    again(el) {
        const t = store.data.trips[+el.dataset.i];
        if (!t) return;
        closeHistory();
        closeSettings();
        if (S.nav) {
            toast('Сначала завершите текущий маршрут');
            return;
        }
        openPreview(t.dest);
    },
    async clearHistory() {
        if (!(await confirm('Очистить историю поездок?', 'Список поездок удалится с телефона.', 'Очистить', true))) return;
        store.put('trips', []);
        renderHistory();
    },
};

// ---- Settings ----

function openSettings() {
    renderSettings();
    $('settings').hidden = false;
}

function closeSettings() {
    $('settings').hidden = true;
}

/** The map of the town around, for the road: about how many tiles and megabytes. */
function townDownload() {
    const area = S.area;
    if (!area || !area.bbox) return null;
    const counts = countByZoom(area.bbox, 0, MAX_ZOOM);
    const tiles = Object.values(counts).reduce((a, b) => a + b, 0);
    return { name: area.name || 'Район вокруг', bbox: area.bbox, tiles, mb: estimateMb(counts) };
}

function renderSettings() {
    const st = store.settings;
    const d = store.data;
    const info = platform.appInfo();
    const sw = (key, title, sub = '', icon = '') => `<button class="setting" data-act="toggle" data-key="${key}">
        ${icon ? `<span class="set-ico">${icon}</span>` : ''}<span class="text"><div class="title">${title}</div>${sub ? `<div class="sub">${sub}</div>` : ''}</span>
        <span class="switch ${st[key] ? 'on' : ''}"></span></button>`;
    const seg = (key, options) => `<div class="segmented">${options.map(([v, label]) =>
        `<button class="${st[key] === v ? 'on' : ''}" data-act="pick" data-key="${key}" data-value="${v}">${label}</button>`).join('')}</div>`;
    const placeLine = (which, label, icon) => {
        const p = d[which];
        return `<div class="setting static"><span class="set-ico">${icon}</span>
            <span class="text"><div class="title">${label}</div><div class="sub">${p ? esc(p.title) : 'Не задан'}</div></span>
            ${p ? `<button class="side-btn" data-act="clearPlace" data-which="${which}" aria-label="Убрать">${ICONS.trash}</button>` : ''}</div>`;
    };
    const hz = (kind) => badge(kind, 'small');
    const kept = platform.storage();
    const town = townDownload();
    const offline = d.offline.map((a, i) => {
        const run = S.downloads[a.tag];
        const state = run ? `Скачиваю… ${Math.round(((run.done + run.failed) / Math.max(1, run.total)) * 100)}%` : a.ready ? `${a.mb} МБ · ${dayText(a.at).toLowerCase()}` : 'Не докачано';
        return `<div class="setting static"><span class="set-ico">${ICONS.download}</span>
            <span class="text"><div class="title">${esc(a.name)}</div><div class="sub">${state}</div>${run ? `<div class="bar"><i style="transform:scaleX(${((run.done + run.failed) / Math.max(1, run.total)).toFixed(3)})"></i></div>` : ''}</span>
            <button class="side-btn" data-act="${run ? 'cancelArea' : 'dropArea'}" data-i="${i}" aria-label="Убрать">${run ? ICONS.close : ICONS.trash}</button></div>`;
    }).join('');
    const ryadom = d.ryadom;
    $('settings').innerHTML = `
        <div class="page-head">
            <button class="icon-btn" data-act="closeSettings" aria-label="Назад">${ICONS.back}</button>
            <h1>Настройки</h1>
        </div>
        <div class="page-body settings-body">
            <button class="card-link" data-act="history"><span class="set-ico">${ICONS.history}</span><span class="text"><div class="title">Поездки</div><div class="sub">${d.trips.length ? `${d.trips.length} ${plural(d.trips.length, ['поездка', 'поездки', 'поездок'])}, последняя — «${esc(d.trips[0].dest.title)}»` : 'Куда и когда вы ездили'}</div></span>${ICONS.back.replace('<svg', '<svg class="chev"')}</button>

            <div class="group-title">Голос</div>
            <div class="card">
                ${sw('voice', 'Голосовые подсказки', 'Повороты, развязки и прибытие', ICONS.soundOn)}
                ${sw('streetNames', 'Называть улицы', '«Направо на улицу Северную»', ICONS.place)}
                <button class="setting" data-act="testVoice"><span class="set-ico">${ICONS.play}</span><span class="text"><div class="title">Проверить голос</div><div class="sub">Скажет пример подсказки</div></span></button>
            </div>

            <div class="group-title">Предупреждения</div>
            <div class="card">
                ${sw('cameras', 'Камеры и ограничения скорости', 'По данным OpenStreetMap: отмечены не везде', hz('camera'))}
                <div class="setting static sub-setting"><span class="text"><div class="sub">Скорость краснеет выше ограничения на</div></span></div>
                ${seg('speedTolerance', [[0, '+0 км/ч'], [10, '+10'], [20, '+20']])}
                ${sw('bumps', 'Лежачие полицейские и переезды', 'Железнодорожные переезды и искусственные неровности', hz('bump'))}
                ${sw('schools', 'Школы и детские сады', 'Днём, когда рядом могут быть дети', hz('school'))}
                ${sw('curves', 'Крутые повороты', 'На загородных дорогах, по форме маршрута', hz('curve'))}
                ${sw('reports', 'Сообщения водителей', 'ДТП, ДПС, ремонт, камеры, пробки — отмечают сами водители', hz('crash'))}
            </div>

            <div class="group-title">Карта</div>
            <div class="card">
                ${seg('mapType', MAP_TYPES)}
                <div class="setting static sub-setting"><span class="text"><div class="sub">Гибрид — снимки со спутника с улицами и подписями. Оформление днём и ночью:</div></span></div>
                ${seg('theme', [['auto', 'Авто'], ['day', 'День'], ['night', 'Ночь']])}
            </div>

            <div class="group-title">Карта без интернета</div>
            <div class="card">
                ${platform.isNative ? `${town ? `<button class="setting" data-act="downloadTown"><span class="set-ico">${ICONS.download}</span><span class="text"><div class="title">Скачать карту: ${esc(town.name)}</div><div class="sub">Около ${town.mb} МБ · схема с домами и улицами</div></span></button>` : `<div class="setting static"><span class="set-ico">${ICONS.download}</span><span class="text"><div class="title">Скачать карту города</div><div class="sub">Найдите любой адрес — и здесь появится ваш город</div></span></div>`}
                ${offline}
                <div class="setting static sub-setting"><span class="text"><div class="title">Карта маршрута заранее</div><div class="sub">Перед поездкой карта вдоль пути сохраняется на телефон</div></span></div>
                ${seg('prefetch', [['wifi', 'По Wi-Fi'], ['always', 'Всегда'], ['off', 'Нет']])}
                ${kept ? `<button class="setting" data-act="clearCache"><span class="set-ico">${ICONS.trash}</span><span class="text"><div class="title">Очистить сохранённую карту</div><div class="sub">Просмотренная карта: ${kept.cache} МБ · скачанная: ${kept.offline} МБ</div></span></button>` : ''}`
        : `<div class="setting static"><span class="text"><div class="sub">Скачивание карт работает в приложении для Android.</div></span></div>`}
            </div>

            <div class="group-title">Друзья из «Рядом»</div>
            <div class="card">
                ${ryadom ? `${sw('friends', 'Показывать друзей на карте', `Подключено${ryadom.name ? `: ${esc(ryadom.name)}` : ''}. Видны только ваши друзья`, ICONS.friends)}
                <button class="setting" data-act="unlinkRyadom"><span class="set-ico">${ICONS.close}</span><span class="text"><div class="title">Отключить «Рядом»</div></span></button>`
        : `<button class="setting" data-act="linkRyadom"><span class="set-ico">${ICONS.friends}</span><span class="text"><div class="title">Подключить «Рядом»</div><div class="sub">Друзья появятся на карте Навигатора. В «Рядом»: Профиль → «Показать в Навигаторе»</div></span></button>`}
            </div>

            <div class="group-title">Маршрут на машине</div>
            <div class="card">
                ${sw('avoidTolls', 'Избегать платных дорог')}
                ${sw('avoidHighways', 'Избегать автомагистралей')}
                ${sw('avoidFerries', 'Избегать паромов')}
            </div>

            <div class="group-title">Места</div>
            <div class="card">
                ${placeLine('home', 'Дом', ICONS.home)}
                ${placeLine('work', 'Работа', ICONS.work)}
                ${d.recent.length ? `<button class="setting" data-act="clearRecent"><span class="set-ico">${ICONS.clock}</span><span class="text"><div class="title">Очистить недавние</div></span></button>` : ''}
            </div>

            <div class="group-title">Демо-поездка</div>
            <div class="card">${seg('demoSpeed', DEMO_SPEEDS.map((v) => [v, `×${v}`]))}</div>

            <div class="group-title">О приложении</div>
            <div class="card">
                <div class="logo"><div class="mark">${ICONS.navigate}</div><div><div class="name">Навигатор</div><div class="ver">Версия ${esc(info.version)}</div></div></div>
                <div class="about">
                    Бесплатный навигатор на открытых картах, без рекламы.<br><br>
                    <b>Карта</b> — © участники OpenStreetMap, OpenMapTiles, оформление и тайлы OpenFreeMap; снимки — Esri.<br>
                    <b>Поиск</b> — Photon (komoot) и Nominatim.<br>
                    <b>Камеры и предупреждения</b> — OpenStreetMap через Overpass API и сами водители.<br>
                    <b>Маршруты</b> — Valhalla и OSRM на серверах FOSSGIS.<br>
                    <b>Движок карты</b> — MapLibre GL JS.<br><br>
                    Данные карты могут быть неточными: следите за дорогой и знаками.
                    <br><br><button class="btn small" data-act="privacy">${ICONS.info}Конфиденциальность</button>
                    ${info.supportUrl ? ` <button class="btn small" data-act="support">${ICONS.heart}Поддержать автора</button>` : ''}
                </div>
            </div>
        </div>`;
}

const settingsActions = {
    closeSettings,
    history: openHistory,
    toggle(el) {
        const key = el.dataset.key;
        store.set(key, !store.settings[key]);
        if (key === 'voice' && !store.settings.voice) platform.stopSpeaking();
        if (key === 'streetNames' && S.nav) S.nav.voice.streets = store.settings.streetNames;
        if (key === 'curves' && S.nav) {
            S.nav.curves = store.settings.curves ? findCurves(S.nav.route) : [];
            mixHazards(S.nav);
        }
        if (key === 'reports') {
            S.reportsAt = null;
            loadViewReports();
        }
        if (key === 'friends') loadFriends();
        if (['cameras', 'bumps', 'schools', 'reports', 'curves'].includes(key)) showHazards();
        renderSettings();
    },
    pick(el) {
        const key = el.dataset.key;
        const value = key === 'demoSpeed' || key === 'speedTolerance' ? +el.dataset.value : el.dataset.value;
        store.set(key, value);
        if (key === 'theme') applyTheme();
        if (key === 'mapType') setMapType(value);
        if (key === 'demoSpeed' && S.nav && S.nav.sim) {
            S.nav.sim.speedup = value;
            renderNavbar();
        }
        renderSettings();
    },
    clearPlace(el) {
        if (el.dataset.which === 'home') store.setHome(null);
        else store.setWork(null);
        renderSettings();
        renderTop();
    },
    clearRecent() {
        store.data.recent = [];
        store.save();
        renderSettings();
        renderTop();
    },
    support() {
        platform.openUrl(platform.appInfo().supportUrl);
    },
    privacy() {
        platform.openUrl('https://ice-rust.store/navigator/privacy');
    },
    testVoice() {
        if (!store.settings.voice) toast('Голосовые подсказки выключены');
        else platform.speak(store.settings.streetNames ? 'Через 300 метров поверните направо на улицу Северную, затем налево.' : 'Через 300 метров поверните направо, затем налево.');
    },
    downloadTown() {
        const town = townDownload();
        const { tiles, glyphs, sprite } = map.sources();
        if (!town || !tiles) {
            toast('Карта ещё не загрузилась, попробуйте через пару секунд');
            return;
        }
        const net = platform.network();
        if (!net.online) {
            toast('Нет интернета');
            return;
        }
        const tag = `area${Date.now()}`;
        const urls = [...tileUrls(tiles, tilesInBox(town.bbox, 0, MAX_ZOOM)), ...styleUrls(glyphs, sprite)];
        if (!platform.prefetch(tag, urls, true)) return;
        S.downloads[tag] = { done: 0, total: urls.length, failed: 0, name: town.name };
        store.put('offline', [{ tag, name: town.name, bbox: town.bbox, tiles: town.tiles, mb: town.mb, at: Date.now(), ready: false }, ...store.data.offline.filter((a) => a.name !== town.name)]);
        toast(net.metered ? `Скачиваю ${town.mb} МБ через мобильный интернет` : `Скачиваю карту «${town.name}»`);
        renderSettings();
    },
    cancelArea(el) {
        const a = store.data.offline[+el.dataset.i];
        if (!a) return;
        platform.cancelPrefetch(a.tag);
        delete S.downloads[a.tag];
        renderSettings();
    },
    async dropArea(el) {
        const a = store.data.offline[+el.dataset.i];
        if (!a || !(await confirm(`Удалить карту «${a.name}»?`, 'Она останется в обычном кэше, пока телефону хватает места.', 'Удалить', true))) return;
        store.put('offline', store.data.offline.filter((x) => x !== a));
        if (!store.data.offline.length) platform.clearMaps('offline');
        renderSettings();
    },
    async clearCache() {
        if (!(await confirm('Очистить сохранённую карту?', 'Карта снова будет загружаться из интернета. Скачанные города тоже удалятся.', 'Очистить', true))) return;
        platform.clearMaps('all');
        store.put('offline', []);
        renderSettings();
    },
    linkRyadom() {
        platform.openRyadom();
        toast('В «Рядом» откройте Профиль → «Показать в Навигаторе»', 5000);
    },
    async unlinkRyadom() {
        if (!(await confirm('Отключить «Рядом»?', 'Друзья пропадут с карты Навигатора. Подключить снова можно из «Рядом».', 'Отключить', true))) return;
        store.put('ryadom', null);
        S.friends = [];
        map.setFriends([]);
        renderSettings();
    },
};

// ---- Dialogs, menus, toasts ----

function confirm(title, text, ok, danger = false) {
    return new Promise((resolve) => {
        S.dialog = resolve;
        const d = $('dialog');
        d.innerHTML = `<div class="box"><h3>${esc(title)}</h3><p>${esc(text)}</p>
            <div class="buttons"><button class="btn" data-act="answer" data-value="">Отмена</button>
            <button class="btn ${danger ? 'danger' : 'primary'}" data-act="answer" data-value="yes">${esc(ok)}</button></div></div>`;
        d.hidden = false;
    }).then((v) => v === 'yes');
}

/** A question with a line to type the answer in: the text, or null when cancelled. */
function ask(title, text, ok) {
    return new Promise((resolve) => {
        S.dialog = resolve;
        const d = $('dialog');
        d.innerHTML = `<div class="box"><h3>${esc(title)}</h3><p>${esc(text)}</p>
            <textarea id="ask-text" rows="3" maxlength="300" placeholder="Напишите здесь"></textarea>
            <div class="buttons"><button class="btn" data-act="answer" data-value="">Отмена</button>
            <button class="btn primary" data-act="send">${esc(ok)}</button></div></div>`;
        d.hidden = false;
        setTimeout(() => $('ask-text').focus(), 50);
    }).then((v) => (v === '' || v == null ? null : v));
}

function menu(items) {
    return new Promise((resolve) => {
        S.dialog = resolve;
        const d = $('dialog');
        d.innerHTML = `<div class="box menu-list">${items.map(([value, icon, label]) =>
            `<button class="row" data-act="answer" data-value="${value}"><span class="ico">${icon}</span><span class="text"><div class="title">${esc(label)}</div></span></button>`).join('')}</div>`;
        d.hidden = false;
    });
}

function closeDialog(value) {
    const resolve = S.dialog;
    S.dialog = null;
    $('dialog').hidden = true;
    if (resolve) resolve(value);
}

const dialogActions = {
    answer(el) {
        closeDialog(el.dataset.value);
    },
    send() {
        const text = $('ask-text').value.trim();
        closeDialog(text || ' ');
    },
};
$('dialog').addEventListener('click', (e) => {
    if (e.target === $('dialog')) closeDialog('');
});

let toastTimer = null;
function toast(text, ms = 2600) {
    const t = $('toast');
    t.textContent = text;
    t.hidden = false;
    t.classList.remove('out');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
        t.classList.add('out');
        toastTimer = setTimeout(() => {
            t.hidden = true;
        }, 180);
    }, ms);
}

// ---- Back button and links from other apps ----

function onBack() {
    if (S.dialog) {
        closeDialog('');
        return true;
    }
    if (S.hud) {
        closeHud();
        return true;
    }
    if (!$('history').hidden) {
        closeHistory();
        return true;
    }
    if (!$('steps').hidden) {
        closeSteps();
        return true;
    }
    if (!$('settings').hidden) {
        closeSettings();
        return true;
    }
    if (S.hazard) {
        closeHazard();
        return true;
    }
    if (S.report) {
        closeReport();
        return true;
    }
    if (S.along) {
        closeAlong();
        return true;
    }
    switch (S.screen) {
        case 'search':
            closeSearch();
            return true;
        case 'place':
            closePlace();
            return true;
        case 'preview':
            backToPlace();
            return true;
        case 'nav':
            confirmEnd();
            return true;
        default:
            return false;
    }
}

/** «Рядом» asks to show its friends here: once agreed, their places come with a key that only reads them. */
async function linkRyadom({ server, token, name }) {
    const host = new URL(server).host;
    const ok = await confirm('Показывать друзей из «Рядом»?', `Навигатор будет видеть, где ваши друзья${name ? ` (аккаунт ${name})` : ''}, и покажет их на карте. Сервер: ${host}. Отключить можно в настройках.`, 'Показывать');
    if (!ok) return;
    store.put('ryadom', { server, token, name });
    store.set('friends', true);
    toast('Друзья из «Рядом» появятся на карте');
    loadFriends();
}

function openIntent(uri) {
    const r = parseIntent(uri);
    if (!r) return;
    if (r.friends) {
        linkRyadom(r.friends);
        return;
    }
    if (S.nav) {
        toast('Сначала завершите текущий маршрут');
        return;
    }
    closeSettings();
    closeHistory();
    if (r.go) {
        const d = store.data;
        if (r.go === 'search') openSearch('');
        else if (r.go === 'car') {
            if (d.parked) openParked();
            else toast('Место машины запоминается в конце поездки');
        } else if (d[r.go]) {
            openPreview(d[r.go]);
        } else {
            openSearch('');
            toast(r.go === 'home' ? 'Найдите свой адрес и выберите «Сделать домом»' : 'Найдите адрес работы и выберите «Сделать работой»', 4500);
        }
    } else if (r.point) {
        openPlace({ title: r.label || 'Точка на карте', subtitle: '', kind: '', lon: r.point[0], lat: r.point[1], id: `${r.point[0]},${r.point[1]}` },
            { lookup: true, keepTitle: Boolean(r.label), fly: true });
    } else if (r.query) {
        openSearch(r.query);
    }
}

// ---- The app's own server: which servers to ask, and a word for everyone ----

/** Newer than [b]? Versions as "1.3", "1.10". */
function newer(a, b) {
    const x = String(a).split('.').map(Number);
    const y = String(b).split('.').map(Number);
    for (let i = 0; i < Math.max(x.length, y.length); i++) {
        if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0);
    }
    return false;
}

async function loadConfig() {
    try {
        const config = await fetchConfig(platform.http);
        useServers(config.servers);
        store.put('config', { ...config, at: Date.now() });
        const version = platform.appInfo().version;
        if (config.message && config.message !== store.data.seenMessage) {
            store.put('seenMessage', config.message);
            toast(config.message, 6000);
        } else if (config.latest && version !== 'web' && newer(config.latest, version)) {
            toast(`Вышла новая версия Навигатора: ${config.latest}`, 5000);
        }
    } catch (e) {
        // The servers named last time, or the free ones, stay.
    }
}

// ---- Go ----

wireStatic();
renderTop();
applyBars();
setInterval(tick, 1000);
platform.setLocationMode('browse');
platform.startLocation();
const startIntent = new URLSearchParams(location.search).get('intent');
if (startIntent) openIntent(startIntent);
setTimeout(loadConfig, 1500);
setTimeout(loadFriends, 2500);
document.addEventListener('visibilitychange', () => {
    if (!document.hidden) loadFriends();
});

// For the tests that drive the page.
window.__app = { S, map, store, openPlace, openPreview, startNav, endNav, onBack, openIntent, openHazard, openReport, openHud, openHistory, renderSuggestion, refreshSuggestion, showPrompt };
