// Штурман: the screens and what happens on them. Browsing the map, searching, a place, choosing a
// route, and driving it with the banner, the voice and new routes when the car goes another way.

import { along, distance } from './geo.js';
import { ICONS, laneIcon, maneuverIcon } from './icons.js';
import { parseIntent } from './intents.js';
import { MapView } from './mapview.js';
import * as platform from './platform.js';
import { cameraAhead, limitAt } from './roadinfo.js';
import { alongRoute, cameras as findCameras, CATEGORIES, nearby, reverse, routes as findRoutes, search, speedLimits } from './services.js';
import { Simulator } from './sim.js';
import { store } from './store.js';
import { isNight } from './sun.js';
import { Tracker } from './tracker.js';
import { Announcer, RoadAlerts } from './voice.js';
import { clockText, distanceText, durationText, stepText } from './words.js';

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
    routeSeq: 0,
    theme: 'day',
    dialog: null,
    along: null,               // the "along the route" sheet while driving: {cat, results, loading, error}
};

// ---- Starting up ----

const view = store.data.view;
S.theme = themeFor(view ? view.center : MOSCOW);
document.documentElement.dataset.theme = S.theme;

const map = new MapView($('map'), {
    theme: S.theme,
    center: view ? view.center : MOSCOW,
    zoom: view ? view.zoom : 11,
});

$('menu-btn').innerHTML = ICONS.menu;
$('search-open').innerHTML = `${ICONS.search}<span>Куда едем?</span>`;
$('locate-btn').innerHTML = ICONS.locate;
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
    platform.setTheme(t);
}

let tickCount = 0;
function tick() {
    tickCount++;
    if (tickCount % 30 === 0) applyTheme();
    if (S.nav && !S.nav.sim && !S.nav.arrived) {
        deadReckon();
        renderBanner();
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
    S.saveTimer = setTimeout(() => store.setView(map.center, map.zoom), 1000);
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
            onQuery(e.target.value);
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
    $('sheet').hidden = screen !== 'place' && screen !== 'preview' && !(screen === 'nav' && S.along);
    for (const id of ['banner', 'navbar']) $(id).hidden = !driving;
    if (!driving) {
        $('speed').hidden = true;
        $('lanes').hidden = true;
        $('resume-btn').hidden = true;
    }
    $('fabs').hidden = screen === 'search';
    $('compass-btn').hidden = driving || Math.abs(map.map.getBearing()) < 2;
    requestAnimationFrame(layoutPanels);
}

/** Moves the map's buttons and credits above whatever panel is at the bottom. */
function layoutPanels() {
    const root = document.documentElement.style;
    let bottom = 0;
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

async function onQuery(q) {
    q = q.trim();
    if (q === S.query && S.results) return;
    S.query = q;
    S.resultsKind = null;
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
        const found = await search(platform.http, q, here() || map.center);
        if (seq !== S.searchSeq) return;
        S.results = found;
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

function renderResults() {
    const box = $('search-results');
    const d = store.data;
    if (!S.query || S.query.length < 2) {
        const parts = [];
        parts.push(`<div class="group-title">Рядом</div><div class="cats">${CATEGORIES.map((c) =>
            `<button class="cat" data-act="cat" data-id="${c.id}">${ICONS[c.id]}<span>${c.label}</span></button>`).join('')}</div>`);
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
    if (!S.results || !S.results.length) {
        box.innerHTML = `<div class="empty">${ICONS.search}Ничего не нашлось.<br>Попробуйте написать иначе или добавить город.</div>`;
        return;
    }
    const icon = (S.resultsKind && ICONS[S.resultsKind]) || ICONS.place;
    box.innerHTML = S.results.map((p, i) => placeRow(p, 'pick', `data-which="results" data-i="${i}"`, icon)).join('');
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
    if (S.screen === 'nav' && S.along) renderAlong(sheet);
    else if (S.screen === 'preview') renderPreview(sheet);
    else if (S.place) renderPlace(sheet);
    requestAnimationFrame(layoutPanels);
}

function renderPlace(sheet) {
    const p = S.place;
    const saved = store.isSaved(p);
    const sub = [p.kind, p.subtitle].filter(Boolean).join(' · ');
    const dist = distanceTo(p);
    sheet.innerHTML = `
        <div class="grip"></div>
        <div class="sheet-head">
            <div class="text">
                <h2>${esc(p.title)}</h2>
                <div class="sub">${S.placeLoading && !sub ? 'Узнаю адрес…' : esc(sub)}${dist ? `${sub ? ' · ' : ''}${esc(dist)}` : ''}</div>
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
        if (S.screen === 'preview') backToPlace();
        else closePlace();
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
        voice: new Announcer(route),
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
        cameras: [],
        limit: undefined,
    };
    S.preview = null;
    S.along = null;
    setScreen('nav');
    useRoute(S.nav, route);
    map.setDestination([S.nav.dest.lon, S.nav.dest.lat]);
    map.setNavZoom(null, route.mode);
    map.setMode('nav');
    platform.keepScreenOn(true);
    renderNav();
    say(S.nav.voice.intro());
    if (demo) S.nav.sim.start((fix) => navUpdate(fix));
    else if (S.fix && Date.now() - S.fixAt < 10000) navUpdate(S.fix);
    updateLocateButton();
}

/** Drives [route] from now on: the map, the stops and what the road says about it. */
function useRoute(n, route) {
    n.route = route;
    n.tracker = new Tracker(route);
    n.voice = new Announcer(route);
    n.progress = null;
    n.limits = [];
    n.cameras = [];
    n.limit = undefined;
    map.drive(route);
    map.setCameras([]);
    n.stopsPassed = 0;
    map.setStops(n.stops.map((p) => [p.lon, p.lat]));
    loadRoadInfo(n, route);
}

/** Speed limits and cameras for a car route, fetched alongside; the drive goes on without them. */
async function loadRoadInfo(n, route) {
    if (!store.settings.cameras || route.mode !== 'car') return;
    const [limits, cams] = await Promise.all([
        speedLimits(platform.http, route).catch(() => []),
        findCameras(platform.http, route).catch(() => []),
    ]);
    if (S.nav !== n || n.route !== route) return;
    n.limits = limits;
    n.cameras = cams;
    map.setCameras(cams.map((c) => along(route.coords, route.cum, c.at).point));
    if (n.progress) renderNav();
}

function navUpdate(fix) {
    const n = S.nav;
    if (!n || n.arrived) return;
    const p = n.tracker.update(fix);
    if (!p) return;
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
    say(n.voice.update(p, fix.speed) || (st.cameras ? n.alerts.update(p, fix.speed, n.cameras, n.limit, st.speedTolerance) : null));
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
    if (n.sim) n.sim.stop();
    map.setManeuver(null);
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
    platform.stopSpeaking();
    platform.keepScreenOn(false);
    S.nav = null;
    S.place = null;
    S.along = null;
    map.clearRoutes();
    map.setCameras([]);
    map.setStops([]);
    map.setDestination(null);
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

/** The speed, the limit beside it and a speed camera coming up. */
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
    const cam = n.progress && store.settings.cameras ? cameraAhead(n.cameras, n.progress.along, 700) : null;
    box.hidden = false;
    box.innerHTML = `<div class="speed ${over ? 'over' : ''}"><b>${kmh}</b><small>км/ч</small></div>
        ${limit != null ? `<div class="limit"><b>${limit}</b></div>` : ''}
        ${cam ? `<div class="cam">${ICONS.camera}<span>${distanceText(Math.max(0, cam.at - n.progress.along))}</span></div>` : ''}`;
}

function bannerStreet(n, s) {
    if (s.type === 'arrive') return n.dest.title;
    if (s.type === 'waypoint') {
        const stop = n.stops[n.route.stops.indexOf(s)];
        return stop ? stop.title : '';
    }
    return s.street;
}

function renderBanner() {
    const n = S.nav;
    const b = $('banner');
    if (!n) return;
    const p = n.progress;
    const weak = !n.sim && !n.arrived && Date.now() - n.lastFixAt > 7000;
    const warn = n.predicting ? `<div class="warn">${ICONS.gps}Нет сигнала GPS — веду по маршруту</div>`
        : weak ? `<div class="warn">${ICONS.gps}Слабый сигнал GPS</div>` : '';
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
            ? `<div class="then">Затем <span style="width:26px;height:26px">${maneuverIcon(r.steps[k])}</span>${esc(stepText(r.steps[k]))}</div>` : '';
        b.innerHTML = `<div class="main">
                <div class="arrow">${maneuverIcon(s)}</div>
                <div class="what">
                    <div class="dist">${distanceText(p.toNext)}</div>
                    ${street ? `<div class="street">${esc(street)}</div><div class="act">${esc(stepText(s))}</div>` : `<div class="street">${esc(stepText(s))}</div>`}
                    ${s.toward ? `<div class="toward">→ ${esc(s.toward)}</div>` : ''}
                </div>
            </div>${then}${warn}`;
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
    if (box.hidden || $('banner').hidden) return;
    const r = $('banner').getBoundingClientRect();
    box.style.top = `${r.bottom - 2}px`;
}

function renderNavbar() {
    const n = S.nav;
    const bar = $('navbar');
    if (!n) return;
    if (n.arrived) {
        const parked = store.data.parked && !n.sim && n.route.mode === 'car';
        bar.innerHTML = `<div class="arrived" style="flex:1">
            <div class="flag">${ICONS.flag}</div>
            <h2>${esc(n.dest.title)}</h2>
            ${n.dest.subtitle ? `<div class="sub" style="color:var(--muted)">${esc(n.dest.subtitle)}</div>` : ''}
            ${parked ? `<div class="sub" style="color:var(--muted);margin-top:6px">Место машины запомнено: кнопка «Машина» на карте</div>` : ''}
            <button class="btn primary" data-act="done">Готово</button>
        </div>`;
        requestAnimationFrame(layoutPanels);
        return;
    }
    const p = n.progress;
    const left = p ? p.timeLeft : n.route.duration;
    const dist = p ? p.remaining : n.route.length;
    const eta = new Date(Date.now() + left * 1000);
    const demo = n.sim ? `<button class="demo-badge" data-act="demoSpeed">${ICONS.play}Демо ×${n.sim.speedup}</button>` : '';
    bar.innerHTML = `${demo}
        <button class="round end" data-act="end" aria-label="Завершить">${ICONS.close}</button>
        <button class="eta" data-act="more"><div class="clock">${clockText(eta)}</div><div class="left">${durationText(left)} · ${distanceText(dist)}</div></button>
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
        openAlong();
    },
    async more() {
        const n = S.nav;
        const choice = await menu([
            ['mute', n.muted ? ICONS.soundOn : ICONS.soundOff, n.muted ? 'Включить голос' : 'Выключить голос'],
            ['share', ICONS.share, 'Поделиться прибытием'],
            ['steps', ICONS.list, 'Маршрут по шагам'],
            ['settings', ICONS.settings, 'Настройки'],
        ]);
        if (!S.nav || S.nav !== n) return;
        if (choice === 'mute') {
            n.muted = !n.muted;
            if (n.muted) platform.stopSpeaking();
            toast(n.muted ? 'Голос выключен' : 'Голос включён');
        } else if (choice === 'share') {
            shareArrival(n);
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

function shareArrival(n) {
    const left = n.progress ? n.progress.timeLeft : n.route.duration;
    const eta = new Date(Date.now() + left * 1000);
    const d = n.dest;
    platform.share(`Еду в «${d.title}», буду в ${clockText(eta)} (через ${durationText(left)}).\n` +
        `https://www.openstreetmap.org/?mlat=${d.lat.toFixed(6)}&mlon=${d.lon.toFixed(6)}#map=17/${d.lat.toFixed(6)}/${d.lon.toFixed(6)}`);
}

// ---- Along the route: places to stop at on the way ----

function openAlong() {
    S.along = { cat: null, results: [], loading: false, error: '' };
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

// ---- Settings ----

function openSettings() {
    renderSettings();
    $('settings').hidden = false;
}

function closeSettings() {
    $('settings').hidden = true;
}

function renderSettings() {
    const st = store.settings;
    const d = store.data;
    const info = platform.appInfo();
    const sw = (key, title, sub = '') => `<button class="setting" data-act="toggle" data-key="${key}">
        <span class="text"><div class="title">${title}</div>${sub ? `<div class="sub">${sub}</div>` : ''}</span>
        <span class="switch ${st[key] ? 'on' : ''}"></span></button>`;
    const seg = (key, options) => `<div class="segmented">${options.map(([v, label]) =>
        `<button class="${st[key] === v ? 'on' : ''}" data-act="pick" data-key="${key}" data-value="${v}">${label}</button>`).join('')}</div>`;
    const placeLine = (which, label, icon) => {
        const p = d[which];
        return `<div class="row" style="cursor:default"><span class="ico">${icon}</span>
            <span class="text"><div class="title">${label}</div><div class="sub">${p ? esc(p.title) : 'Не задан'}</div></span>
            ${p ? `<button class="side-btn" data-act="clearPlace" data-which="${which}" aria-label="Убрать">${ICONS.trash}</button>` : ''}</div>`;
    };
    $('settings').innerHTML = `
        <div class="page-head">
            <button class="icon-btn" data-act="closeSettings" aria-label="Назад">${ICONS.back}</button>
            <h1>Настройки</h1>
        </div>
        <div class="page-body">
            <div class="group-title">Голос</div>
            ${sw('voice', 'Голосовые подсказки', 'Повороты, развязки и прибытие')}
            <button class="setting" data-act="testVoice"><span class="text"><div class="title">Проверить голос</div><div class="sub">Скажет пример подсказки</div></span>${ICONS.soundOn.replace('<svg', '<svg style="width:24px;height:24px;color:var(--accent)"')}</button>
            <div class="group-title">Камеры и скорость</div>
            ${sw('cameras', 'Камеры и ограничения скорости', 'По данным OpenStreetMap: отмечены не везде')}
            <div class="setting" style="cursor:default"><span class="text"><div class="title">Предупреждать о превышении</div><div class="sub">Скорость краснеет выше ограничения на</div></span></div>
            ${seg('speedTolerance', [[0, '+0 км/ч'], [10, '+10'], [20, '+20']])}
            <div class="group-title">Карта</div>
            ${seg('theme', [['auto', 'Авто'], ['day', 'День'], ['night', 'Ночь']])}
            <div class="group-title">Маршрут на машине</div>
            ${sw('avoidTolls', 'Избегать платных дорог')}
            ${sw('avoidHighways', 'Избегать автомагистралей')}
            ${sw('avoidFerries', 'Избегать паромов')}
            <div class="group-title">Демо-поездка</div>
            ${seg('demoSpeed', DEMO_SPEEDS.map((v) => [v, `×${v}`]))}
            <div class="group-title">Места</div>
            ${placeLine('home', 'Дом', ICONS.home)}
            ${placeLine('work', 'Работа', ICONS.work)}
            ${d.recent.length ? `<button class="setting" data-act="clearRecent"><span class="text"><div class="title">Очистить недавние</div></span></button>` : ''}
            <div class="group-title">О приложении</div>
            <div class="logo"><div class="mark">${ICONS.navigate}</div><div><div class="name">Штурман</div><div class="ver">Версия ${esc(info.version)}</div></div></div>
            <div class="about">
                Бесплатный навигатор на открытых картах.<br><br>
                <b>Карта</b> — © участники OpenStreetMap, OpenMapTiles, оформление и тайлы OpenFreeMap.<br>
                <b>Поиск</b> — Photon (komoot).<br>
                <b>Камеры</b> — OpenStreetMap через Overpass API.<br>
                <b>Маршруты</b> — Valhalla и OSRM на серверах FOSSGIS.<br>
                <b>Движок карты</b> — MapLibre GL JS.<br><br>
                Данные карты могут быть неточными: следите за дорогой и знаками.
                ${info.supportUrl ? `<br><br><button class="btn small" data-act="support">${ICONS.heart}Поддержать автора</button>` : ''}
            </div>
        </div>`;
}

const settingsActions = {
    closeSettings,
    toggle(el) {
        const key = el.dataset.key;
        store.set(key, !store.settings[key]);
        if (key === 'voice' && !store.settings.voice) platform.stopSpeaking();
        renderSettings();
    },
    pick(el) {
        const key = el.dataset.key;
        const value = key === 'demoSpeed' || key === 'speedTolerance' ? +el.dataset.value : el.dataset.value;
        store.set(key, value);
        if (key === 'theme') applyTheme();
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
    testVoice() {
        if (!store.settings.voice) toast('Голосовые подсказки выключены');
        else platform.speak('Через 300 метров поверните направо, затем налево.');
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
};
$('dialog').addEventListener('click', (e) => {
    if (e.target === $('dialog')) closeDialog('');
});

let toastTimer = null;
function toast(text, ms = 2600) {
    const t = $('toast');
    t.textContent = text;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
        t.hidden = true;
    }, ms);
}

// ---- Back button and links from other apps ----

function onBack() {
    if (S.dialog) {
        closeDialog('');
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

function openIntent(uri) {
    const r = parseIntent(uri);
    if (!r) return;
    if (S.nav) {
        toast('Сначала завершите текущий маршрут');
        return;
    }
    closeSettings();
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

// ---- Go ----

wireStatic();
renderTop();
platform.setTheme(S.theme);
setInterval(tick, 1000);
platform.startLocation();
const startIntent = new URLSearchParams(location.search).get('intent');
if (startIntent) openIntent(startIntent);

// For the tests that drive the page.
window.__app = { S, map, store, openPlace, openPreview, startNav, endNav, onBack, openIntent };
