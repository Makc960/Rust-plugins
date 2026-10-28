// The servers the app asks: Photon (komoot) and Nominatim for places, Valhalla and OSRM (FOSSGIS)
// for routes, Overpass for what is on the road, and the app's own server for drivers' reports,
// shared drives, search notes and which of the others to ask (see server/).
// Requests go through [http] (the app's own, or fetch in a browser): (url, options) => {status, text}.

import { along, distance, project } from './geo.js';
import { hazardQuery, limitChunks, parseHazards, parseLimits, traceRequest } from './roadinfo.js';
import { distinct, fromOsrm, fromValhalla } from './route.js';

export const SERVERS = {
    photon: 'https://photon.komoot.io',
    nominatim: 'https://nominatim.openstreetmap.org',
    valhalla: 'https://valhalla1.openstreetmap.de',
    osrm: 'https://routing.openstreetmap.de',
    overpass: ['https://overpass-api.de/api/interpreter', 'https://overpass.kumi.systems/api/interpreter'],
    own: 'https://ice-rust.store/navigator',
};

const DEFAULT_SERVERS = structuredClone(SERVERS);
const HTTPS = /^https:\/\/[a-z0-9.-]+(:\d+)?(\/[\w./-]*)?$/i;

/**
 * Servers named by the app's own server ({photon, valhalla, ...}) take the place of the free
 * ones; anything not a plain https address is ignored. Called with {} it puts the defaults back.
 */
export function useServers(servers = {}) {
    for (const key of ['photon', 'nominatim', 'valhalla', 'osrm']) {
        SERVERS[key] = typeof servers[key] === 'string' && HTTPS.test(servers[key]) ? servers[key].replace(/\/+$/, '') : DEFAULT_SERVERS[key];
    }
    const overpass = Array.isArray(servers.overpass) ? servers.overpass.filter((u) => typeof u === 'string' && HTTPS.test(u)) : [];
    SERVERS.overpass = overpass.length ? overpass : [...DEFAULT_SERVERS.overpass];
}

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

/** A Photon search for [query]; [near] puts nearby places first, [bbox] keeps to an area. */
export function searchUrl(query, near, { bbox = null, scale = 0.3, zoom = 12, limit = 10, layer = null } = {}) {
    const q = new URLSearchParams({ q: query, limit: String(limit) });
    if (near) {
        q.set('lat', near[1].toFixed(5));
        q.set('lon', near[0].toFixed(5));
        q.set('zoom', String(zoom));
        q.set('location_bias_scale', String(scale));
    }
    if (bbox) q.set('bbox', bbox.map((v) => v.toFixed(5)).join(','));
    if (layer) q.set('layer', layer);
    return `${SERVERS.photon}/api?${q}`;
}

export function reverseUrl(point) {
    return `${SERVERS.photon}/reverse?lat=${point[1].toFixed(6)}&lon=${point[0].toFixed(6)}&limit=1&radius=0.2`;
}

// Words Russian addresses shorten, as the map spells them out.
const SPELLED = {
    'ул': 'улица', 'улиц': 'улица', 'пр': 'проспект', 'пр-т': 'проспект', 'пр-кт': 'проспект', 'просп': 'проспект', 'пркт': 'проспект',
    'пер': 'переулок', 'б-р': 'бульвар', 'бул': 'бульвар', 'бр': 'бульвар', 'ш': 'шоссе', 'наб': 'набережная', 'пл': 'площадь',
    'мкр': 'микрорайон', 'мкрн': 'микрорайон', 'м-н': 'микрорайон', 'мк-н': 'микрорайон', 'пр-д': 'проезд', 'туп': 'тупик',
    'корп': 'к', 'кор': 'к',
};
// Words that only say what the next one is: «г. Краснодар», «д. 5», «пос. Южный».
const DROPPED = new Set(['г', 'гор', 'город', 'д', 'дом', 'пос', 'п', 'посёлок', 'поселок', 'ст-ца', 'станица', 'село', 'х', 'хутор', 'россия', 'рф']);

/**
 * What a person typed as the map would have it: shortened words spelled out, «дом» and «город»
 * dropped, the flat number (кв. 12) left off. «ул. Красная, д. 120, кв. 5» → «улица Красная 120».
 */
export function normalizeQuery(text) {
    const tokens = String(text).replace(/[«»"]/g, ' ').split(/[\s,;]+/).filter(Boolean);
    const out = [];
    for (let i = 0; i < tokens.length; i++) {
        const raw = tokens[i];
        const word = raw.toLowerCase().replace(/\.+$/, '');
        if (word === 'кв' || word === 'квартира' || word === 'оф' || word === 'офис') {
            i++;
            continue;
        }
        if (DROPPED.has(word) && i + 1 < tokens.length) continue;
        if (SPELLED[word]) {
            out.push(SPELLED[word]);
            continue;
        }
        // «ул.Красная» typed without a space.
        const glued = raw.match(/^([А-Яа-яЁё-]+)\.([А-ЯЁа-яё\d].*)$/);
        if (glued && (SPELLED[glued[1].toLowerCase()] || DROPPED.has(glued[1].toLowerCase()))) {
            const kind = SPELLED[glued[1].toLowerCase()];
            if (kind) out.push(kind);
            out.push(glued[2]);
            continue;
        }
        out.push(raw);
    }
    return out.join(' ').trim() || String(text).trim();
}

/** The house number in a query, like «120» in «Красная 120» or «12к1» in «Мира 12 к1». */
export function houseIn(text) {
    const m = String(text).match(/(?:^|\s)(\d+[а-яА-Я]?(?:\/\d+)?)(?:\s*к\s*(\d+))?(?=\s|$)/);
    return m ? (m[2] ? `${m[1]}к${m[2]}` : m[1]).toLowerCase() : null;
}

/** [west, south, east, north] of [meters] around [point], as Photon's bbox wants it. */
export function boxAround(point, meters) {
    const dLat = meters / 111320;
    const dLon = meters / (111320 * Math.cos((point[1] * Math.PI) / 180));
    return [point[0] - dLon, point[1] - dLat, point[0] + dLon, point[1] + dLat];
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

/** Photon's answer as places, the same place once; each knows its house number and town. */
async function photon(http, url) {
    const { status, json } = await getJson(http, url);
    if (status !== 200 || !json) throw new ServiceError('Поиск сейчас не отвечает', status ? 'server' : 'network');
    const seen = new Set();
    const out = [];
    for (const f of json.features || []) {
        const place = placeFromPhoton(f);
        const key = `${place.title}|${place.subtitle}`;
        if (seen.has(key)) continue;
        seen.add(key);
        place.house = String(f.properties?.housenumber || '').toLowerCase().replace(/\s+/g, '');
        out.push(place);
    }
    return out;
}

// A town is searched within its own bounds; bounds wider than this are a region's, not a town's,
// and a circle of AREA_RADIUS around the person stands in for them.
const AREA_RADIUS = 25000;
const MAX_TOWN_SPAN = 1.2;

/**
 * The town around [point]: {name, bbox, point}, its name from the address there and its bounds
 * from the map, or a circle of 25 km when the map has no bounds for it.
 */
export async function areaAround(http, point) {
    let name = null;
    let bbox = null;
    try {
        const { status, json } = await getJson(http, reverseUrl(point));
        const p = status === 200 && json?.features?.[0]?.properties;
        name = (p && (p.city || p.town || p.village || p.locality)) || null;
        if (name) {
            const town = await getJson(http, searchUrl(name, point, { scale: 0.05, zoom: 10, limit: 3, layer: 'city' }));
            const f = (town.json?.features || []).find((x) => x.properties?.name === name && x.properties?.extent && distance(point, x.geometry.coordinates) < 60000);
            if (f) {
                const [w, n, e, s] = f.properties.extent;
                if (e - w < MAX_TOWN_SPAN && n - s < MAX_TOWN_SPAN) {
                    // A little wider, and always around the person too: suburbs lie past a town's line.
                    const pad = 0.02;
                    bbox = [Math.min(w, point[0]) - pad, Math.min(s, point[1]) - pad, Math.max(e, point[0]) + pad, Math.max(n, point[1]) + pad];
                }
            }
        }
    } catch (e) {
        // No answer: the circle around the person will do.
    }
    return { name, bbox: bbox || boxAround(point, AREA_RADIUS), point };
}

/**
 * The order to show places in: the very house asked for first; then those whose name has the
 * words typed («Магнит» → the shops called so, before a street of them); then the nearest.
 */
function rank(places, query, near) {
    const house = houseIn(query);
    const words = query.toLowerCase().replace(house || '', ' ').split(/\s+/).filter((w) => w.length > 1);
    const houseScore = (p) => (house && p.house === house ? 0 : house && p.house && p.house.startsWith(house) ? 1 : 2);
    const nameScore = (p) => (words.length && words.every((w) => p.title.toLowerCase().includes(w)) ? 0 : 1);
    const far = (p) => (near ? distance(near, [p.lon, p.lat]) : 0);
    return places
        .map((p, i) => ({ p, i }))
        .sort((a, b) => houseScore(a.p) - houseScore(b.p) || nameScore(a.p) - nameScore(b.p) || far(a.p) - far(b.p) || a.i - b.i)
        .map((x) => x.p);
}

/**
 * Looks for [query] in the town around the person first ([area], from [areaAround]):
 *   {places, scope: 'local', area}      found in the town;
 *   {places, scope: 'elsewhere', area}  nothing in the town: the nearest found elsewhere;
 *   {places, scope: 'everywhere'}       asked to look everywhere ([everywhere]).
 * A town's name typed alone («Сочи») finds that town even from another one. [precise]: the person
 * sent the search themselves, so a house number Photon does not know is asked of Nominatim too;
 * [exact] in the answer says whether the very house asked for was found.
 */
export async function search(http, query, near, area = null, { everywhere = false, precise = false } = {}) {
    const found = await photonSearch(http, query, near, area, { everywhere });
    const house = houseIn(normalizeQuery(query.trim()));
    found.exact = !house || found.places.some((p) => p.house === house);
    if (!precise || found.exact || found.scope === 'coords') return found;
    try {
        const more = (await nominatim(http, normalizeQuery(query.trim()), everywhere ? null : area)).filter((p) => p.house === house);
        if (more.length) {
            const ids = new Set(more.map((p) => p.id));
            more.sort((a, b) => (near ? distance(near, [a.lon, a.lat]) - distance(near, [b.lon, b.lat]) : 0));
            const local = area && area.bbox && more.every((p) => p.lon >= area.bbox[0] && p.lon <= area.bbox[2] && p.lat >= area.bbox[1] && p.lat <= area.bbox[3]);
            return { ...found, places: [...more, ...found.places.filter((p) => !ids.has(p.id))], scope: everywhere ? 'everywhere' : local ? 'local' : found.scope, exact: true };
        }
    } catch (e) {
        // Photon's answer stands.
    }
    return found;
}

async function photonSearch(http, query, near, area, { everywhere }) {
    const coords = parseCoordinates(query);
    if (coords) return { places: [coords], scope: 'coords', area };
    const typed = query.trim();
    const text = normalizeQuery(typed);
    if (!everywhere && area) {
        const inTown = (q) => photon(http, searchUrl(q, near, { bbox: area.bbox, scale: 0.2, zoom: 14, limit: 15 }));
        const [local, towns] = await Promise.all([
            inTown(text),
            houseIn(text) ? Promise.resolve([]) : photon(http, searchUrl(text, near, { scale: 0.5, zoom: 6, limit: 3, layer: 'city' })).catch(() => []),
        ]);
        const found = local.length || text === typed ? local : await inTown(typed);
        // «Сочи» typed in Krasnodar: that town goes first, before any street named for it.
        const asked = text.toLowerCase();
        const town = towns.find((t) => t.title.toLowerCase() === asked && t.title !== area.name);
        if (found.length) {
            const places = rank(found, text, near);
            return { places: town ? [town, ...places.filter((p) => p.id !== town.id)] : places, scope: 'local', area };
        }
        if (town) return { places: [town], scope: 'elsewhere', area };
    }
    // Everywhere, the nearest first.
    const anywhere = (q) => photon(http, searchUrl(q, near, { scale: 0.05, zoom: 8, limit: 15 }));
    let wide = await anywhere(text);
    if (!wide.length && text !== typed) wide = await anywhere(typed);
    return { places: rank(wide, text, near), scope: everywhere ? 'everywhere' : 'elsewhere', area };
}

// ---- Nominatim: house numbers Photon misses ----

// Nominatim asks for one request a second at most, and none while typing: it is asked only when a
// person sends the search themselves, and then waits its turn.
let nominatimAt = 0;

export function nominatimUrl(query, area) {
    const q = new URLSearchParams({ q: query, format: 'jsonv2', addressdetails: '1', limit: '8', 'accept-language': 'ru' });
    if (area && area.bbox) {
        const [w, s, e, n] = area.bbox;
        q.set('viewbox', [w, n, e, s].map((v) => v.toFixed(5)).join(','));
        q.set('bounded', '1');
    }
    return `${SERVERS.nominatim}/search?${q}`;
}

/** A place from a Nominatim answer, in the same shape as Photon's. */
export function placeFromNominatim(r) {
    const a = r.address || {};
    const lon = Number(r.lon);
    const lat = Number(r.lat);
    const road = a.road || a.pedestrian || a.footway || a.residential || a.square || '';
    const street = [road, a.house_number].filter(Boolean).join(', ');
    const town = a.city || a.town || a.village || a.hamlet || a.municipality || a.county || '';
    const name = r.name && r.name !== a.house_number ? r.name : '';
    const title = name || street || town || (r.display_name || '').split(',')[0] || 'Точка на карте';
    const parts = [];
    if (name && street) parts.push(street);
    if (a.suburb && a.suburb !== title) parts.push(a.suburb);
    if (town && town !== title) parts.push(town);
    const kind = KINDS[`${r.category}:${r.type}`];
    const type = { node: 'N', way: 'W', relation: 'R' }[r.osm_type] || '';
    return {
        title, subtitle: parts.join(', '), kind: kind && kind !== title ? kind : '', lon, lat,
        id: type && r.osm_id ? `${type}${r.osm_id}` : `${lon},${lat}`,
        house: String(a.house_number || '').toLowerCase().replace(/\s+/g, ''),
    };
}

/** Looks for [query] in Nominatim, in the town ([area]) first, then anywhere. */
export async function nominatim(http, query, area = null, wait = (ms) => new Promise((ok) => setTimeout(ok, ms))) {
    const ask = async (url) => {
        const gap = Date.now() - nominatimAt;
        if (gap < 1100) await wait(1100 - gap);
        nominatimAt = Date.now();
        const { status, json } = await getJson(http, url, { timeout: 12000 });
        if (status !== 200 || !Array.isArray(json)) throw new ServiceError('Поиск сейчас не отвечает', status ? 'server' : 'network');
        return json.map(placeFromNominatim).filter((p) => Number.isFinite(p.lon) && Number.isFinite(p.lat));
    };
    let found = area ? await ask(nominatimUrl(query, area)) : [];
    if (!found.length) found = await ask(nominatimUrl(area && area.name && !query.includes(area.name) ? `${query}, ${area.name}` : query, null));
    return found;
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

/**
 * What to warn about along the first few hundred kilometres of a car route, of [kinds]
 * ({camera, bump, rail, school}); [] when no Overpass server answers.
 */
export async function hazards(http, route, kinds = { camera: true }) {
    if (route.mode !== 'car' || !Object.values(kinds).some(Boolean)) return [];
    const query = hazardQuery(route, kinds);
    for (const server of SERVERS.overpass) {
        try {
            const res = await http(server, { method: 'POST', body: `data=${encodeURIComponent(query)}`, type: 'application/x-www-form-urlencoded', timeout: 30000 });
            if (res.status === 200) return parseHazards(JSON.parse(res.text), route);
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

// ---- The app's own server ----

/** The server's word on which servers to ask, and a message for everyone: {servers, message, latest}. */
export async function fetchConfig(http) {
    const { status, json } = await getJson(http, `${SERVERS.own}/api/config`, { timeout: 8000 });
    if (status !== 200 || !json || typeof json !== 'object') throw new ServiceError('Сервер приложения не ответил', 'server');
    return { servers: json.servers || {}, message: String(json.message || ''), latest: String(json.latest || '') };
}

async function ownPost(http, path, body, method = 'POST') {
    const { status, json } = await getJson(http, `${SERVERS.own}${path}`, { method, body: JSON.stringify(body), type: 'application/json', timeout: 12000 });
    if (status !== 200 || !json) throw new ServiceError(json && json.error === 'too many reports' ? 'Слишком много отметок, попробуйте позже' : 'Сервер приложения не ответил', status ? 'server' : 'network');
    return json;
}

/** Drivers' reports in [bbox] ([west, south, east, north]): [{id, kind, lat, lon, heading, created, confirmed, votes}]. */
export async function reportsIn(http, bbox) {
    const box = bbox.map((v) => v.toFixed(4)).join(',');
    const { status, json } = await getJson(http, `${SERVERS.own}/api/reports?bbox=${box}`, { timeout: 12000 });
    if (status !== 200 || !json) throw new ServiceError('Сервер приложения не ответил', status ? 'server' : 'network');
    return Array.isArray(json.reports) ? json.reports : [];
}

/** Reports [kind] at the car: {report, confirmed}. */
export function sendReport(http, { kind, point, heading, device }) {
    return ownPost(http, '/api/reports', { kind, lat: point[1], lon: point[0], heading: heading ?? null, device });
}

export function voteReport(http, id, device, still) {
    return ownPost(http, `/api/reports/${encodeURIComponent(id)}/vote`, { device, still });
}

/** «Не то нашлось»: the query, what was found and what was wanted. */
export function sendFeedback(http, note) {
    return ownPost(http, '/api/feedback', note);
}

/** Starts sharing a drive: {id, key, url}. */
export function shareTrip(http, body) {
    return ownPost(http, '/api/trips', body);
}

export function updateTrip(http, id, key, body) {
    return ownPost(http, `/api/trips/${encodeURIComponent(id)}`, { ...body, key }, 'PUT');
}

export function endTrip(http, id, key) {
    return ownPost(http, `/api/trips/${encodeURIComponent(id)}/end`, { key });
}

/** At most [max] points of [line], evenly along it, for sending the road ahead. */
export function thinLine(line, max = 300) {
    if (line.length <= max) return line.map(([x, y]) => [Math.round(x * 1e5) / 1e5, Math.round(y * 1e5) / 1e5]);
    const out = [];
    const step = (line.length - 1) / (max - 1);
    for (let i = 0; i < max; i++) {
        const [x, y] = line[Math.round(i * step)];
        out.push([Math.round(x * 1e5) / 1e5, Math.round(y * 1e5) / 1e5]);
    }
    return out;
}
