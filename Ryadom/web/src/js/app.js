// «Рядом»: the screens, and what they do with the server, the map and the phone.

import { Api, codeIn, inviteLink } from './api.js';
import { meters } from './geo.js';
import { icon } from './icons.js';
import { MapView, STALE_MS } from './map.js';
import * as platform from './platform.js';
import { load, save } from './store.js';
import { ago, distance, escape, initials, speed } from './words.js';

export const COLORS = ['#E5484D', '#F76B15', '#FFC53D', '#46A758', '#12A594', '#0090FF', '#3E63DD', '#8E4EC6', '#D6409F', '#7B8794'];
const POLL_MS = 10000;
// While the app is open it sends this phone's position this often, or sooner after a walk.
const SEND_MS = 10000;
const SEND_METERS = 25;
const PHOTON = 'https://photon.komoot.io';

const info = platform.appInfo();
const settings = load();
const api = new Api({ server: info.server, http: platform.http });
const $ = (id) => document.getElementById(id);

const state = {
    me: null,                // my profile: {id, name, color, code, sharing}
    view: null,              // the server's last {friends, incoming, outgoing, now}
    offset: 0,               // server time minus this phone's
    fix: null,               // this phone's last position
    locationStatus: 'ok',    // ok, denied, off
    online: true,
    selected: null,          // the friend whose card is open
    sheetOpen: false,
    sent: { at: 0, point: null },
    addresses: new Map(),
    centered: false,
    timer: null,
    pendingCode: null,       // a code from an invite link, before there is an account
};

const serverNow = () => Date.now() + state.offset;

// ---- The map ----

const map = new MapView($('map'), {
    style: settings.style,
    center: settings.view?.center,
    zoom: settings.view?.zoom,
    onFriend: (id) => select(id),
    onMove: (view) => {
        settings.view = view;
        save(settings);
    },
});

function updatePadding() {
    const sheet = $('sheet').getBoundingClientRect();
    const top = $('top').getBoundingClientRect();
    const covered = Math.max(0, window.innerHeight - sheet.top);
    document.documentElement.style.setProperty('--sheet-h', `${covered}px`);
    map.setPadding({ top: top.bottom + 16, bottom: Math.max(80, covered + 16), left: 40, right: 40 });
}

// ---- Server ----

async function refresh() {
    if (!api.token) return;
    try {
        const view = await api.view();
        state.offset = view.now - Date.now();
        state.view = view;
        state.me = view.me;
        state.online = true;
        render();
    } catch (e) {
        if (e.status === 401) return lostAccount();
        state.online = false;
        renderBanner();
    }
}

function startPolling() {
    stopPolling();
    state.timer = setInterval(refresh, POLL_MS);
}

function stopPolling() {
    if (state.timer) clearInterval(state.timer);
    state.timer = null;
}

/** The server no longer knows this phone's account (deleted, or the server started over). */
function lostAccount() {
    platform.stopSharing();
    platform.saveAccount(null);
    api.token = null;
    state.me = null;
    state.view = null;
    stopPolling();
    showWelcome('Аккаунт не найден на сервере. Создай новый — друзей нужно будет добавить заново.');
}

/** Sharing in the background follows the «show me» switch. */
function syncSharing() {
    if (state.me?.sharing) platform.startSharing(info.server, api.token);
    else platform.stopSharing();
}

async function sendFix(fix) {
    if (!api.token || !state.me?.sharing) return;
    const point = [fix.lon, fix.lat];
    const now = Date.now();
    const moved = state.sent.point ? meters(state.sent.point, point) : Infinity;
    if (now - state.sent.at < SEND_MS && moved < SEND_METERS) return;
    state.sent = { at: now, point };
    const power = platform.battery();
    try {
        const answer = await api.locate({ ...fix, battery: power?.level ?? null, charging: power?.charging ?? false });
        if (answer.incoming.length !== (state.view?.incoming.length ?? 0)) refresh();
    } catch (e) {
        if (e.status === 401) lostAccount();
    }
}

// ---- Position ----

platform.on('location', (fix) => {
    state.fix = fix;
    if (state.locationStatus !== 'ok') {
        state.locationStatus = 'ok';
        renderBanner();
    }
    map.setMe(fix);
    if (!state.centered && !settings.view) map.flyTo([fix.lon, fix.lat], 14);
    state.centered = true;
    sendFix(fix);
    renderList();
});

platform.on('locationStatus', (status) => {
    state.locationStatus = status;
    renderBanner();
});

platform.on('resume', () => {
    if (!api.token) return;
    refresh();
    startPolling();
    renderBanner();
    // Back from the phone's settings: what the profile says about permissions may have changed.
    if ($('screen').dataset.name === 'profile') showProfile();
});

platform.on('pause', () => stopPolling());

// ---- Rendering ----

function avatar(person, size = '') {
    return `<span class="avatar ${size}" style="--c:${escape(person.color)}">${escape(initials(person.name))}</span>`;
}

/** «1,2 км · 3 мин назад», or why there is no position. */
function statusOf(friend) {
    if (!friend.sharing) return 'скрывает местоположение';
    const p = friend.place;
    if (!p) return 'ещё не делился местоположением';
    const parts = [];
    if (state.fix) parts.push(distance(meters([state.fix.lon, state.fix.lat], [p.lon, p.lat])));
    const fast = speed(p.speed);
    if (fast && serverNow() - p.time < 5 * 60000) parts.push(fast);
    parts.push(ago(p.time, serverNow()));
    return parts.join(' · ');
}

function batteryOf(place) {
    if (!place || place.battery == null) return '';
    const low = place.battery <= 15 && !place.charging;
    return `<span class="battery${low ? ' low' : ''}">${icon(place.charging ? 'bolt' : 'battery')}${Math.round(place.battery)}%</span>`;
}

function sortedFriends() {
    const friends = [...(state.view?.friends || [])];
    const key = (f) => {
        if (!f.place) return Infinity;
        return state.fix ? meters([state.fix.lon, state.fix.lat], [f.place.lon, f.place.lat]) : 0;
    };
    return friends.sort((a, b) => key(a) - key(b) || a.name.localeCompare(b.name, 'ru'));
}

function render() {
    renderTop();
    renderList();
    renderBanner();
    renderCard();
    if (state.view) map.setFriends(state.view.friends, serverNow());
    map.highlight(state.selected);
}

function renderTop() {
    const me = state.me;
    $('profileBtn').innerHTML = me ? avatar(me) : icon('person');
    const chip = $('sharingChip');
    chip.hidden = !me;
    if (me) {
        chip.className = `chip ${me.sharing ? 'on' : 'off'}`;
        chip.innerHTML = me.sharing ? `<span class="led"></span>Друзья видят тебя` : `${icon('eyeOff')}Ты скрыт`;
    }
}

function renderList() {
    const view = state.view;
    const list = $('list');
    if (!view) {
        list.innerHTML = '';
        return;
    }
    const friends = sortedFriends();
    $('count').textContent = friends.length ? String(friends.length) : '';
    let html = '';
    if (view.incoming.length) {
        html += '<h3>Хотят дружить</h3>';
        for (const p of view.incoming) {
            html += `<div class="row request">${avatar(p)}<div class="who"><b>${escape(p.name)}</b><span>хочет видеть тебя на карте</span></div>
<button class="icon-btn ok" data-action="accept" data-id="${escape(p.id)}" aria-label="Принять">${icon('check')}</button>
<button class="icon-btn" data-action="decline" data-id="${escape(p.id)}" aria-label="Отклонить">${icon('close')}</button></div>`;
        }
    }
    if (friends.length) {
        if (view.incoming.length) html += '<h3>Друзья</h3>';
        for (const f of friends) {
            const stale = f.place && serverNow() - f.place.time > STALE_MS;
            html += `<button class="row friend${stale ? ' stale' : ''}${f.place ? '' : ' nowhere'}" data-action="select" data-id="${escape(f.id)}">${avatar(f)}
<div class="who"><b>${escape(f.name)}</b><span>${escape(statusOf(f))}</span></div>${batteryOf(f.place)}</button>`;
        }
    } else if (!view.incoming.length) {
        html += `<div class="empty"><p>Пока никого. Добавь друзей: отправь им свою ссылку или введи их код.</p>
<button class="primary" data-action="add">${icon('plus')}Добавить друга</button></div>`;
    }
    if (view.outgoing.length) {
        html += '<h3>Ждут ответа</h3>';
        for (const p of view.outgoing) {
            html += `<div class="row waiting">${avatar(p)}<div class="who"><b>${escape(p.name)}</b><span>заявка отправлена</span></div>
<button class="text-btn" data-action="cancel" data-id="${escape(p.id)}">Отменить</button></div>`;
        }
    }
    list.innerHTML = html;
    const badge = view.incoming.length;
    $('addBtn').classList.toggle('badge', badge > 0);
}

function renderBanner() {
    const banner = $('banner');
    let html = '';
    if (state.me && !state.online) {
        html = `<span>Нет связи с сервером — показываю то, что было.</span><button data-action="retry">Ещё раз</button>`;
    } else if (state.me && state.locationStatus === 'denied') {
        html = `<span>Нет доступа к местоположению: друзья тебя не видят.</span><button data-action="allowLocation">Разрешить</button>`;
    } else if (state.me && state.locationStatus === 'off') {
        html = `<span>Геолокация на телефоне выключена.</span><button data-action="locationSettings">Включить</button>`;
    }
    banner.innerHTML = html;
    banner.hidden = !html;
}

function friendById(id) {
    return state.view?.friends.find((f) => f.id === id) || null;
}

function renderCard() {
    const card = $('card');
    const f = state.selected && friendById(state.selected);
    if (!f) {
        card.hidden = true;
        document.body.classList.remove('card-open');
        return;
    }
    const p = f.place;
    const address = p ? state.addresses.get(addressKey(p)) : null;
    card.innerHTML = `<div class="card-head">${avatar(f, 'big')}<div class="who"><b>${escape(f.name)}</b><span>${escape(statusOf(f))}</span>
${address ? `<span class="address">${icon('pin')}${escape(address)}</span>` : ''}</div>${batteryOf(p)}
<button class="icon-btn" data-action="closeCard" aria-label="Закрыть">${icon('close')}</button></div>
<div class="card-actions">
${p ? `<button class="primary" data-action="route" data-id="${escape(f.id)}">${icon('route')}Маршрут</button>
<button data-action="show" data-id="${escape(f.id)}">${icon('locate')}Показать</button>` : ''}
<button class="icon-btn danger-text" data-action="unfriend" data-id="${escape(f.id)}" aria-label="Удалить из друзей">${icon('trash')}</button>
</div>
${p && p.accuracy > 100 ? `<p class="small">Точность около ${escape(distance(p.accuracy))}: телефон друга определял место по сети, а не по спутникам.</p>` : ''}`;
    card.hidden = false;
    document.body.classList.add('card-open');
    if (p && !address) lookUpAddress(p);
}

function addressKey(p) {
    return `${p.lat.toFixed(4)},${p.lon.toFixed(4)}`;
}

/** The street and house a friend is at, from Photon (OpenStreetMap's addresses). */
async function lookUpAddress(p) {
    const key = addressKey(p);
    if (state.addresses.has(key)) return;
    state.addresses.set(key, '');
    try {
        const answer = await platform.http(`${PHOTON}/reverse?lat=${p.lat.toFixed(6)}&lon=${p.lon.toFixed(6)}&limit=1&radius=0.2`, { timeout: 8000 });
        const feature = JSON.parse(answer.text).features?.[0]?.properties;
        if (!feature) return;
        const street = [feature.street, feature.housenumber].filter(Boolean).join(', ');
        const place = feature.name && feature.name !== feature.street ? feature.name : '';
        const town = feature.city || feature.town || feature.village || feature.district || '';
        const text = [place || street, town].filter(Boolean).join(' · ');
        if (text) {
            state.addresses.set(key, text);
            renderCard();
        }
    } catch (e) {
        // No address: the card shows the distance and time only.
    }
}

// ---- Friends ----

function select(id) {
    state.selected = id;
    const f = friendById(id);
    if (f?.place) map.flyTo([f.place.lon, f.place.lat]);
    setSheet(false);
    render();
}

function closeCard() {
    state.selected = null;
    render();
}

function setSheet(open) {
    state.sheetOpen = open;
    $('sheet').classList.toggle('open', open);
    setTimeout(updatePadding, 260);
}

function fitAll() {
    const points = (state.view?.friends || []).filter((f) => f.place).map((f) => [f.place.lon, f.place.lat]);
    if (state.fix) points.push([state.fix.lon, state.fix.lat]);
    if (points.length) map.fit(points);
    else toast('Пока некого показать: добавь друзей');
}

async function addByCode(raw) {
    const code = codeIn(raw);
    if (!code) {
        toast('Код — 8 букв и цифр, например ABCD-EFGH');
        return false;
    }
    if (code === state.me?.code) {
        toast('Это твой собственный код');
        return false;
    }
    try {
        const { result, name } = await api.add(code);
        toast(result === 'friends' ? `Теперь вы друзья: ${name}` : result === 'already' ? `${name} уже в списке` : `Заявка отправлена: ${name}`);
        await refresh();
        return true;
    } catch (e) {
        toast(e.status === 404 ? 'Такого кода нет. Проверь буквы или попроси новую ссылку' : e.status === 409 ? 'Слишком много друзей или заявок' : 'Не получилось. Проверь интернет');
        return false;
    }
}

async function act(fn, done) {
    try {
        const view = await fn();
        if (view?.friends) {
            state.view = view;
            state.offset = view.now - Date.now();
        }
        if (done) toast(done);
        render();
    } catch (e) {
        toast(e.status === 0 ? 'Нет связи с сервером' : 'Не получилось, попробуй ещё раз');
    }
}

// ---- Screens over the map ----

function openScreen(html, name) {
    const screen = $('screen');
    screen.innerHTML = html;
    screen.dataset.name = name;
    screen.hidden = false;
    document.body.classList.add('screen-open');
}

function closeScreen() {
    const screen = $('screen');
    screen.hidden = true;
    screen.innerHTML = '';
    screen.dataset.name = '';
    document.body.classList.remove('screen-open');
}

function palette(selected) {
    return `<div class="palette">${COLORS.map((c) => `<button class="swatch${c === selected ? ' on' : ''}" style="--c:${c}" data-action="color" data-color="${c}" aria-label="Цвет"></button>`).join('')}</div>`;
}

let welcomeColor = COLORS[Math.floor(Math.random() * COLORS.length)];

function showWelcome(note = '') {
    openScreen(`<div class="screen-body welcome">
<div class="logo">${icon('group')}</div>
<h1>Рядом</h1>
<p class="lead">Смотри на карте со спутника, где твои друзья. Ты видишь их — они видят тебя.</p>
${note ? `<p class="note">${escape(note)}</p>` : ''}
<label class="field">Как тебя зовут?<input id="nameInput" maxlength="30" autocomplete="nickname" placeholder="Имя"></label>
<p class="label">Твой цвет на карте</p>
${palette(welcomeColor)}
<button class="primary big" data-action="register">Продолжить</button>
<p class="small">Телефон и почта не нужны. Продолжая, ты принимаешь <a href="#" data-action="privacy">политику конфиденциальности</a>.</p>
</div>`, 'welcome');
}

function showPermission() {
    openScreen(`<div class="screen-body welcome">
<div class="logo">${icon('locate')}</div>
<h1>Где ты?</h1>
<p class="lead">Чтобы друзья видели тебя на карте, «Рядом» нужен доступ к местоположению. Видят его только те, кого ты добавил в друзья, и ты в любой момент можешь скрыться.</p>
<button class="primary big" data-action="grantLocation">Разрешить доступ</button>
<button class="text-btn" data-action="closeScreen">Позже</button>
</div>`, 'permission');
}

function showAdd(prefill = '') {
    const me = state.me;
    if (!me) return;
    const link = inviteLink(info.server, me.code);
    openScreen(`<header class="screen-head"><button class="icon-btn" data-action="closeScreen" aria-label="Назад">${icon('back')}</button><h2>Добавить друга</h2></header>
<div class="screen-body">
<section class="panel">
<p class="label">Твой код</p>
<div class="code">${escape(me.code)}</div>
<p class="small">Отправь другу ссылку или продиктуй код. Когда он добавит тебя, ты увидишь заявку и подтвердишь её.</p>
<div class="buttons">
<button class="primary" data-action="invite">${icon('share')}Отправить ссылку</button>
<button data-action="copyCode">${icon('copy')}Копировать</button>
</div>
<p class="small link">${escape(link)}</p>
</section>
<section class="panel">
<p class="label">Код друга</p>
<form data-action="addCode" class="code-form">
<input id="codeInput" placeholder="XXXX-XXXX" maxlength="40" autocapitalize="characters" autocomplete="off" spellcheck="false" value="${escape(prefill)}">
<button class="primary" type="submit">Добавить</button>
</form>
</section>
</div>`, 'add');
}

function showProfile() {
    const me = state.me;
    if (!me) return;
    const bg = platform.background();
    const backgroundOk = bg.always && bg.notifications && bg.unrestricted;
    openScreen(`<header class="screen-head"><button class="icon-btn" data-action="closeScreen" aria-label="Назад">${icon('back')}</button><h2>Профиль</h2></header>
<div class="screen-body">
<section class="panel profile-head">${avatar(me, 'big')}
<form data-action="rename" class="name-form"><input id="renameInput" value="${escape(me.name)}" maxlength="30"><button type="submit">Сохранить</button></form>
${palette(me.color)}
</section>
<section class="panel">
<label class="switch-row"><span><b>Показывать меня друзьям</b><small>${me.sharing ? 'Друзья видят, где ты' : 'Ты скрыт: друзья видят только, что ты в списке'}</small></span>
<input type="checkbox" id="sharingSwitch" data-action="toggleSharing" ${me.sharing ? 'checked' : ''}></label>
</section>
<section class="panel">
<p class="label">Работа в фоне</p>
<p class="small">${backgroundOk ? 'Всё настроено: друзья видят тебя, даже когда приложение закрыто.' : 'Чтобы друзья видели тебя, когда приложение закрыто, разреши:'}</p>
${platform.isNative && !bg.always ? `<button data-action="askBackground">${icon('locate')}Местоположение «Всегда»</button>` : ''}
${platform.isNative && !bg.notifications ? `<button data-action="askNotifications">Уведомления</button>` : ''}
${platform.isNative && !bg.unrestricted ? `<button data-action="battery">${icon('battery')}Без ограничений батареи</button>` : ''}
</section>
<section class="panel">
<p class="label">Карта</p>
<div class="segmented">${[['hybrid', 'Гибрид'], ['satellite', 'Спутник'], ['scheme', 'Схема']].map(([k, t]) => `<button class="${settings.style === k ? 'on' : ''}" data-action="style" data-style="${k}">${t}</button>`).join('')}</div>
</section>
<section class="panel">
<p class="label">Код для друзей: <b>${escape(me.code)}</b></p>
<button data-action="newCode">${icon('refresh')}Новый код</button>
<p class="small">Старая ссылка перестанет работать. Те, кто уже в друзьях, останутся.</p>
</section>
<section class="panel">
<button class="text-btn" data-action="privacy">Политика конфиденциальности</button>
<button class="danger-text" data-action="deleteAccount">${icon('trash')}Удалить аккаунт</button>
<p class="small">«Рядом» ${escape(info.version)} · снимки © Esri, карта © OpenStreetMap</p>
</section>
</div>`, 'profile');
}

function showLayers() {
    const box = $('layers');
    if (!box.hidden) {
        box.hidden = true;
        return;
    }
    box.innerHTML = [['hybrid', 'Гибрид'], ['satellite', 'Спутник'], ['scheme', 'Схема']]
        .map(([k, t]) => `<button class="${settings.style === k ? 'on' : ''}" data-action="style" data-style="${k}"><span class="thumb ${k}"></span>${t}</button>`).join('');
    box.hidden = false;
}

function setStyle(name) {
    settings.style = name;
    save(settings);
    map.setStyle(name);
    $('layers').hidden = true;
    if ($('screen').dataset.name === 'profile') showProfile();
}

/** A yes-or-no question over everything; resolves to the answer. */
function confirmDialog(text, ok, danger = false) {
    return new Promise((resolve) => {
        const box = $('dialog');
        box.innerHTML = `<div class="dialog-box"><p>${escape(text)}</p><div class="buttons">
<button data-answer="no">Отмена</button><button class="${danger ? 'danger' : 'primary'}" data-answer="yes">${escape(ok)}</button></div></div>`;
        box.hidden = false;
        box.onclick = (e) => {
            const answer = e.target.closest('[data-answer]')?.dataset.answer;
            if (!answer && e.target !== box) return;
            box.hidden = true;
            box.onclick = null;
            resolve(answer === 'yes');
        };
    });
}

let toastTimer = null;

function toast(text, ms = 2600) {
    const t = $('toast');
    t.textContent = text;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (t.hidden = true), ms);
}

// ---- What the buttons do ----

const actions = {
    async register() {
        const name = $('nameInput').value.trim();
        if (!name) {
            toast('Напиши имя — так тебя увидят друзья');
            $('nameInput').focus();
            return;
        }
        try {
            const { token, me } = await api.register(name, welcomeColor);
            platform.saveAccount({ token, id: me.id });
            state.me = me;
            closeScreen();
            enterMap();
            showPermission();
        } catch (e) {
            toast(e.status === 429 ? 'Слишком много попыток, подожди немного' : 'Не получилось связаться с сервером');
        }
    },
    color(el) {
        const color = el.dataset.color;
        if ($('screen').dataset.name === 'welcome') {
            welcomeColor = color;
            for (const s of document.querySelectorAll('.swatch')) s.classList.toggle('on', s.dataset.color === color);
            return;
        }
        act(async () => {
            state.me = await api.update({ color });
            showProfile();
        });
    },
    grantLocation() {
        platform.requestLocation();
        closeScreen();
        if (state.pendingCode) {
            showAdd(state.pendingCode);
            state.pendingCode = null;
        }
    },
    closeScreen() {
        closeScreen();
        if (state.pendingCode) {
            showAdd(state.pendingCode);
            state.pendingCode = null;
        }
    },
    privacy() {
        platform.openUrl(`${info.server.replace(/\/+$/, '')}/privacy`);
    },
    add: () => showAdd(),
    profile: () => (state.me ? showProfile() : null),
    layers: () => showLayers(),
    style: (el) => setStyle(el.dataset.style),
    me() {
        if (state.fix) map.flyTo([state.fix.lon, state.fix.lat], 16);
        else if (state.locationStatus !== 'ok') platform.requestLocation();
        else toast('Ищу тебя…');
    },
    fit: () => fitAll(),
    sheet: () => setSheet(!state.sheetOpen),
    select: (el) => select(el.dataset.id),
    closeCard: () => closeCard(),
    show(el) {
        const f = friendById(el.dataset.id);
        if (f?.place) map.flyTo([f.place.lon, f.place.lat], 17);
    },
    route(el) {
        const f = friendById(el.dataset.id);
        if (f?.place) platform.openPlace(f.place.lat, f.place.lon, f.name);
    },
    async unfriend(el) {
        const f = friendById(el.dataset.id);
        if (!f || !(await confirmDialog(`Удалить ${f.name} из друзей? Вы перестанете видеть друг друга.`, 'Удалить', true))) return;
        state.selected = null;
        act(() => api.unfriend(f.id), `${f.name} больше не в друзьях`);
    },
    accept(el) {
        const p = state.view?.incoming.find((x) => x.id === el.dataset.id);
        act(() => api.accept(el.dataset.id), p ? `Теперь вы друзья: ${p.name}` : null);
    },
    decline: (el) => act(() => api.decline(el.dataset.id)),
    cancel: (el) => act(() => api.unfriend(el.dataset.id), 'Заявка отменена'),
    invite() {
        const me = state.me;
        platform.share(`Добавь меня в «Рядом», чтобы видеть друг друга на карте: ${inviteLink(info.server, me.code)}\nМой код: ${me.code}`);
    },
    copyCode() {
        platform.copy(state.me.code);
        toast('Код скопирован');
    },
    async addCode() {
        if (await addByCode($('codeInput').value)) closeScreen();
    },
    async rename() {
        const name = $('renameInput').value.trim();
        if (!name) return;
        await act(async () => {
            state.me = await api.update({ name });
        }, 'Имя сохранено');
        showProfile();
    },
    async toggleSharing(el) {
        const sharing = el.checked;
        try {
            state.me = await api.update({ sharing });
            syncSharing();
            toast(sharing ? 'Друзья снова видят тебя' : 'Ты скрыт от всех друзей');
            if (sharing && state.fix) {
                state.sent.at = 0;
                sendFix(state.fix);
            }
        } catch (e) {
            el.checked = !sharing;
            toast('Нет связи с сервером');
        }
        render();
        if ($('screen').dataset.name === 'profile') showProfile();
    },
    async chip() {
        const sharing = !state.me.sharing;
        if (!sharing && !(await confirmDialog('Скрыть тебя от всех друзей? Они увидят, что ты скрыт, но не где ты.', 'Скрыть'))) return;
        const box = document.createElement('input');
        box.checked = sharing;
        actions.toggleSharing(box);
    },
    askBackground() {
        settings.askedBackground = true;
        save(settings);
        platform.requestBackground();
    },
    askNotifications: () => platform.requestNotifications(),
    battery: () => platform.openBatterySettings(),
    async newCode() {
        if (!(await confirmDialog('Сделать новый код? Старая ссылка и старый код перестанут работать.', 'Новый код'))) return;
        await act(async () => {
            state.me = await api.newCode();
        }, 'Готово: новый код');
        showProfile();
    },
    async deleteAccount() {
        if (!(await confirmDialog('Удалить аккаунт? Друзья, заявки и местоположение сотрутся с сервера навсегда.', 'Удалить', true))) return;
        try {
            await api.remove();
        } catch (e) {
            if (e.status !== 401) {
                toast('Нет связи с сервером — аккаунт не удалён');
                return;
            }
        }
        platform.stopSharing();
        platform.saveAccount(null);
        api.token = null;
        state.me = null;
        state.view = null;
        stopPolling();
        map.setFriends([], 0);
        render();
        showWelcome('Аккаунт удалён.');
    },
    retry: () => refresh(),
    allowLocation: () => platform.requestLocation(),
    locationSettings: () => platform.openLocationSettings(),
};

document.addEventListener('click', (e) => {
    const el = e.target.closest('[data-action]');
    if (!el || el.tagName === 'FORM') return;
    if (el.tagName === 'INPUT' && el.type === 'checkbox') return;
    e.preventDefault();
    const fn = actions[el.dataset.action];
    if (fn) fn(el);
});

document.addEventListener('change', (e) => {
    const el = e.target;
    if (el.matches('input[type=checkbox][data-action]')) actions[el.dataset.action]?.(el);
});

document.addEventListener('submit', (e) => {
    const form = e.target.closest('form[data-action]');
    if (!form) return;
    e.preventDefault();
    actions[form.dataset.action]?.(form);
});

document.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && e.target.id === 'nameInput') actions.register();
});

// ---- Back button and links ----

platform.on('back', () => {
    if (!$('dialog').hidden) {
        $('dialog').click();
        return true;
    }
    const screen = $('screen').dataset.name;
    if (screen && screen !== 'welcome') {
        actions.closeScreen();
        return true;
    }
    if (!$('layers').hidden) {
        $('layers').hidden = true;
        return true;
    }
    if (state.selected) {
        closeCard();
        return true;
    }
    if (state.sheetOpen) {
        setSheet(false);
        return true;
    }
    return false;
});

/** An invite link (https://…/add/CODE) or ryadom://add/CODE opened the app. */
function openLink(uri) {
    const code = codeIn(uri);
    if (!code) return;
    if (!state.me) {
        state.pendingCode = code;
        toast('Сначала представься — потом добавим друга');
        return;
    }
    closeScreen();
    showAdd(code);
}

platform.on('intent', openLink);

// ---- Start ----

function enterMap() {
    platform.startLocation();
    syncSharing();
    refresh().then(() => {
        if (!settings.view && !state.fix && state.view?.friends.some((f) => f.place)) fitAll();
    });
    startPolling();
}

$('profileBtn').innerHTML = icon('person');
$('layersBtn').innerHTML = icon('layers');
$('meBtn').innerHTML = icon('locate');
$('fitBtn').innerHTML = icon('group');
$('addBtn').innerHTML = `${icon('plus')}Добавить`;
window.addEventListener('resize', () => {
    map.resize();
    updatePadding();
});
updatePadding();

const saved = platform.account();
if (saved?.token) {
    api.token = saved.token;
    enterMap();
} else {
    showWelcome();
}
const startLink = new URLSearchParams(location.search).get('link');
if (startLink) openLink(startLink);

// For the end-to-end checks.
window.__app = { state, map, actions, refresh };
