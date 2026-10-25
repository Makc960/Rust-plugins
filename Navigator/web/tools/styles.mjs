// The map's day and night looks: OpenFreeMap's Liberty and Dark styles, with names in Russian
// where the map has them (and the local name elsewhere) instead of Latin over Cyrillic.

import { readFileSync } from 'node:fs';

export const TILES = 'tiles.openfreemap.org';

const RUSSIAN = ['coalesce', ['get', 'name:ru'], ['get', 'name']];

function usesName(expr) {
    return JSON.stringify(expr).includes('"name');
}

export function makeStyle(source, { domain = TILES, name } = {}) {
    const style = JSON.parse(JSON.stringify(source).replaceAll('__TILEJSON_DOMAIN__', domain));
    if (name) style.name = name;
    for (const layer of style.layers) {
        const field = layer.layout && layer.layout['text-field'];
        if (field && usesName(field)) layer.layout['text-field'] = RUSSIAN;
    }
    return style;
}

export function loadStyles(vendor) {
    return {
        day: makeStyle(JSON.parse(readFileSync(`${vendor}/liberty.json`, 'utf8')), { name: 'Штурман: день' }),
        night: makeStyle(JSON.parse(readFileSync(`${vendor}/dark.json`, 'utf8')), { name: 'Штурман: ночь' }),
    };
}
