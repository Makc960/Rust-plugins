import assert from 'node:assert/strict';
import { test } from 'node:test';

import { parseIntent } from '../src/js/intents.js';
import { makeStyle } from '../tools/styles.mjs';

test('links from other apps become a place or a search', () => {
    assert.deepEqual(parseIntent('geo:55.75,37.62'), { point: [37.62, 55.75], label: '' });
    assert.deepEqual(parseIntent('geo:0,0?q=55.7539,37.6208(Красная площадь)'), { point: [37.6208, 55.7539], label: 'Красная площадь' });
    assert.deepEqual(parseIntent('geo:0,0?q=Тверская+улица+7'), { query: 'Тверская улица 7' });
    assert.deepEqual(parseIntent('geo:0,0?q=%D0%9C%D0%BE%D1%81%D0%BA%D0%B2%D0%B0'), { query: 'Москва' });
    assert.deepEqual(parseIntent('google.navigation:q=55.7,37.6'), { point: [37.6, 55.7], label: '' });
    assert.equal(parseIntent('geo:0,0'), null);
    assert.equal(parseIntent('https://example.com'), null);
});

test('map styles get Russian names and the real tile server', () => {
    const style = makeStyle({
        version: 8,
        sources: { openmaptiles: { type: 'vector', url: 'https://__TILEJSON_DOMAIN__/planet' } },
        glyphs: 'https://__TILEJSON_DOMAIN__/fonts/{fontstack}/{range}.pbf',
        layers: [
            { id: 'city', type: 'symbol', layout: { 'text-field': ['concat', ['get', 'name:latin'], '\n', ['get', 'name:nonlatin']] } },
            { id: 'shield', type: 'symbol', layout: { 'text-field': ['to-string', ['get', 'ref']] } },
        ],
    });
    assert.equal(style.sources.openmaptiles.url, 'https://tiles.openfreemap.org/planet');
    assert.deepEqual(style.layers[0].layout['text-field'], ['coalesce', ['get', 'name:ru'], ['get', 'name']]);
    assert.deepEqual(style.layers[1].layout['text-field'], ['to-string', ['get', 'ref']]);
});

test('the shipped styles are valid MapLibre styles', async () => {
    const { validateStyleMin } = await import('@maplibre/maplibre-gl-style-spec');
    const { loadStyles } = await import('../tools/styles.mjs');
    const styles = loadStyles(new URL('../vendor/openfreemap', import.meta.url).pathname);
    for (const [name, style] of Object.entries(styles)) {
        const errors = validateStyleMin(style);
        assert.deepEqual(errors.map((e) => e.message), [], name);
        assert.ok(!JSON.stringify(style).includes('name:latin'), `${name} still shows Latin names`);
    }
});
