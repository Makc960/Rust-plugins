import assert from 'node:assert/strict';
import { test } from 'node:test';

import { parseCoordinates, placeFromPhoton, osrmRequest, searchUrl, valhallaRequest } from '../src/js/services.js';
import { clockText, distanceText, durationText, forDistance, forTime, inDistance, plural, roundSpoken, stepSaid, stepText, streetAfterNa } from '../src/js/words.js';

test('Russian plurals', () => {
    const w = ['метр', 'метра', 'метров'];
    assert.deepEqual([1, 2, 5, 11, 12, 21, 22, 25, 101, 111].map((n) => plural(n, w)),
        ['метр', 'метра', 'метров', 'метров', 'метров', 'метр', 'метра', 'метров', 'метр', 'метров']);
});

test('distances and times on screen', () => {
    assert.equal(distanceText(7), '5 м');
    assert.equal(distanceText(143), '140 м');
    assert.equal(distanceText(380), '400 м');
    assert.equal(distanceText(1234), '1,2 км');
    assert.equal(distanceText(23456), '23 км');
    assert.equal(durationText(20), '1 мин');
    assert.equal(durationText(25 * 60), '25 мин');
    assert.equal(durationText(85 * 60), '1 ч 25 мин');
    assert.equal(durationText(120 * 60), '2 ч');
    assert.equal(clockText(new Date(2026, 0, 1, 9, 5)), '9:05');
});

test('distances and times out loud', () => {
    assert.equal(inDistance(310), 'через 300 метров');
    assert.equal(inDistance(52), 'через 50 метров');
    assert.equal(inDistance(1020), 'через километр');
    assert.equal(inDistance(1600), 'через полтора километра');
    assert.equal(inDistance(2100), 'через 2 километра');
    assert.equal(inDistance(5200), 'через 5 километров');
    assert.equal(roundSpoken(260), 250);
    assert.equal(forDistance(6100), '6 километров');
    assert.equal(forDistance(1100), 'километр');
    assert.equal(forDistance(2200), '2 километра');
    assert.equal(forTime(25 * 60), '25 минут');
    assert.equal(forTime(61 * 60), 'час 1 минута');
    assert.equal(forTime(122 * 60), '2 часа 2 минуты');
});

test('manoeuvres in words', () => {
    assert.equal(stepText({ type: 'turn', modifier: 'right' }), 'Направо');
    assert.equal(stepText({ type: 'roundabout', exit: 3 }), 'Кольцо, 3-й съезд');
    assert.equal(stepText({ type: 'arrive', modifier: 'left' }), 'Финиш слева');
    assert.equal(stepSaid({ type: 'roundabout', exit: 3 }), 'на круговом движении третий съезд');
    assert.equal(stepSaid({ type: 'fork', modifier: 'left' }), 'держитесь левее');
    assert.equal(stepSaid({ type: 'namechange', modifier: 'straight' }), null);
});

test('places from Photon read like addresses', () => {
    const cafe = placeFromPhoton({
        geometry: { coordinates: [37.6, 55.76] },
        properties: { name: 'Кофемания', street: 'Большая Никитская улица', housenumber: '13', city: 'Москва', osm_key: 'amenity', osm_value: 'cafe' },
    });
    assert.equal(cafe.title, 'Кофемания');
    assert.equal(cafe.subtitle, 'Большая Никитская улица, 13, Москва');
    assert.equal(cafe.kind, 'Кафе');
    const house = placeFromPhoton({
        geometry: { coordinates: [30.3, 59.9] },
        properties: { street: 'Невский проспект', housenumber: '28', city: 'Санкт-Петербург', district: 'Центральный район', osm_key: 'building', osm_value: 'yes' },
    });
    assert.equal(house.title, 'Невский проспект, 28');
    assert.equal(house.subtitle, 'Центральный район, Санкт-Петербург');
});

test('coordinates can be typed straight in', () => {
    assert.deepEqual(parseCoordinates('55.7539, 37.6208').lat, 55.7539);
    assert.equal(parseCoordinates('55,7539 37,6208').lon, 37.6208);
    assert.equal(parseCoordinates('Тверская 7'), null);
    assert.equal(parseCoordinates('95, 37'), null);
});

test('requests to the servers', () => {
    const url = valhallaRequest([37.6, 55.75], [37.7, 55.8], 'car', { avoidTolls: true, heading: 92.4 });
    const body = JSON.parse(decodeURIComponent(url.split('json=')[1]));
    assert.equal(body.costing, 'auto');
    assert.equal(body.costing_options.auto.use_tolls, 0);
    assert.equal(body.locations[0].heading, 92);
    assert.equal(body.locations[1].lon, 37.7);
    assert.equal(body.alternates, 2);
    assert.equal(body.directions_options.language, 'ru-RU');
    assert.equal(JSON.parse(decodeURIComponent(valhallaRequest([1, 2], [3, 4], 'foot', { alternates: 0 }).split('json=')[1])).alternates, undefined);
    assert.match(osrmRequest([37.6, 55.75], [37.7, 55.8], 'bike'), /\/routed-bike\/route\/v1\/driving\/37\.600000,55\.750000;37\.700000,55\.800000\?/);
    assert.match(searchUrl('кафе', [37.6, 55.75]), /q=%D0%BA%D0%B0%D1%84%D0%B5.*lat=55\.75000&lon=37\.60000/);
});

test('streets after "на", as Russian has them', () => {
    const cases = {
        'улица Северная': 'улицу Северную', 'Ставропольская улица': 'Ставропольскую улицу', 'улица Большая Садовая': 'улицу Большую Садовую',
        'Красная площадь': 'Красную площадь', 'площадь Ленина': 'площадь Ленина', 'улица Героя Сарабьева': 'улицу Героя Сарабьева',
        'проспект Мира': 'проспект Мира', 'Московское шоссе': 'Московское шоссе', 'Почтовый переулок': 'Почтовый переулок',
        'Тенистая аллея': 'Тенистую аллею', 'Верхняя улица': 'Верхнюю улицу', '1-я Линия': '1-ю Линию', 'Кольцевая дорога': 'Кольцевую дорогу',
        'улица 40 лет Победы': 'улицу 40 лет Победы', 'М-4 «Дон»': 'М-4 «Дон»',
        'Набережная улица': 'Набережную улицу', 'улица Набережная': 'улицу Набережную', 'набережная реки Кубань': 'набережную реки Кубань',
    };
    for (const [name, after] of Object.entries(cases)) assert.equal(streetAfterNa(name), after, name);
});
