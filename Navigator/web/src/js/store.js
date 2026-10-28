// What the app keeps between launches: settings, home and work, saved places and recent searches.

const KEY = 'shturman.v1';
const HISTORY = 15;

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
    },
    home: null,
    work: null,
    saved: [],
    recent: [],
    view: null,              // {center, zoom} where the map was last left
    parked: null,            // {lon, lat, time}: where the car was left at the end of a drive
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

    set(name, value) {
        this.data.settings[name] = value;
        this.save();
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
};
