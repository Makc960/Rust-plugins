// Russian words for distances, times and manoeuvres: on screen, short; out loud, as a person says them.

export function plural(n, [one, few, many]) {
    const a = Math.abs(n) % 100;
    const b = a % 10;
    if (a > 10 && a < 20) return many;
    if (b === 1) return one;
    if (b >= 2 && b <= 4) return few;
    return many;
}

// ---- On screen ----

export function distanceText(m) {
    if (m < 1000) {
        const step = m < 50 ? 5 : m < 200 ? 10 : 50;
        return `${Math.max(0, Math.round(m / step) * step)} м`;
    }
    const km = m / 1000;
    return km < 10 ? `${km.toFixed(1).replace('.', ',')} км` : `${Math.round(km)} км`;
}

export function durationText(s) {
    const min = Math.max(1, Math.round(s / 60));
    if (min < 60) return `${min} мин`;
    const h = Math.floor(min / 60);
    const m = min % 60;
    return m ? `${h} ч ${m} мин` : `${h} ч`;
}

export function clockText(date) {
    return `${date.getHours()}:${String(date.getMinutes()).padStart(2, '0')}`;
}

const SCREEN = {
    'turn:right': 'Направо', 'turn:left': 'Налево',
    'turn:slight-right': 'Плавно направо', 'turn:slight-left': 'Плавно налево',
    'turn:sharp-right': 'Резко направо', 'turn:sharp-left': 'Резко налево',
    'turn:uturn': 'Разворот', 'turn:straight': 'Прямо',
    'continue:straight': 'Прямо',
    'ramp:right': 'Съезд направо', 'ramp:left': 'Съезд налево', 'ramp:straight': 'Съезд прямо',
    'exit:right': 'Съезд направо', 'exit:left': 'Съезд налево',
    'fork:right': 'Держитесь правее', 'fork:left': 'Держитесь левее', 'fork:straight': 'Держитесь прямо',
    'merge:right': 'Перестройтесь правее', 'merge:left': 'Перестройтесь левее', 'merge:straight': 'Выезд на дорогу',
    ferry: 'Паром', 'ferry-exit': 'Съезд с парома',
    depart: 'Начало маршрута',
};

/** What a step asks for, as the banner shows it. */
export function stepText(s) {
    if (s.type === 'roundabout') return s.exit ? `Кольцо, ${s.exit}-й съезд` : 'Круговое движение';
    if (s.type === 'arrive') return s.modifier === 'right' ? 'Финиш справа' : s.modifier === 'left' ? 'Финиш слева' : 'Финиш';
    if (s.type === 'waypoint') return s.modifier === 'right' ? 'Заезд справа' : s.modifier === 'left' ? 'Заезд слева' : 'Заезд';
    return SCREEN[`${s.type}:${s.modifier}`] || SCREEN[s.type] || 'Прямо';
}

// ---- Out loud ----

const ORDINALS = ['', 'первый', 'второй', 'третий', 'четвёртый', 'пятый', 'шестой', 'седьмой', 'восьмой', 'девятый', 'десятый'];

const SAY = {
    'turn:right': 'поверните направо', 'turn:left': 'поверните налево',
    'turn:slight-right': 'плавно поверните направо', 'turn:slight-left': 'плавно поверните налево',
    'turn:sharp-right': 'резко поверните направо', 'turn:sharp-left': 'резко поверните налево',
    'turn:uturn': 'развернитесь', 'turn:straight': 'проезжайте прямо',
    'ramp:right': 'съезжайте направо', 'ramp:left': 'съезжайте налево', 'ramp:straight': 'держитесь прямо',
    'exit:right': 'съезжайте направо', 'exit:left': 'съезжайте налево',
    'fork:right': 'держитесь правее', 'fork:left': 'держитесь левее', 'fork:straight': 'держитесь прямо',
    'merge:right': 'перестройтесь правее', 'merge:left': 'перестройтесь левее', 'merge:straight': 'выезжайте на основную дорогу',
    ferry: 'заезжайте на паром',
};

// The same, after "затем": short, as a person adds the next turn.
const THEN = {
    'turn:right': 'направо', 'turn:left': 'налево', 'turn:slight-right': 'плавно направо', 'turn:slight-left': 'плавно налево',
    'turn:sharp-right': 'резко направо', 'turn:sharp-left': 'резко налево', 'turn:uturn': 'разворот',
    'ramp:right': 'съезд направо', 'ramp:left': 'съезд налево', 'exit:right': 'съезд направо', 'exit:left': 'съезд налево',
    'fork:right': 'правее', 'fork:left': 'левее',
};

/** What to do at a step, as a voice says it ("поверните направо"), or null for steps not spoken. */
export function stepSaid(s) {
    if (s.type === 'roundabout') {
        return s.exit && s.exit <= 10 ? `на круговом движении ${ORDINALS[s.exit]} съезд` : 'въезжайте на круговое движение';
    }
    if (s.type === 'arrive') return 'вы приедете';
    return SAY[`${s.type}:${s.modifier}`] || SAY[s.type] || null;
}

export function thenSaid(s) {
    if (s.type === 'roundabout') return s.exit && s.exit <= 10 ? `на кольце ${ORDINALS[s.exit]} съезд` : 'круговое движение';
    if (s.type === 'arrive') return 'финиш';
    if (s.type === 'waypoint') return 'заезд';
    return THEN[`${s.type}:${s.modifier}`] || null;
}

// Distances a voice rounds to: what the driver can picture, never more than is left.
const SPOKEN = [50, 100, 150, 200, 250, 300, 400, 500, 600, 700, 800, 1000, 1500, 2000, 2500, 3000, 4000, 5000];

export function roundSpoken(m) {
    if (m > 5500) return Math.round(m / 1000) * 1000;
    let best = SPOKEN[0];
    for (const v of SPOKEN) if (v <= m * 1.1) best = v;
    return best;
}

/** "через 300 метров", "через полтора километра". */
export function inDistance(m) {
    const v = roundSpoken(m);
    if (v < 1000) return `через ${v} ${plural(v, ['метр', 'метра', 'метров'])}`;
    if (v === 1000) return 'через километр';
    if (v === 1500) return 'через полтора километра';
    if (v === 2500) return 'через два с половиной километра';
    const km = v / 1000;
    return `через ${km} ${plural(km, ['километр', 'километра', 'километров'])}`;
}

/** "5 километров", "800 метров", for how long a road goes on. */
export function forDistance(m) {
    if (m < 1000) {
        const v = Math.round(m / 100) * 100;
        return `${v} ${plural(v, ['метр', 'метра', 'метров'])}`;
    }
    const km = Math.round(m / 1000);
    return km === 1 ? 'километр' : `${km} ${plural(km, ['километр', 'километра', 'километров'])}`;
}

export function forTime(s) {
    const min = Math.max(1, Math.round(s / 60));
    const h = Math.floor(min / 60);
    const m = min % 60;
    const hours = h ? (h === 1 ? 'час' : `${h} ${plural(h, ['час', 'часа', 'часов'])}`) : '';
    const minutes = m ? `${m} ${plural(m, ['минута', 'минуты', 'минут'])}` : '';
    return [hours, minutes].filter(Boolean).join(' ');
}

export function capital(text) {
    return text ? text[0].toUpperCase() + text.slice(1) : text;
}
