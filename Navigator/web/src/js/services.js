// The free servers the app asks: Photon (komoot) for places, Valhalla and OSRM (FOSSGIS) for routes.
// Requests go through [http] (the app's own, or fetch in a browser): (url, options) => {status, text}.

import { along, distance, project } from './geo.js';
import { cameraQuery, limitChunks, parseCameras, parseLimits, traceRequest } from './roadinfo.js';
import { distinct, fromOsrm, fromValhalla } from './route.js';

export const SERVERS = {
    photon: 'https://photon.komoot.io',
    valhalla: 'https://valhalla1.openstreetmap.de',
    osrm: 'https://routing.openstreetmap.de',
    overpass: ['https://overpass-api.de/api/interpreter', 'https://overpass.kumi.systems/api/interpreter'],
};

export class ServiceError extends Error {
    constructor(message, kind) {
        super(message);
        this.kind = kind; // 'network', 'noroute', 'server'
    }
}

// ---- Places ----

const KINDS = {
    'amenity:fuel': 'АЗС', 'amenity:cafe': 'Кафе', 'amenity:restaurant': 'Ресторан', 'amenity:fast_food': 'Быстрое питание',
    'amenity:bar': 'Бар', 'amenity:pub': 'Паб', 'amenity:pharmacy': 'Аптека', 'amenity:hospital': 'Больница',
    'amenity:clinic': 'Поликлиника', 'amenity:school': 'Школа', 'amenity:kindergarten': 'Детский сад',
    'amenity:university': 'Университет', 'amenity:bank': 'Банк', 'amenity:atm': 'Банкомат', 'amenity:parking': 'Парковка',
    'amenity:police': 'Полиция', 'amenity:post_office': 'Почта', 'amenity:cinema': 'Кинотеатр', 'amenity:theatre': 'Театр',
    'amenity:place_of_worship': 'Храм', 'amenity:car_wash': 'Автомойка', 'amenity:charging_station': 'Зарядная станция',
    'amenity:townhall': 'Администрация', 'amenity:marketplace': 'Рынок', 'amenity:library': 'Библиотека',
    'shop:supermarket': 'Супермаркет', 'shop:convenience': 'Магазин', 'shop:mall': 'Торговый центр', 'shop:car_repair': 'Автосервис',
    'shop:bakery': 'Пекарня', 'shop:clothes': 'Одежда', 'shop:hardware': 'Хозтовары', 'shop:doityourself': 'Стройматериалы',
    'tourism:hotel': 'Гостиница', 'tourism:museum': 'Музей', 'tourism:attraction': 'Достопримечательность',
    'tourism:hostel': 'Хостел', 'tourism:guest_house': 'Гостевой дом', 'tourism:viewpoint': 'Смотровая площадка',
    'leisure:park': 'Парк', 'leisure:stadium': 'Стадион', 'leisure:sports_centre': 'Спортивный центр',
    'railway:station': 'Вокзал', 'railway:halt': 'Платформа', 'railway:tram_stop': 'Трамвайная остановка',
    'public_transport:station': 'Станция', 'highway:bus_stop': 'Остановка', 'aeroway:aerodrome': 'Аэропорт',
    'place:city': 'Город', 'place:town': 'Город', 'place:village': 'Село', 'place:hamlet': 'Деревня',
    'place:suburb': 'Район', 'place:neighbourhood': 'Микрорайон', 'place:quarter': 'Квартал', 'place:locality': 'Местность',
    'place:house': 'Дом', 'building:yes': 'Здание', 'building:house': 'Дом', 'building:apartments': 'Жилой дом',
    'highway:residential': 'Улица', 'highway:primary': 'Улица', 'highway:secondary': 'Улица', 'highway:tertiary': 'Улица',
    'highway:trunk': 'Шоссе', 'highway:motorway': 'Автомагистраль', 'highway:unclassified': 'Дорога', 'highway:living_street': 'Улица',
    'highway:pedestrian': 'Пешеходная улица', 'natural:water': 'Водоём', 'waterway:river': 'Река',
};

/** A place from a Photon feature: a title, a line of address under it, and where it is. */
export function placeFromPhoton(f) {
    const p = f.properties || {};
    const [lon, lat] = f.geometry.coordinates;
    const street = [p.street, p.housenumber].filter(Boolean).join(', ');
    const town = p.city || p.town || p.village || p.locality || p.county;
    let title = p.name || street || town || p.state || 'Точка на карте';
    if (!p.name && p.housenumber && !p.street) title = `${p.housenumber}`;
    const parts = [];
    if (p.name && street) parts.push(street);
    if (p.district && p.district !== title) parts.push(p.district);
    if (town && town !== title) parts.push(town);
    if (!town && p.state && p.state !== title) parts.push(p.state);
    const kind = KINDS[`${p.osm_key}:${p.osm_value}`];
    return {
        title,
        subtitle: parts.join(', '),
        kind: kind && kind !== title ? kind : '',
        lon, lat,
        extent: p.extent,
        id: `${p.osm_type || ''}${p.osm_id || `${lon},${lat}`}`,
    };
}

/** Two numbers typed in the search box, like "55.7539, 37.6208", as a place. */
export function parseCoordinates(text) {
    const m = text.trim().match(/^(-?\d{1,2}(?:[.,]\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:[.,]\d+)?)$/);
    if (!m) return null;
    const lat = parseFloat(m[1].replace(',', '.'));
    const lon = parseFloat(m[2].replace(',', '.'));
    if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
    return { title: `${lat.toFixed(5)}, ${lon.toFixed(5)}`, subtitle: 'Координаты', kind: '', lon, lat, id: `${lon},${lat}` };
}

export function searchUrl(query, near) {
    const q = new URLSearchParams({ q: query, limit: '10' });
    if (near) {
        q.set('lat', near[1].toFixed(5));
        q.set('lon', near[0].toFixed(5));
        q.set('zoom', '12');
        q.set('location_bias_scale', '0.3');
    }
    return `${SERVERS.photon}/api?${q}`;
}

export function reverseUrl(point) {
    return `${SERVERS.photon}/reverse?lat=${point[1].toFixed(6)}&lon=${point[0].toFixed(6)}&limit=1&radius=0.2`;
}

async function getJson(http, url, options = {}) {
    let res;
    try {
        res = await http(url, { timeout: 15000, ...options });
    } catch (e) {
        throw new ServiceError('Нет связи с сервером', 'network');
    }
    let json = null;
    try {
        json = JSON.parse(res.text);
    } catch (e) {
        json = null;
    }
    return { status: res.status, json };
}

export async function search(http, query, near) {
    const coords = parseCoordinates(query);
    if (coords) return [coords];
    const { status, json } = await getJson(http, searchUrl(query, near));
    if (status !== 200 || !json) throw new ServiceError('Поиск сейчас не отвечает', status ? 'server' : 'network');
    const seen = new Set();
    const out = [];
    for (const f of json.features || []) {
        const place = placeFromPhoton(f);
        const key = `${place.title}|${place.subtitle}`;
        if (seen.has(key)) continue;
        seen.add(key);
        out.push(place);
    }
    return out;
}

/** What is at [point]: the nearest address, or the bare coordinates. */
export async function reverse(http, point) {
    const bare = { title: 'Точка на карте', subtitle: `${point[1].toFixed(5)}, ${point[0].toFixed(5)}`, kind: '', lon: point[0], lat: point[1], id: `${point[0]},${point[1]}` };
    try {
        const { status, json } = await getJson(http, reverseUrl(point));
        const f = status === 200 && json && json.features && json.features[0];
        if (!f) return bare;
        const place = placeFromPhoton(f);
        // The pin stays where it was put, not where the address's point is.
        return { ...place, lon: point[0], lat: point[1] };
    } catch (e) {
        return bare;
    }
}

// ---- Routes ----

const COSTING = { car: 'auto', bike: 'bicycle', foot: 'pedestrian' };
const OSRM_PROFILE = { car: 'routed-car', bike: 'routed-bike', foot: 'routed-foot' };

/**
 * A Valhalla request from [from] to [to] ([lon, lat]); [heading] is the way the car points when
 * it is moving, so a new route does not begin with a U-turn.
 */
export function valhallaRequest(from, to, mode, { avoidTolls, avoidHighways, avoidFerries, heading, alternates = 2, stops = [] } = {}) {
    const start = { lat: from[1], lon: from[0] };
    if (heading != null) {
        start.heading = Math.round(heading);
        start.heading_tolerance = 45;
    }
    const costing = COSTING[mode] || 'auto';
    const options = {};
    if (costing === 'auto') {
        if (avoidTolls) options.use_tolls = 0;
        if (avoidHighways) options.use_highways = 0;
        if (avoidFerries) options.use_ferry = 0;
    } else if (costing === 'bicycle') {
        options.bicycle_type = 'hybrid';
        if (avoidFerries) options.use_ferry = 0;
    } else if (avoidFerries) {
        options.use_ferry = 0;
    }
    const body = {
        // Stops on the way are breaks: the route comes to each and the voice says so.
        locations: [start, ...stops.map((p) => ({ lat: p[1], lon: p[0], type: 'break' })), { lat: to[1], lon: to[0] }],
        costing,
        costing_options: { [costing]: options },
        directions_options: { units: 'kilometers', language: 'ru-RU' },
        directions_type: 'maneuvers',
        turn_lanes: true,
    };
    // Valhalla gives alternatives only for routes with no stops.
    if (alternates > 0 && !stops.length) body.alternates = alternates;
    return `${SERVERS.valhalla}/route?json=${encodeURIComponent(JSON.stringify(body))}`;
}

export function osrmRequest(from, to, mode, { heading, alternates = 2, stops = [] } = {}) {
    const profile = OSRM_PROFILE[mode] || OSRM_PROFILE.car;
    const q = new URLSearchParams({ overview: 'full', geometries: 'polyline6', steps: 'true' });
    if (alternates > 0 && !stops.length) q.set('alternatives', String(alternates));
    if (heading != null) q.set('bearings', `${Math.round(heading)},45${';'.repeat(stops.length + 1)}`);
    const pts = [from, ...stops, to].map((p) => `${p[0].toFixed(6)},${p[1].toFixed(6)}`).join(';');
    return `${SERVERS.osrm}/${profile}/route/v1/driving/${pts}?${q}`;
}

function valhallaError(json) {
    const code = json && json.error_code;
    if (code === 170 || code === 442 || code === 443) return new ServiceError('Сюда не проехать: дороги не связаны', 'noroute');
    if (code === 171) return new ServiceError('Рядом нет подходящих дорог', 'noroute');
    if (code === 154) return new ServiceError('Слишком далеко для этого способа', 'noroute');
    return null;
}

/**
 * Routes from [from] to [to], the best first. Valhalla first; if it fails to answer, OSRM.
 * The avoid options only work with Valhalla.
 */
export async function routes(http, from, to, mode, options = {}) {
    let firstError = null;
    try {
        const { status, json } = await getJson(http, valhallaRequest(from, to, mode, options), { timeout: 20000 });
        if (status === 200 && json && json.trip) return distinct(fromValhalla(json, mode));
        const known = valhallaError(json);
        if (known) throw known;
        firstError = new ServiceError('Сервер маршрутов не ответил', 'server');
    } catch (e) {
        if (e.kind === 'noroute') throw e;
        firstError = e;
    }
    try {
        const { status, json } = await getJson(http, osrmRequest(from, to, mode, options), { timeout: 20000 });
        if (status === 200 && json && json.code === 'Ok' && json.routes && json.routes.length) return distinct(fromOsrm(json, mode));
        if (json && (json.code === 'NoRoute' || json.code === 'NoSegment')) throw new ServiceError('Сюда не проехать', 'noroute');
    } catch (e) {
        if (e.kind === 'noroute') throw e;
    }
    throw firstError && firstError.kind === 'network' ? firstError : new ServiceError('Сервер маршрутов не ответил', 'server');
}

// ---- What the road says ----

/** Speed limits along a car route from Valhalla, stretch by stretch; [] when it cannot tell. */
export async function speedLimits(http, route) {
    if (route.mode !== 'car') return [];
    const out = [];
    for (const chunk of limitChunks(route)) {
        try {
            const res = await http(`${SERVERS.valhalla}/trace_attributes`, {
                method: 'POST', body: JSON.stringify(traceRequest(chunk.coords)), type: 'application/json', timeout: 20000,
            });
            if (res.status !== 200) break;
            out.push(...parseLimits(JSON.parse(res.text), chunk.from, chunk.to));
        } catch (e) {
            break;
        }
    }
    return out;
}

/** Speed cameras along the first few hundred kilometres of a car route; [] when none answer. */
export async function cameras(http, route) {
    if (route.mode !== 'car') return [];
    const query = cameraQuery(route);
    for (const server of SERVERS.overpass) {
        try {
            const res = await http(server, { method: 'POST', body: `data=${encodeURIComponent(query)}`, type: 'application/x-www-form-urlencoded', timeout: 30000 });
            if (res.status === 200) return parseCameras(JSON.parse(res.text), route);
        } catch (e) {
            // On to the next server.
        }
    }
    return [];
}

// ---- Places of a kind: nearby, or along the route ----

export const CATEGORIES = [
    { id: 'fuel', label: 'АЗС', tag: 'amenity:fuel' },
    { id: 'cafe', label: 'Кафе', tag: 'amenity:cafe' },
    { id: 'food', label: 'Еда', tag: 'amenity:fast_food' },
    { id: 'pharmacy', label: 'Аптека', tag: 'amenity:pharmacy' },
    { id: 'atm', label: 'Банкомат', tag: 'amenity:atm' },
    { id: 'parking', label: 'Парковка', tag: 'amenity:parking' },
    { id: 'shop', label: 'Продукты', tag: 'shop:supermarket' },
    { id: 'charging', label: 'Зарядка', tag: 'amenity:charging_station' },
    { id: 'repair', label: 'Автосервис', tag: 'shop:car_repair' },
    { id: 'toilets', label: 'Туалет', tag: 'amenity:toilets' },
];

export function nearbyUrl(point, tag, radiusKm = 3, limit = 15) {
    return `${SERVERS.photon}/reverse?lat=${point[1].toFixed(6)}&lon=${point[0].toFixed(6)}&osm_tag=${encodeURIComponent(tag)}&limit=${limit}&radius=${radiusKm}`;
}

/** Places of [category] near [point], nearest first. */
export async function nearby(http, point, category, radiusKm = 3) {
    const { status, json } = await getJson(http, nearbyUrl(point, category.tag, radiusKm));
    if (status !== 200 || !json) throw new ServiceError('Поиск сейчас не отвечает', status ? 'server' : 'network');
    const seen = new Set();
    const out = [];
    for (const f of json.features || []) {
        const p = placeFromPhoton(f);
        if (seen.has(p.id)) continue;
        seen.add(p.id);
        if (!p.kind) p.kind = category.label;
        if (p.title === 'Точка на карте' || !f.properties.name) p.title = f.properties.name || category.label;
        out.push({ ...p, away: distance(point, [p.lon, p.lat]) });
    }
    return out.sort((a, b) => a.away - b.away);
}

/**
 * Places of [category] along [route] ahead of [from] metres: looked for around a few points ahead,
 * each placed at how far along the route it is and how far off it.
 */
export async function alongRoute(http, route, from, category) {
    const ahead = route.mode === 'car' ? [1500, 5000, 10000, 18000, 30000] : [300, 1000, 2000];
    const radius = route.mode === 'car' ? 2.5 : 0.8;
    const found = new Map();
    let failed = 0;
    for (const extra of ahead) {
        const d = from + extra;
        if (d > route.length + 500) break;
        try {
            const places = await nearby(http, along(route.coords, route.cum, Math.min(d, route.length)).point, category, radius);
            for (const p of places) found.set(p.id, p);
        } catch (e) {
            failed++;
        }
    }
    if (!found.size && failed) throw new ServiceError('Поиск сейчас не отвечает', 'network');
    const out = [];
    for (const p of found.values()) {
        const spot = [p.lon, p.lat];
        let best = null;
        for (let i = 0; i < route.coords.length - 1; i++) {
            if (route.cum[i + 1] < from) continue;
            const { t, dist } = project(spot, route.coords[i], route.coords[i + 1]);
            if (!best || dist < best.dist) best = { i, t, dist };
        }
        if (!best) continue;
        const at = route.cum[best.i] + (route.cum[best.i + 1] - route.cum[best.i]) * best.t;
        if (at < from + 100) continue;
        out.push({ ...p, ahead: at - from, off: best.dist });
    }
    // Close to the road first, then nearer ahead.
    return out.filter((p) => p.off < radius * 1000).sort((a, b) => (a.ahead + a.off * 6) - (b.ahead + b.off * 6)).slice(0, 15);
}
