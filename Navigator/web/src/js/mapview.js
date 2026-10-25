// The map: MapLibre with the day or night style, the routes on it, the car's marker and the camera
// that follows it.

import * as maplibregl from '../lib/maplibre-gl.mjs';

import { along, bearing, lerp, slice, turn } from './geo.js';

const COLORS = {
    day: { route: '#2f6fed', casing: '#1846a8', alt: '#9db7e8', altCasing: '#6d8cc6', passed: '#a7b1c2', arrow: '#ffffff', arrowCasing: '#14213a' },
    night: { route: '#4f8dff', casing: '#1a3f8f', alt: '#4c5f86', altCasing: '#2c3a57', passed: '#556070', arrow: '#ffffff', arrowCasing: '#0b1220' },
};

const NAV_PITCH = 55;

/** Where the style files are; a test page can point them elsewhere. */
function styleUrl(theme) {
    const override = new URLSearchParams(location.search).get('style');
    return override || `styles/${theme}.json`;
}

function metersPerPixel(lat, zoom) {
    return (40075016.686 * Math.cos((lat * Math.PI) / 180)) / (512 * 2 ** zoom);
}

export class MapView {
    constructor(container, { theme = 'day', center = [37.6176, 55.7558], zoom = 11 } = {}) {
        this.theme = theme;
        this.mode = 'free';                 // free, follow (north up on the car), nav (behind the car), overview
        this.handlers = {};
        this.routes = [];
        this.selected = 0;
        this.active = null;                 // the route being driven
        this.progress = 0;
        this.maneuver = null;
        this.fix = null;
        this.shown = null;                  // {pos, heading} drawn this frame
        this.anim = null;
        this.navZoom = 17;
        this.cameraPoints = [];
        this.stopMarkers = [];

        this.map = new maplibregl.Map({
            container,
            style: styleUrl(theme),
            center,
            zoom,
            attributionControl: { compact: true },
            maxPitch: 65,
            fadeDuration: 120,
            clickTolerance: 4,
        });
        this.map.touchZoomRotate.enableRotation();

        this.map.on('style.load', () => this.setupLayers());
        this.map.on('movestart', (e) => {
            if (e.originalEvent && this.mode !== 'free') {
                const was = this.mode;
                this.mode = 'free';
                this.emit('userMoved', was);
            }
        });
        this.map.on('rotate', () => this.emit('rotate', this.map.getBearing()));
        this.map.on('zoom', () => this.updateHalo());
        this.map.on('click', (e) => this.onClick(e));
        this.map.on('error', (e) => this.emit('error', e && e.error));
        this.map.on('moveend', () => this.emit('moveend'));
        this.setupLongPress(container);

        this.puck = this.makePuck();
        this.puckMarker = new maplibregl.Marker({ element: this.puck, rotationAlignment: 'map', pitchAlignment: 'map' });
        this.destMarker = null;
        this.frame = this.frame.bind(this);
        requestAnimationFrame(this.frame);
    }

    on(name, fn) {
        this.handlers[name] = fn;
    }

    emit(name, ...args) {
        if (this.handlers[name]) this.handlers[name](...args);
    }

    // ---- Style and layers ----

    setTheme(theme) {
        if (theme === this.theme) return;
        this.theme = theme;
        this.map.setStyle(styleUrl(theme));
    }

    /** The layer our routes go under: the first label above the roads and buildings. */
    labelLayer() {
        const layers = this.map.getStyle().layers || [];
        let last = -1;
        layers.forEach((l, i) => {
            if (l['source-layer'] === 'transportation' || l['source-layer'] === 'building') last = i;
        });
        const label = layers.find((l, i) => i > last && l.type === 'symbol');
        return label ? label.id : undefined;
    }

    setupLayers() {
        const m = this.map;
        const c = COLORS[this.theme] || COLORS.day;
        const empty = { type: 'FeatureCollection', features: [] };
        const before = this.labelLayer();
        if (!m.hasImage('shturman-arrowhead')) m.addImage('shturman-arrowhead', arrowheadImage(c), { pixelRatio: 2 });

        m.addSource('alts', { type: 'geojson', data: empty });
        m.addSource('route', { type: 'geojson', data: empty, lineMetrics: true });
        m.addSource('maneuver', { type: 'geojson', data: empty });

        const round = { 'line-cap': 'round', 'line-join': 'round' };
        const width = (w) => ['interpolate', ['exponential', 1.5], ['zoom'], 10, w * 0.45, 16, w, 19, w * 2.2];
        m.addLayer({ id: 'alts-casing', type: 'line', source: 'alts', layout: round, paint: { 'line-color': c.altCasing, 'line-width': width(11) } }, before);
        m.addLayer({ id: 'alts-line', type: 'line', source: 'alts', layout: round, paint: { 'line-color': c.alt, 'line-width': width(7.5) } }, before);
        m.addLayer({ id: 'alts-hit', type: 'line', source: 'alts', paint: { 'line-color': '#000', 'line-opacity': 0, 'line-width': 28 } }, before);
        m.addLayer({ id: 'route-casing', type: 'line', source: 'route', layout: round, paint: { 'line-color': c.casing, 'line-width': width(12) } }, before);
        m.addLayer({ id: 'route-line', type: 'line', source: 'route', layout: round, paint: { 'line-color': c.route, 'line-width': width(8.5) } }, before);
        // Speed cameras on the route: red dots with a white ring, from close enough to matter.
        m.addSource('cameras', { type: 'geojson', data: collection(this.cameraPoints.map((p) => ({ type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: p } }))) });
        m.addLayer({
            id: 'cameras', type: 'circle', source: 'cameras', minzoom: 11,
            paint: {
                'circle-color': '#e5484d', 'circle-stroke-color': '#ffffff', 'circle-stroke-width': 2.5,
                'circle-radius': ['interpolate', ['linear'], ['zoom'], 11, 3, 16, 7, 19, 10],
                'circle-pitch-alignment': 'map',
            },
        });
        m.addLayer({
            id: 'maneuver-casing', type: 'line', source: 'maneuver', filter: ['==', ['geometry-type'], 'LineString'], layout: round,
            paint: { 'line-color': c.arrowCasing, 'line-width': width(10), 'line-opacity': ['interpolate', ['linear'], ['zoom'], 13, 0, 14, 1] },
        });
        m.addLayer({
            id: 'maneuver-line', type: 'line', source: 'maneuver', filter: ['==', ['geometry-type'], 'LineString'], layout: round,
            paint: { 'line-color': c.arrow, 'line-width': width(5.5), 'line-opacity': ['interpolate', ['linear'], ['zoom'], 13, 0, 14, 1] },
        });
        m.addLayer({
            id: 'maneuver-head', type: 'symbol', source: 'maneuver', filter: ['==', ['geometry-type'], 'Point'],
            layout: {
                'icon-image': 'shturman-arrowhead', 'icon-rotate': ['get', 'bearing'], 'icon-rotation-alignment': 'map',
                'icon-pitch-alignment': 'map', 'icon-allow-overlap': true, 'icon-ignore-placement': true,
                'icon-size': ['interpolate', ['exponential', 1.5], ['zoom'], 14, 0.55, 16, 1, 19, 2.2],
            },
            paint: { 'icon-opacity': ['interpolate', ['linear'], ['zoom'], 13, 0, 14, 1] },
        });
        this.redraw();
        this.emit('styleReady');
    }

    redraw() {
        if (!this.map.getSource('route')) return;
        if (this.active) {
            this.map.getSource('alts').setData(collection([]));
            this.map.getSource('route').setData(lineFeature(this.active.coords));
            this.paintProgress();
            this.drawManeuver();
        } else {
            const alts = this.routes.map((r, idx) => ({ r, idx })).filter((x) => x.idx !== this.selected);
            this.map.getSource('alts').setData(collection(alts.map(({ r, idx }) => lineFeature(r.coords, { idx }))));
            const main = this.routes[this.selected];
            this.map.getSource('route').setData(main ? lineFeature(main.coords) : collection([]));
            this.map.setPaintProperty('route-line', 'line-gradient', null);
            this.map.getSource('maneuver').setData(collection([]));
        }
    }

    // ---- Routes ----

    /** Routes to choose from: the chosen one bold, the others pale and tappable. */
    showRoutes(routes, selected = 0, padding) {
        this.routes = routes;
        this.selected = selected;
        this.active = null;
        this.redraw();
        if (routes[selected] && padding) this.fitBounds(routes[selected].bbox, padding);
    }

    selectRoute(i) {
        this.selected = i;
        this.redraw();
    }

    /** The route being driven, greyed behind the car. */
    drive(route) {
        this.active = route;
        this.routes = [];
        this.progress = 0;
        this.maneuver = null;
        this.redraw();
    }

    clearRoutes() {
        this.routes = [];
        this.active = null;
        this.maneuver = null;
        this.redraw();
    }

    setProgress(d) {
        this.progress = d;
        this.paintProgress();
    }

    paintProgress() {
        if (!this.active || !this.map.getLayer('route-line')) return;
        const c = COLORS[this.theme] || COLORS.day;
        const f = Math.max(0, Math.min(0.999, this.progress / this.active.length));
        this.map.setPaintProperty('route-line', 'line-gradient', f > 0.0005
            ? ['step', ['line-progress'], c.passed, f, c.route]
            : ['step', ['line-progress'], c.route, 1, c.route]);
    }

    /** The white arrow over the route at the next manoeuvre. */
    setManeuver(step) {
        this.maneuver = step;
        this.drawManeuver();
    }

    drawManeuver() {
        const src = this.map.getSource('maneuver');
        if (!src) return;
        const r = this.active;
        const s = this.maneuver;
        if (!r || !s || s.type === 'arrive' || s.type === 'depart') {
            src.setData(collection([]));
            return;
        }
        const line = slice(r.coords, r.cum, Math.max(0, s.at - 30), Math.min(r.length, s.at + 22));
        if (line.length < 2) {
            src.setData(collection([]));
            return;
        }
        const end = line[line.length - 1];
        const head = bearing(line[line.length - 2], end);
        src.setData(collection([
            lineFeature(line),
            { type: 'Feature', properties: { bearing: head }, geometry: { type: 'Point', coordinates: end } },
        ]));
    }

    // ---- Markers ----

    makePuck() {
        const el = document.createElement('div');
        el.className = 'puck';
        el.innerHTML = '<div class="puck-halo"></div><div class="puck-body"><svg viewBox="0 0 40 40"><circle class="puck-ring" cx="20" cy="20" r="15"/><path class="puck-arrow" d="M20 8 29 29 20 24.5 11 29z"/><path class="puck-chevron" d="M20 3 34 35 20 27.5 6 35z"/></svg></div>';
        return el;
    }

    /** Speed cameras to show, as points. */
    setCameras(points) {
        this.cameraPoints = points;
        const src = this.map.getSource('cameras');
        if (src) src.setData(collection(points.map((p) => ({ type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: p } }))));
    }

    /** Stops on the way: small numbered pins. */
    setStops(points) {
        for (const m of this.stopMarkers) m.remove();
        this.stopMarkers = points.map((p, i) => {
            const el = document.createElement('div');
            el.className = 'stop-pin';
            el.innerHTML = `<svg viewBox="0 0 30 40"><path d="M15 1.5C7.5 1.5 1.5 7.4 1.5 14.6c0 9.9 13.5 23.9 13.5 23.9s13.5-14 13.5-23.9C28.5 7.4 22.5 1.5 15 1.5z"/></svg><span>${i + 1}</span>`;
            return new maplibregl.Marker({ element: el, anchor: 'bottom' }).setLngLat(p).addTo(this.map);
        });
    }

    setDestination(point) {
        if (!point) {
            if (this.destMarker) this.destMarker.remove();
            this.destMarker = null;
            return;
        }
        if (!this.destMarker) {
            const el = document.createElement('div');
            el.className = 'dest-pin';
            el.innerHTML = '<svg viewBox="0 0 36 48"><path d="M18 2C9.2 2 2 9 2 17.6 2 29.4 18 46 18 46s16-16.6 16-28.4C34 9 26.8 2 18 2z"/><circle cx="18" cy="17.5" r="6.5"/></svg>';
            this.destMarker = new maplibregl.Marker({ element: el, anchor: 'bottom' });
        }
        this.destMarker.setLngLat(point).addTo(this.map);
    }

    // ---- The car and the camera ----

    /** A new position: [pos] where to draw it (on the route when on it), [heading] the way it points. */
    moveTo(fix, pos, heading) {
        const now = performance.now();
        const first = !this.shown;
        if (first) {
            this.shown = { pos, heading: heading ?? 0 };
            this.puckMarker.setLngLat(pos).addTo(this.map);
        }
        const prev = this.fix;
        this.fix = fix;
        const gap = prev ? Math.max(250, Math.min(1500, fix.time - prev.time)) : 0;
        this.anim = {
            from: { pos: this.shown.pos, heading: this.shown.heading },
            to: { pos, heading: heading ?? this.shown.heading },
            start: now,
            dur: first ? 1 : gap,
            cameraFrames: 2,
        };
        this.puck.classList.toggle('has-heading', heading != null);
        this.updateHalo();
        if (this.mode === 'follow') this.map.easeTo({ center: pos, duration: first ? 0 : 600 });
    }

    hidePuck() {
        this.puckMarker.remove();
        this.shown = null;
        this.fix = null;
    }

    updateHalo() {
        const halo = this.puck.firstChild;
        if (!this.fix || this.mode === 'nav') {
            halo.style.width = halo.style.height = '0px';
            return;
        }
        const px = (2 * Math.min(this.fix.accuracy || 0, 500)) / metersPerPixel(this.fix.lat, this.map.getZoom());
        const size = px < 36 ? 0 : Math.min(px, 600);
        halo.style.width = halo.style.height = `${size}px`;
    }

    frame(now) {
        requestAnimationFrame(this.frame);
        const a = this.anim;
        if (!a || !this.shown) return;
        const t = Math.min(1, (now - a.start) / a.dur);
        const pos = lerp(a.from.pos, a.to.pos, t);
        const heading = (a.from.heading + turn(a.from.heading, a.to.heading) * t + 360) % 360;
        this.shown = { pos, heading };
        this.puckMarker.setLngLat(pos);
        this.puckMarker.setRotation(heading);
        if (this.mode === 'nav') {
            const z = this.map.getZoom();
            const settled = Math.abs(this.navZoom - z) < 0.01;
            // Once the car and the zoom are where they should be, the map is left still to save the battery.
            if (t < 1 || !settled || a.cameraFrames-- > 0) {
                const zoom = settled ? this.navZoom : z + (this.navZoom - z) * 0.04;
                this.map.jumpTo({ center: pos, bearing: heading, zoom, pitch: NAV_PITCH });
            }
        } else if (t >= 1) {
            this.anim = null;
        }
    }

    /** How close the camera comes at [speed] m/s. */
    setNavZoom(speed, mode) {
        if (mode === 'foot') this.navZoom = 18;
        else if (speed == null || speed < 8) this.navZoom = 17.2;
        else if (speed < 14) this.navZoom = 16.7;
        else if (speed < 22) this.navZoom = 16.1;
        else if (speed < 30) this.navZoom = 15.5;
        else this.navZoom = 15;
    }

    /** Where the car sits on screen while driving: pushed down, and right of a side panel. */
    setNavPadding(padding) {
        this.navPadding = padding;
        if (this.mode === 'nav') this.map.setPadding(padding);
    }

    setMode(mode) {
        const was = this.mode;
        this.mode = mode;
        const h = this.map.getContainer().clientHeight;
        if (mode === 'nav') {
            this.map.setPadding(this.navPadding || { top: Math.round(h * 0.42), bottom: 0, left: 0, right: 0 });
            if (this.shown) {
                this.map.easeTo({ center: this.shown.pos, bearing: this.shown.heading, pitch: NAV_PITCH, zoom: this.navZoom, duration: 700 });
            }
        } else {
            if (was === 'nav') this.map.setPadding({ top: 0, bottom: 0, left: 0, right: 0 });
            if (mode === 'follow' && this.shown) {
                this.map.easeTo({ center: this.shown.pos, zoom: Math.max(this.map.getZoom(), 15), bearing: 0, pitch: 0, duration: 700 });
            }
        }
        this.updateHalo();
    }

    /** Shows [bbox] ([[w, s], [e, n]]) between the panels around it. */
    fitBounds(bbox, padding = { top: 80, bottom: 80, left: 40, right: 40 }) {
        const c = this.map.getContainer();
        const fit = {
            top: Math.min(padding.top, c.clientHeight * 0.45), bottom: Math.min(padding.bottom, c.clientHeight * 0.5),
            left: Math.min(padding.left, c.clientWidth * 0.6), right: Math.min(padding.right, c.clientWidth * 0.3),
        };
        this.map.setPadding({ top: 0, bottom: 0, left: 0, right: 0 });
        this.map.fitBounds(bbox, { padding: fit, bearing: 0, pitch: 0, duration: 800, maxZoom: 17 });
    }

    /** The rest of the route from the car to the end, for a look ahead. */
    overview(padding) {
        if (!this.active) return;
        this.mode = 'overview';
        const r = this.active;
        const rest = slice(r.coords, r.cum, this.progress, r.length);
        const here = this.shown ? [this.shown.pos] : [];
        let w = Infinity, s = Infinity, e = -Infinity, n = -Infinity;
        for (const [x, y] of [...rest, ...here]) {
            w = Math.min(w, x); e = Math.max(e, x); s = Math.min(s, y); n = Math.max(n, y);
        }
        this.fitBounds([[w, s], [e, n]], padding);
        this.updateHalo();
    }

    flyTo(center, zoom = 16) {
        this.mode = 'free';
        this.map.easeTo({ center, zoom: Math.max(zoom, this.map.getZoom()), duration: 800 });
    }

    resetNorth() {
        this.map.easeTo({ bearing: 0, pitch: 0, duration: 400 });
    }

    // ---- Touches ----

    onClick(e) {
        const m = this.map;
        const alts = m.getLayer('alts-hit') ? m.queryRenderedFeatures(e.point, { layers: ['alts-hit'] }) : [];
        if (alts.length) {
            this.emit('routeTap', alts[0].properties.idx);
            return;
        }
        const poiLayers = (m.getStyle().layers || []).filter((l) => l.type === 'symbol' && (l['source-layer'] === 'poi' || l['source-layer'] === 'place')).map((l) => l.id);
        const hit = poiLayers.length ? m.queryRenderedFeatures(e.point, { layers: poiLayers }) : [];
        const f = hit.find((x) => x.properties && x.properties.name);
        if (f) {
            const coords = f.geometry.type === 'Point' ? f.geometry.coordinates : [e.lngLat.lng, e.lngLat.lat];
            this.emit('poiTap', { name: f.properties['name:ru'] || f.properties.name, point: coords, layer: f.sourceLayer, cls: f.properties.class });
            return;
        }
        this.emit('tap', [e.lngLat.lng, e.lngLat.lat]);
    }

    setupLongPress(container) {
        let timer = null;
        let start = null;
        const cancel = () => {
            clearTimeout(timer);
            timer = null;
        };
        container.addEventListener('touchstart', (e) => {
            cancel();
            if (e.touches.length !== 1) return;
            const t = e.touches[0];
            start = [t.clientX, t.clientY];
            timer = setTimeout(() => {
                timer = null;
                const rect = container.getBoundingClientRect();
                const ll = this.map.unproject([start[0] - rect.left, start[1] - rect.top]);
                this.emit('longPress', [ll.lng, ll.lat]);
            }, 550);
        }, { passive: true });
        container.addEventListener('touchmove', (e) => {
            if (!timer) return;
            const t = e.touches[0];
            if (e.touches.length > 1 || Math.hypot(t.clientX - start[0], t.clientY - start[1]) > 10) cancel();
        }, { passive: true });
        container.addEventListener('touchend', cancel, { passive: true });
        container.addEventListener('touchcancel', cancel, { passive: true });
        this.map.on('contextmenu', (e) => this.emit('longPress', [e.lngLat.lng, e.lngLat.lat]));
    }

    get center() {
        const c = this.map.getCenter();
        return [c.lng, c.lat];
    }

    get zoom() {
        return this.map.getZoom();
    }

    /** The point [d] metres along the driven route, for markers. */
    pointAlong(d) {
        return this.active ? along(this.active.coords, this.active.cum, d).point : null;
    }
}

function lineFeature(coords, properties = {}) {
    return { type: 'Feature', properties, geometry: { type: 'LineString', coordinates: coords } };
}

function collection(features) {
    return { type: 'FeatureCollection', features };
}

/** A white arrowhead with a dark edge, drawn once for the manoeuvre arrow. */
function arrowheadImage(c) {
    const size = 64;
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = size;
    const g = canvas.getContext('2d');
    g.lineJoin = 'round';
    g.beginPath();
    g.moveTo(32, 8);
    g.lineTo(56, 50);
    g.lineTo(8, 50);
    g.closePath();
    g.lineWidth = 8;
    g.strokeStyle = c.arrowCasing;
    g.stroke();
    g.fillStyle = c.arrow;
    g.fill();
    return g.getImageData(0, 0, size, size);
}
