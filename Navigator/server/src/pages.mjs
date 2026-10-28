// The pages the server shows people: a shared drive (what the link opens) and the privacy policy.

const STYLE = `*{box-sizing:border-box}body{margin:0;background:#0d131d;color:#eef2f8;font:16px/1.45 system-ui,Roboto,sans-serif}
main{max-width:620px;margin:0 auto;padding:24px 20px}h1{font-size:24px;margin:0 0 10px}h2{font-size:18px;margin:22px 0 6px}li{margin:4px 0}
.small{color:#9aa7b8;font-size:14px}a{color:#7fb0ff}`;

function escape(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function page(title, body, head = '') {
    return `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title)}</title><style>${STYLE}</style>${head}</head><body>${body}</body></html>`;
}

const MAPLIBRE = 'https://unpkg.com/maplibre-gl@5.6.0/dist';

/**
 * A shared drive: a map with the car, the road left and the destination, and above it when the car
 * gets there. The page asks the server again every few seconds.
 */
export function tripPage({ id, title }) {
    const head = `<link rel="stylesheet" href="${MAPLIBRE}/maplibre-gl.css">
<style>body{height:100vh;display:flex;flex-direction:column}#map{flex:1;min-height:260px}
.card{padding:18px 20px 16px;background:#141c29;border-bottom:1px solid #ffffff14}
.who{color:#9aa7b8;font-size:15px}.dest{font-size:21px;font-weight:700;margin:2px 0 10px}
.eta{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap}.eta b{font-size:40px;line-height:1;letter-spacing:-1px}
.eta span{color:#c9d3e0;font-size:17px}.upd{margin-top:8px;color:#9aa7b8;font-size:13px}.done b{color:#47d18c}
.car{width:22px;height:22px;border-radius:50%;background:#2f6bff;border:4px solid #fff;box-shadow:0 2px 10px #0008}
.flag{width:18px;height:18px;border-radius:50% 50% 50% 0;transform:rotate(-45deg);background:#ff4d5e;border:3px solid #fff}
.app{display:block;margin-top:10px;font-size:14px}</style>`;
    const body = `<div class="card" id="card"><div class="who" id="who">Поездка</div><div class="dest">${escape(title)}</div>
<div class="eta" id="eta"><b>…</b></div><div class="upd" id="upd"></div></div><div id="map"></div>
<script src="${MAPLIBRE}/maplibre-gl.js"></script>
<script>
const ID = ${JSON.stringify(id)};
const $ = (id) => document.getElementById(id);
const clock = (t) => { const d = new Date(t); return d.getHours() + ':' + String(d.getMinutes()).padStart(2, '0'); };
const mins = (s) => { const m = Math.max(1, Math.round(s / 60)); return m < 60 ? m + ' мин' : Math.floor(m / 60) + ' ч' + (m % 60 ? ' ' + (m % 60) + ' мин' : ''); };
const km = (m) => m < 1000 ? Math.round(m / 50) * 50 + ' м' : (m / 1000).toFixed(m < 10000 ? 1 : 0).replace('.', ',') + ' км';
const ago = (s) => s < 60 ? 'только что' : s < 3600 ? Math.round(s / 60) + ' мин назад' : 'давно';
let map = null, car = null, flag = null, fitted = false;
function draw(t) {
    if (!window.maplibregl) return;
    if (!map) {
        map = new maplibregl.Map({ container: 'map', style: 'https://tiles.openfreemap.org/styles/liberty', center: [t.dest.lon, t.dest.lat], zoom: 12, attributionControl: { compact: true } });
        map.on('load', () => { map.addSource('road', { type: 'geojson', data: line(t) }); map.addLayer({ id: 'road', type: 'line', source: 'road', paint: { 'line-color': '#2f6bff', 'line-width': 6, 'line-opacity': 0.85 }, layout: { 'line-cap': 'round', 'line-join': 'round' } }); });
        const f = document.createElement('div'); f.className = 'flag';
        flag = new maplibregl.Marker({ element: f }).setLngLat([t.dest.lon, t.dest.lat]).addTo(map);
    }
    if (map.getSource('road')) map.getSource('road').setData(line(t));
    if (t.pos) {
        if (!car) { const c = document.createElement('div'); c.className = 'car'; car = new maplibregl.Marker({ element: c }).addTo(map); }
        car.setLngLat([t.pos.lon, t.pos.lat]);
        if (!fitted) { fitted = true; map.fitBounds([[Math.min(t.pos.lon, t.dest.lon), Math.min(t.pos.lat, t.dest.lat)], [Math.max(t.pos.lon, t.dest.lon), Math.max(t.pos.lat, t.dest.lat)]], { padding: 60, maxZoom: 15, duration: 0 }); }
    }
}
function line(t) { return { type: 'Feature', geometry: { type: 'LineString', coordinates: t.line && t.line.length > 1 ? t.line : [] }, properties: {} }; }
async function load() {
    let t;
    try {
        const r = await fetch('../api/trips/' + encodeURIComponent(ID), { cache: 'no-store' });
        if (r.status === 404) { $('eta').innerHTML = '<span>Поездка закончилась или ссылка устарела.</span>'; $('upd').textContent = ''; return; }
        t = await r.json();
    } catch (e) { $('upd').textContent = 'Нет связи, пробую ещё…'; setTimeout(load, 10000); return; }
    $('who').textContent = t.name ? t.name + (t.ended ? '' : ' в пути') : (t.ended ? 'Поездка' : 'В пути');
    if (t.arrived) { $('card').classList.add('done'); $('eta').innerHTML = '<b>На месте</b><span>в ' + clock(t.ended) + '</span>'; }
    else if (t.ended) { $('eta').innerHTML = '<span>Поездку перестали показывать.</span>'; }
    else if (t.eta) { $('eta').innerHTML = '<b>' + clock(t.eta + (Date.now() - t.now)) + '</b><span>через ' + mins((t.eta - t.now) / 1000) + (t.remaining != null ? ' · ' + km(t.remaining) : '') + '</span>'; }
    $('upd').textContent = t.ended ? '' : 'Обновлено ' + ago((t.now - t.updated) / 1000);
    draw(t);
    if (!t.ended) setTimeout(load, 10000);
}
load();
</script>`;
    return page(`${title} — поездка`, body, head);
}

export function privacyPage({ contact }) {
    return page('«Навигатор»: политика конфиденциальности', `<main><h1>Политика конфиденциальности «Навигатора»</h1>
<p>«Навигатор» работает без аккаунтов. Местоположение используется на телефоне — для карты, маршрута и подсказок —
и на сервер приложения попадает только в трёх случаях ниже, когда вы сами нажимаете кнопку.</p>
<h2>Сообщения о дороге</h2>
<p>Когда вы отмечаете на дороге ДТП, ремонт, пост ДПС, камеру, пробку или опасность, на сервер уходят место отметки,
направление движения и случайный номер, который приложение придумало при первом запуске. По этому номеру нельзя
узнать, кто вы; он нужен, чтобы один телефон не голосовал за одну отметку дважды. Отметки видят все водители,
номер — никто. Отметка удаляется сама через несколько часов или дней.</p>
<h2>«Поделиться поездкой»</h2>
<p>Пока вы делитесь поездкой, сервер хранит место назначения, где сейчас машина, оставшиеся время и путь.
Это видит только тот, кому вы отправили ссылку. История перемещений не ведётся, поездка удаляется
через полчаса после конца, и не позже чем через сутки.</p>
<h2>«Не то нашлось»</h2>
<p>Если вы сообщаете, что поиск нашёл не то, на сервер уходят текст запроса, найденные варианты, ваш комментарий,
город и место с точностью около ста метров — чтобы улучшить поиск.</p>
<h2>Друзья из «Рядом»</h2>
<p>Если вы подключили приложение «Рядом», Навигатор получает с сервера «Рядом» положения ваших друзей —
тех же, кого вы видите в «Рядом», — по ключу, который может только читать. Ключ хранится на телефоне;
отключить можно в настройках Навигатора.</p>
<h2>Карта, поиск и маршруты</h2>
<p>Карту, адреса и маршруты приложение запрашивает у открытых сервисов: OpenFreeMap, Esri (снимки), Photon,
Nominatim, Valhalla, OSRM, Overpass. Они получают координаты нужного места, как любой картографический сервис.
Данные не продаются, реклама не показывается.</p>
${contact ? `<h2>Связь</h2><p>${escape(contact)}</p>` : ''}</main>`);
}
