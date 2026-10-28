// What the app keeps between launches: settings, home and work, saved places, recent searches,
// past drives, and what makes the next start quicker (the town, the servers to ask).

const KEY = 'shturman.v1';
const HISTORY = 15;
const TRIPS = 50;

const DEFAULTS = {
    settings: {
        voice: true,
        theme: 'auto',       // auto, day, night
        mapType: 'scheme',   // scheme, hybrid, satellite
        mode: 'car',         // car, bike, foot
        avoidTolls: false,
        avoidHighways: false,
        avoidFerries: false,
        demoSpeed: 2,
        cameras: true,       // speed cameras and limits
        speedTolerance: 10,  // km/h over the limit before the speed turns red
        streetNames: true,   // the voice names the street a turn leads onto
        bumps: true,         // speed bumps and railway crossings
        schools: true,
        curves: true,        // sharp bends on fast roads
        reports: true,       // what other drivers report, on the map and out loud
        prefetch: 'wifi',    // fetch the map along a route before the drive: wifi, always, off
        friends: true,       // friends from «Рядом» on the map, once linked
        hudMirror: true,     // the windscreen view is drawn mirrored
    },
    home: null,
    work: null,
    saved: [],
    recent: [],
    view: null,              // {center, zoom} where the map was last left
    parked: null,            // {lon, lat, time}: where the car was left at the end of a drive
    trips: [],               // past drives, the newest first: {dest, start, end, length, mode}
    device: null,            // an anonymous id for drivers' reports, made up on the first launch
    ryadom: null,            // {server, token, name}: a link to «Рядом» that only reads friends' places
    config: null,            // {servers, message, latest, at}: the last word from the app's server
    seenMessage: '',
    area: null,              // the town last searched in: {name, bbox, point}
    offline: [],             // map areas fetched for the road: {name, bbox, tiles, mb, at}
};

function load() {
    try {
        const raw = localStorage.getItem(KEY);
        if (!raw) return structuredClone(DEFAULTS);
        const data = JSON.parse(raw);
        return { ...structuredClone(DEFAULTS), ...data, settings: { ...DEFAULTS.settings, ...(data.settings || {}) } };
    } catch (e) {
        return structuredClone(DEFAULTS);
    }
}

export const store = {
    data: load(),

    save() {
        try {
            localStorage.setItem(KEY, JSON.stringify(this.data));
        } catch (e) {
            // Full or blocked storage: the app goes on, it just forgets.
        }
    },

    get settings() {
        return this.data.settings;
    },


    /** A place as kept: only what is needed to show it and go there. */
    keep(place) {
        const { title, subtitle, kind, lon, lat, id } = place;
        return { title, subtitle: subtitle || '', kind: kind || '', lon, lat, id: id || `${lon},${lat}` };
    },

    remember(place) {
        const p = this.keep(place);
        this.data.recent = [p, ...this.data.recent.filter((r) => r.id !== p.id)].slice(0, HISTORY);
        this.save();
    },

    forget(place) {
        this.data.recent = this.data.recent.filter((r) => r.id !== place.id);
        this.save();
    },

    isSaved(place) {
        return this.data.saved.some((s) => s.id === place.id);
    },

    toggleSaved(place) {
        if (this.isSaved(place)) this.data.saved = this.data.saved.filter((s) => s.id !== place.id);
        else this.data.saved = [this.keep(place), ...this.data.saved];
        this.save();
        return this.isSaved(place);
    },

    setHome(place) {
        this.data.home = place ? this.keep(place) : null;
        this.save();
    },

    setWork(place) {
        this.data.work = place ? this.keep(place) : null;
        this.save();
    },

    setParked(spot) {
        this.data.parked = spot;
        this.save();
    },

    setView(center, zoom) {
        this.data.view = { center, zoom };
        this.save();
    },

    /** The anonymous id drivers' reports go with: random letters, nothing about the person. */
    get device() {
        if (!this.data.device) {
            const bytes = new Uint8Array(16);
            crypto.getRandomValues(bytes);
            this.data.device = `d${Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')}`;
            this.save();
        }
        return this.data.device;
    },

    addTrip(trip) {
        this.data.trips = [{ ...trip, dest: this.keep(trip.dest) }, ...this.data.trips].slice(0, TRIPS);
        this.save();
    },

    set(name, value) {
        this.data.settings[name] = value;
        this.save();
    },

    put(key, value) {
        this.data[key] = value;
        this.save();
    },
};
