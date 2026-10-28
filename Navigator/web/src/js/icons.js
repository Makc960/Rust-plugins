// Icons as inline SVG: the interface's own, and the manoeuvre arrows drawn from a step.

const svg = (body, view = 24) => `<svg viewBox="0 0 ${view} ${view}" aria-hidden="true">${body}</svg>`;
const stroke = (d) => `<path d="${d}" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>`;
const fill = (d) => `<path d="${d}" fill="currentColor"/>`;

export const ICONS = {
    search: svg(stroke('M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13zM15.5 15.5 20 20')),
    back: svg(stroke('M15 5l-7 7 7 7')),
    close: svg(stroke('M6 6l12 12M18 6 6 18')),
    menu: svg(stroke('M4 7h16M4 12h16M4 17h16')),
    star: svg(stroke('M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z')),
    starFilled: svg(fill('M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z')),
    share: svg(stroke('M12 15V4M8 8l4-4 4 4M5 12v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6')),
    more: svg(fill('M12 5.5a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2zm0 5a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2zm0 5a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2z')),
    home: svg(stroke('M4 11 12 4l8 7M6 9.5V20h4v-5h4v5h4V9.5')),
    work: svg(stroke('M4 8h16v11H4zM9 8V5.5h6V8M4 13h16')),
    clock: svg(stroke('M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16zM12 8v4.5l3 2')),
    pin: svg(fill('M12 2.5a6.5 6.5 0 0 0-6.5 6.5c0 4.6 6.5 12.5 6.5 12.5s6.5-7.9 6.5-12.5A6.5 6.5 0 0 0 12 2.5zm0 9a2.5 2.5 0 1 1 0-5 2.5 2.5 0 0 1 0 5z')),
    place: svg(stroke('M12 21s-6.5-7.4-6.5-12a6.5 6.5 0 1 1 13 0c0 4.6-6.5 12-6.5 12zM12 6.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z')),
    car: svg(stroke('M5 16v3M19 16v3M4 16h16v-4l-2-5H6l-2 5zM4 12h16M7.5 14h.01M16.5 14h.01')),
    bike: svg(stroke('M6 17.5a3.5 3.5 0 1 0 0-.01zM18 17.5a3.5 3.5 0 1 0 0-.01zM6 17.5 9.5 10H15l3 7.5M9.5 10 8 6.5H6M12 17.5 9.5 10M14 6.5h2l-1 3.5')),
    foot: svg(stroke('M13 4.5a1.5 1.5 0 1 0 0 .01zM10 21l2-6 3 3v3M8 12l2-4 3.5 1.5L15 13l2.5 1M12 15l-1.5-4.5')),
    layers: svg(stroke('M12 4 3.5 8.5 12 13l8.5-4.5zM3.5 12.5 12 17l8.5-4.5M3.5 16.5 12 21l8.5-4.5')),
    locate: svg(stroke('M12 3v3M12 18v3M3 12h3M18 12h3M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10z') + fill('M12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4z')),
    compass: svg(fill('M12 3l3.5 9h-7z') + `<path d="M12 21l-3.5-9h7z" fill="currentColor" opacity=".35"/>`),
    soundOn: svg(stroke('M4 9.5h3.5L12 6v12l-4.5-3.5H4zM15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11')),
    soundOff: svg(stroke('M4 9.5h3.5L12 6v12l-4.5-3.5H4zM16 9.5l5 5M21 9.5l-5 5')),
    overview: svg(stroke('M4 6.5 9 4.5l6 2 5-2v13l-5 2-6-2-5 2zM9 4.5v13M15 6.5v13')),
    settings: svg(stroke('M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM19.4 13.5l1.6 1.2-2 3.4-1.9-.7a7 7 0 0 1-1.7 1L15 20.5h-4l-.4-2.1a7 7 0 0 1-1.7-1l-1.9.7-2-3.4 1.6-1.2a7 7 0 0 1 0-2l-1.6-1.2 2-3.4 1.9.7a7 7 0 0 1 1.7-1L11 3.5h4l.4 2.1a7 7 0 0 1 1.7 1l1.9-.7 2 3.4-1.6 1.2a7 7 0 0 1 0 2z')),
    route: svg(stroke('M6 19a2 2 0 1 0 0-.01zM18 5a2 2 0 1 0 0-.01zM8 19h7a3.5 3.5 0 0 0 0-7H9a3.5 3.5 0 0 1 0-7h7')),
    play: svg(fill('M8 5.5v13l10.5-6.5z')),
    navigate: svg(fill('M12 2.5 19.5 20 12 16l-7.5 4z')),
    swap: svg(stroke('M7 4v16M7 20l-3-3M7 20l3-3M17 20V4M17 4l-3 3M17 4l3 3')),
    copy: svg(stroke('M9 9h10v11H9zM5 15V4h10')),
    trash: svg(stroke('M5 7h14M9 7V4.5h6V7M7 7l1 13h8l1-13')),
    warning: svg(stroke('M12 4 2.5 20h19zM12 10v4.5M12 17.5h.01')),
    gps: svg(stroke('M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2v3M12 19v3M2 12h3M19 12h3')),
    check: svg(stroke('M5 12.5 10 17l9-10')),
    info: svg(stroke('M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17zM12 11v5.5M12 7.5h.01')),
    heart: svg(stroke('M12 19.5s-7.5-4.5-7.5-10A4 4 0 0 1 12 7a4 4 0 0 1 7.5 2.5c0 5.5-7.5 10-7.5 10z')),
    flag: svg(stroke('M6 21V4M6 4h11l-2.5 4L17 12H6')),
    list: svg(stroke('M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01')),
    camera: svg(stroke('M4 8.5h10.5V16H4zM14.5 10.5 20 8v9l-5.5-2.5M7 6V4.5')),
    stop: svg(stroke('M12 21s-6.5-7.4-6.5-12a6.5 6.5 0 1 1 13 0c0 4.6-6.5 12-6.5 12z') + fill('M12 6.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z')),
    plus: svg(stroke('M12 5v14M5 12h14')),
    // Kinds of places, for searching nearby and along the route.
    fuel: svg(stroke('M5 20V5.5A1.5 1.5 0 0 1 6.5 4h6A1.5 1.5 0 0 1 14 5.5V20M3.5 20h12M5 10h9M14 9l3 2.5V17a1.5 1.5 0 0 0 3 0v-7l-2.5-2.5')),
    cafe: svg(stroke('M5 9h11v5a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5zM16 10.5h1.5a2.5 2.5 0 0 1 0 5H16M8 3.5c0 1.5 1 1.5 1 3M11.5 3.5c0 1.5 1 1.5 1 3')),
    food: svg(stroke('M7 3v7M5 3v4.5a2 2 0 0 0 4 0V3M7 10v11M16.5 21V3c-2 1-3.5 3.5-3.5 7.5h3.5')),
    pharmacy: svg(stroke('M9.5 4h5v5.5H20v5h-5.5V20h-5v-5.5H4v-5h5.5z')),
    atm: svg(stroke('M3.5 6.5h17v11h-17zM3.5 10h17M7 14.5h4')),
    parking: svg(stroke('M5 3.5h14v17H5zM10 16.5v-9h3a2.5 2.5 0 0 1 0 5h-3')),
    shop: svg(stroke('M3.5 4.5h2l2.2 10h10.3l2-7H6.6M9.5 19.5h.01M17 19.5h.01')),
    charging: svg(stroke('M13 3 6 13.5h5L10 21l7-10.5h-5z')),
    repair: svg(stroke('M14.5 6a4 4 0 0 0 4.9 4.9L20 11l-9.5 9.5a2.1 2.1 0 0 1-3-3L17 8l.1-.6A4 4 0 0 0 14.5 6l2.5 2.5')),
    toilets: svg(stroke('M7.5 6.5a1.5 1.5 0 1 0 0-.01zM16.5 6.5a1.5 1.5 0 1 0 0-.01zM5.5 20v-6H4.5l1-5h4l1 5h-1v6M14.5 20v-5h-1.5l2-6h3l2 6H18.5v5M12 4v16')),
};

// ---- Manoeuvre arrows ----

const ANGLES = {
    straight: 0, 'slight-right': 45, right: 90, 'sharp-right': 135,
    'slight-left': -45, left: -90, 'sharp-left': -135, uturn: -180,
};

const RAD = Math.PI / 180;

function arrowHead(x, y, deg, size = 7) {
    const a = deg * RAD;
    const fx = Math.sin(a), fy = -Math.cos(a);
    const px = -fy, py = fx;
    const tip = [x + fx * size, y + fy * size];
    const l = [x + px * size, y + py * size];
    const r = [x - px * size, y - py * size];
    return `<path d="M${tip[0].toFixed(1)} ${tip[1].toFixed(1)}L${l[0].toFixed(1)} ${l[1].toFixed(1)}L${r[0].toFixed(1)} ${r[1].toFixed(1)}Z" fill="currentColor"/>`;
}

function branch(deg, { width = 5, opacity = 1, from = [24, 44], knee = 25, length = 14 } = {}) {
    const a = deg * RAD;
    const ex = from[0] + Math.sin(a) * length;
    const ey = knee - Math.cos(a) * length;
    const line = `<path d="M${from[0]} ${from[1]}V${knee}L${ex.toFixed(1)} ${ey.toFixed(1)}" fill="none" stroke="currentColor" stroke-width="${width}" stroke-linecap="round" stroke-linejoin="round" opacity="${opacity}"/>`;
    return { line, end: [ex, ey] };
}

function turnArrow(deg) {
    const { line, end } = branch(deg);
    return line + arrowHead(end[0], end[1], deg);
}

function uturn(side) {
    // In Russia a U-turn comes back on the left; the arrow bends that way unless told otherwise.
    const right = side === 'right';
    const x1 = right ? 16 : 32, x2 = right ? 32 : 16;
    const sweep = right ? 1 : 0;
    return `<path d="M${x1} 44V20A8 8 0 0 ${sweep} ${x2} 20V28" fill="none" stroke="currentColor" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>` +
        arrowHead(x2, 30, 180);
}

function roundabout(step) {
    const exitAngle = ANGLES[step.modifier] ?? 0;
    const cx = 24, cy = 21, r = 9;
    const a = exitAngle * RAD;
    const sx = cx + Math.sin(a) * r, sy = cy - Math.cos(a) * r;
    const ex = cx + Math.sin(a) * (r + 10), ey = cy - Math.cos(a) * (r + 10);
    const label = step.exit ? `<text x="${cx}" y="${cy + 4.5}" text-anchor="middle" font-size="13" font-weight="700" fill="currentColor">${step.exit}</text>` : '';
    return `<circle cx="${cx}" cy="${cy}" r="${r}" fill="none" stroke="currentColor" stroke-width="4" opacity=".55"/>` +
        `<path d="M24 44V${cy + r}" stroke="currentColor" stroke-width="5" stroke-linecap="round"/>` +
        `<path d="M${sx.toFixed(1)} ${sy.toFixed(1)}L${ex.toFixed(1)} ${ey.toFixed(1)}" stroke="currentColor" stroke-width="5" stroke-linecap="round"/>` +
        arrowHead(ex, ey, exitAngle, 6) + label;
}

/** The big arrow for a step, as an SVG string. */
export function maneuverIcon(step) {
    const m = step.modifier || 'straight';
    let body;
    switch (step.type) {
        case 'arrive':
            body = `<path d="M24 6a11 11 0 0 0-11 11c0 8 11 21 11 21s11-13 11-21A11 11 0 0 0 24 6zm0 15a4 4 0 1 1 0-8 4 4 0 0 1 0 8z" fill="currentColor"/>` +
                `<path d="M14 42h20" stroke="currentColor" stroke-width="4" stroke-linecap="round"/>`;
            break;
        case 'depart':
            body = turnArrow(0);
            break;
        case 'waypoint':
            body = `<path d="M24 8a9 9 0 0 0-9 9c0 6.5 9 17 9 17s9-10.5 9-17a9 9 0 0 0-9-9zm0 12.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z" fill="currentColor"/>` +
                `<path d="M16 41h16" stroke="currentColor" stroke-width="4" stroke-linecap="round" opacity=".55"/>`;
            break;
        case 'roundabout':
            body = roundabout(step);
            break;
        case 'fork':
        case 'ramp':
        case 'exit': {
            const deg = m === 'right' ? 35 : m === 'left' ? -35 : 0;
            const other = step.type === 'fork' ? (deg === 0 ? 35 : -deg) : 0;
            body = (deg !== other ? branch(other, { opacity: 0.35 }).line : '') + turnArrow(deg);
            break;
        }
        case 'merge': {
            const side = m === 'left' ? -1 : 1;
            body = `<path d="M${24 - side * 14} 44Q${24 - side * 14} 30 24 22" fill="none" stroke="currentColor" stroke-width="4" stroke-linecap="round" opacity=".45"/>` + turnArrow(0);
            break;
        }
        default:
            body = m === 'uturn' ? uturn(step.side) : turnArrow(ANGLES[m] ?? 0);
    }
    return svg(body, 48);
}

/** A small arrow for one lane: its turns, the ones that lead on brighter. */
export function laneIcon(lane, wanted) {
    const parts = lane.turns.map((t) => {
        const deg = t === 'uturn' ? -180 : ANGLES[t] ?? 0;
        const on = lane.use && (wanted.length === 0 || wanted.includes(t));
        if (t === 'uturn') return `<g opacity="${on ? 1 : 0.35}">${uturn('left')}</g>`;
        const { line, end } = branch(deg, { opacity: 1 });
        return `<g opacity="${on ? 1 : 0.35}">${line}${arrowHead(end[0], end[1], deg)}</g>`;
    });
    return svg(parts.join(''), 48);
}
