// The map: MapLibre with the hybrid, satellite or scheme look, the friends' round markers with
// their names, this phone's blue dot, and how sure each position is as a pale circle.

import * as maplibregl from '../lib/maplibre-gl.mjs';

import { bounds, circle } from './geo.js';
import { ago, escape, initials } from './words.js';

// Positions older than this are shown faded: the friend may be somewhere else by now.
export const STALE_MS = 20 * 60 * 1000;

/** Where the style files are; a test page can point them elsewhere. */
function styleUrl(name) {
    const override = new URLSearchParams(location.search).get('style');
    return override || `styles/${name}.json`;
}

export class MapView {
    constructor(container, { style = 'hybrid', center = [37.6176, 55.7558], zoom = 11, onFriend = () => {}, onMove = () => {} } = {}) {
        this.styleName = style;
        this.markers = new Map();           // friend id -> {marker, el}
        this.me = null;                     // {marker, fix}
        this.circles = { me: null, friends: [] };
        this.onFriend = onFriend;
        this.map = new maplibregl.Map({
            container,
            style: styleUrl(style),
            center,
            zoom,
            attributionControl: { compact: true },
            pitchWithRotate: false,
            dragRotate: false,
            fadeDuration: 150,
        });
        this.map.touchZoomRotate.disableRotation();
        this.map.on('style.load', () => this.addLayers());
        this.map.on('moveend', () => onMove({ center: this.map.getCenter().toArray(), zoom: this.map.getZoom() }));
        // The pictures of some places stop at a zoom; the map then shows the last one larger.
        this.map.on('error', (e) => {
            if (e?.error?.status !== 404) console.warn('map', e?.error?.message || e);
        });
    }

    setStyle(name) {
        if (name === this.styleName) return;
        this.styleName = name;
        this.map.setStyle(styleUrl(name));
    }

    /** The accuracy circles, re-made whenever a new style comes in. */
    addLayers() {
        if (!this.map.getSource('accuracy')) {
            this.map.addSource('accuracy', { type: 'geojson', data: this.circleData() });
        }
        if (!this.map.getLayer('accuracy-fill')) {
            this.map.addLayer({ id: 'accuracy-fill', type: 'fill', source: 'accuracy', paint: { 'fill-color': ['get', 'color'], 'fill-opacity': 0.14 } });
            this.map.addLayer({ id: 'accuracy-line', type: 'line', source: 'accuracy', paint: { 'line-color': ['get', 'color'], 'line-opacity': 0.5, 'line-width': 1.2 } });
        }
    }

    circleData() {
        const features = [];
        const add = (place, color) => {
            if (!place || !(place.accuracy > 15)) return;
            features.push({ type: 'Feature', properties: { color }, geometry: { type: 'Polygon', coordinates: [circle([place.lon, place.lat], Math.min(place.accuracy, 3000))] } });
        };
        if (this.circles.me) add(this.circles.me, '#2f80ff');
        for (const f of this.circles.friends) add(f.place, f.color);
        return { type: 'FeatureCollection', features };
    }

    updateCircles() {
        const source = this.map.getSource('accuracy');
        if (source) source.setData(this.circleData());
    }

    /** This phone's own position, as the blue dot. */
    setMe(fix) {
        if (!fix) return;
        if (!this.me) {
            const el = document.createElement('div');
            el.className = 'me-dot';
            el.innerHTML = '<span class="pulse"></span><span class="dot"></span>';
            this.me = { marker: new maplibregl.Marker({ element: el }).setLngLat([fix.lon, fix.lat]).addTo(this.map) };
        } else {
            this.me.marker.setLngLat([fix.lon, fix.lat]);
        }
        this.circles.me = fix;
        this.updateCircles();
    }

    /** The friends that have a position to show: [{id, name, color, place}], at server time [now]. */
    setFriends(friends, now) {
        const seen = new Set();
        for (const f of friends) {
            if (!f.place) continue;
            seen.add(f.id);
            let entry = this.markers.get(f.id);
            if (!entry) {
                const el = document.createElement('button');
                el.className = 'pin';
                el.addEventListener('click', (e) => {
                    e.stopPropagation();
                    this.onFriend(f.id);
                });
                entry = { el, marker: new maplibregl.Marker({ element: el, anchor: 'bottom' }).setLngLat([f.place.lon, f.place.lat]).addTo(this.map) };
                this.markers.set(f.id, entry);
            } else {
                entry.marker.setLngLat([f.place.lon, f.place.lat]);
            }
            const stale = now - f.place.time > STALE_MS;
            entry.el.classList.toggle('stale', stale);
            entry.el.dataset.id = f.id;
            entry.el.style.setProperty('--c', f.color);
            entry.el.innerHTML = `<span class="pin-name">${escape(f.name.split(' ')[0])}<small>${escape(ago(f.place.time, now))}</small></span><span class="pin-face">${escape(initials(f.name))}</span>`;
        }
        for (const [id, entry] of this.markers) {
            if (!seen.has(id)) {
                entry.marker.remove();
                this.markers.delete(id);
            }
        }
        this.circles.friends = friends.filter((f) => f.place);
        this.updateCircles();
    }

    /** Lifts one friend's marker over the rest. */
    highlight(id) {
        for (const [fid, entry] of this.markers) entry.el.classList.toggle('selected', fid === id);
    }

    flyTo(point, zoom = Math.max(this.map.getZoom(), 15)) {
        this.map.flyTo({ center: point, zoom, duration: 900, padding: this.padding || undefined });
    }

    /** Shows all of [points] at once. */
    fit(points) {
        const box = bounds(points);
        if (!box) return;
        if (points.length === 1) return this.flyTo(points[0], 15);
        this.map.fitBounds(box, { padding: this.padding || 60, maxZoom: 16, duration: 900 });
    }

    /** Room the panels over the map take, so things are centered in what is left. */
    setPadding(padding) {
        this.padding = padding;
    }

    resize() {
        this.map.resize();
    }
}
