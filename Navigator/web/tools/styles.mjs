// The map's looks: OpenFreeMap's Liberty and Dark styles for day and night, and satellite pictures
// with or without Liberty's roads and names over them (hybrid, satellite). Names are in Russian
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

export const SATELLITE = {
    type: 'raster',
    tiles: ['https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'],
    tileSize: 256,
    maxzoom: 19,
    attribution: 'Снимки © Esri, Maxar, Earthstar Geographics и сообщество ГИС',
};

// What the hybrid keeps of Liberty, and how roads look over a picture: big roads in yellow, the
// rest in see-through white, the way paper maps' hybrids draw them.
const ROADS = {
    road_motorway: 'rgba(255, 205, 80, 0.85)',
    road_motorway_link: 'rgba(255, 205, 80, 0.75)',
    road_trunk_primary: 'rgba(255, 220, 120, 0.75)',
    road_secondary_tertiary: 'rgba(255, 255, 255, 0.55)',
    road_link: 'rgba(255, 255, 255, 0.5)',
    road_minor: 'rgba(255, 255, 255, 0.4)',
    bridge_motorway: 'rgba(255, 205, 80, 0.85)',
    bridge_trunk_primary: 'rgba(255, 220, 120, 0.75)',
    bridge_secondary_tertiary: 'rgba(255, 255, 255, 0.55)',
    bridge_street: 'rgba(255, 255, 255, 0.4)',
};
const LINES = { boundary_3: 'rgba(255, 255, 255, 0.45)', boundary_2: 'rgba(255, 230, 160, 0.8)', boundary_disputed: 'rgba(255, 230, 160, 0.6)' };
const LABELS = new Set([
    'waterway_line_label', 'water_name_point_label', 'water_name_line_label', 'poi_transit', 'poi_r1',
    'highway-name-path', 'highway-name-minor', 'highway-name-major', 'airport',
    'label_other', 'label_village', 'label_town', 'label_state', 'label_city', 'label_city_capital',
    'label_country_3', 'label_country_2', 'label_country_1',
]);

/** Satellite pictures (Esri's World Imagery) with Liberty's roads, borders and names over them, or [labels] off. */
export function hybridStyle(liberty, { labels = true } = {}) {
    const from = makeStyle(liberty, { name: labels ? 'Навигатор: гибрид' : 'Навигатор: спутник' });
    const layers = [
        { id: 'background', type: 'background', paint: { 'background-color': '#0b1510' } },
        { id: 'satellite', type: 'raster', source: 'satellite', paint: { 'raster-fade-duration': 150 } },
    ];
    if (labels) {
        for (const layer of from.layers) {
            if (ROADS[layer.id]) {
                layers.push({ ...layer, paint: { ...layer.paint, 'line-color': ROADS[layer.id], 'line-opacity': 1 } });
            } else if (LINES[layer.id]) {
                layers.push({ ...layer, paint: { ...layer.paint, 'line-color': LINES[layer.id] } });
            } else if (LABELS.has(layer.id)) {
                const road = layer.id.startsWith('highway');
                layers.push({
                    ...layer,
                    paint: {
                        ...layer.paint,
                        'text-color': road ? '#fff4d6' : '#ffffff',
                        'text-halo-color': 'rgba(0, 0, 0, 0.8)',
                        'text-halo-width': 1.4,
                        'text-halo-blur': 0.5,
                    },
                });
            }
        }
    }
    return {
        version: 8,
        name: from.name,
        glyphs: from.glyphs,
        sprite: from.sprite,
        sources: { satellite: SATELLITE, ...(labels ? { openmaptiles: from.sources.openmaptiles } : {}) },
        layers,
    };
}

export function loadStyles(vendor) {
    const liberty = JSON.parse(readFileSync(`${vendor}/liberty.json`, 'utf8'));
    return {
        day: makeStyle(liberty, { name: 'Навигатор: день' }),
        night: makeStyle(JSON.parse(readFileSync(`${vendor}/dark.json`, 'utf8')), { name: 'Навигатор: ночь' }),
        hybrid: hybridStyle(liberty),
        satellite: hybridStyle(liberty, { labels: false }),
    };
}
