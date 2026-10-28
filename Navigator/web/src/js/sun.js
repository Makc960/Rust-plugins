// The sun's height over a place, for switching the map to night colours after dusk.

const RAD = Math.PI / 180;

/** The sun's elevation in degrees at [date] over [lat], [lon] (NOAA's approximation, to a few tenths of a degree). */
export function sunElevation(date, lat, lon) {
    const jd = date.getTime() / 86400000 + 2440587.5;
    const t = (jd - 2451545) / 36525;
    const l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360;
    const m = 357.52911 + t * (35999.05029 - 0.0001537 * t);
    const e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
    const c = Math.sin(m * RAD) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
        Math.sin(2 * m * RAD) * (0.019993 - 0.000101 * t) + Math.sin(3 * m * RAD) * 0.000289;
    const trueLong = l0 + c;
    const omega = 125.04 - 1934.136 * t;
    const lambda = trueLong - 0.00569 - 0.00478 * Math.sin(omega * RAD);
    const eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60;
    const eps = eps0 + 0.00256 * Math.cos(omega * RAD);
    const decl = Math.asin(Math.sin(eps * RAD) * Math.sin(lambda * RAD));
    const y = Math.tan((eps / 2) * RAD) ** 2;
    const eqTime = 4 / RAD * (y * Math.sin(2 * l0 * RAD) - 2 * e * Math.sin(m * RAD) +
        4 * e * y * Math.sin(m * RAD) * Math.cos(2 * l0 * RAD) - 0.5 * y * y * Math.sin(4 * l0 * RAD) -
        1.25 * e * e * Math.sin(2 * m * RAD));
    const minutes = date.getUTCHours() * 60 + date.getUTCMinutes() + date.getUTCSeconds() / 60;
    const solarTime = (minutes + eqTime + 4 * lon + 1440) % 1440;
    const hourAngle = (solarTime / 4 - 180) * RAD;
    const cosZenith = Math.sin(lat * RAD) * Math.sin(decl) + Math.cos(lat * RAD) * Math.cos(decl) * Math.cos(hourAngle);
    return 90 - Math.acos(Math.max(-1, Math.min(1, cosZenith))) / RAD;
}

/** Dark enough for night colours: the sun more than a little below the horizon. */
export function isNight(date, lat, lon) {
    return sunElevation(date, lat, lon) < -3;
}
