// Google's encoded polyline format, as Valhalla and OSRM send route shapes (6 decimal places).

export function decode(text, precision = 6) {
    const factor = 10 ** precision;
    const out = [];
    let lat = 0, lon = 0, i = 0;
    while (i < text.length) {
        for (const which of [0, 1]) {
            let shift = 0, value = 0, byte;
            do {
                byte = text.charCodeAt(i++) - 63;
                value |= (byte & 0x1f) << shift;
                shift += 5;
            } while (byte >= 0x20 && i < text.length);
            const delta = value & 1 ? ~(value >> 1) : value >> 1;
            if (which === 0) lat += delta; else lon += delta;
        }
        out.push([lon / factor, lat / factor]);
    }
    return out;
}

export function encode(points, precision = 6) {
    const factor = 10 ** precision;
    let out = '';
    let lastLat = 0, lastLon = 0;
    const put = (v) => {
        let n = v < 0 ? ~(v << 1) : v << 1;
        while (n >= 0x20) {
            out += String.fromCharCode((0x20 | (n & 0x1f)) + 63);
            n >>>= 5;
        }
        out += String.fromCharCode(n + 63);
    };
    for (const [lon, lat] of points) {
        const y = Math.round(lat * factor);
        const x = Math.round(lon * factor);
        put(y - lastLat);
        put(x - lastLon);
        lastLat = y;
        lastLon = x;
    }
    return out;
}
