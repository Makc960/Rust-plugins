// For the author, on the server:
//   navigator-admin feedback [n]     the last «Не то нашлось» notes (30 unless told)
//   navigator-admin reports          road reports on the map now, by kind
//   navigator-admin trips            drives being shared now

import { Feedback } from './feedback.mjs';
import { KINDS, Reports } from './reports.mjs';
import { Trips } from './trips.mjs';

const dir = process.env.DATA_DIR || '/var/lib/navigator';
const [what = 'feedback', arg] = process.argv.slice(2);

if (what === 'feedback') {
    const notes = new Feedback({ file: `${dir}/feedback.jsonl` }).recent(Number(arg) || 30);
    if (!notes.length) console.log('Отзывов о поиске пока нет.');
    for (const n of notes) {
        console.log(`\n${n.at}  «${n.query}»${n.town ? `  (${n.town})` : ''}${n.point ? `  ${n.point.lat},${n.point.lon}` : ''}`);
        if (n.wanted) console.log(`  хотели: ${n.wanted}`);
        if (n.results?.length) console.log(`  нашлось: ${n.results.slice(0, 5).join(' | ')}`);
    }
} else if (what === 'reports') {
    const reports = new Reports({ file: `${dir}/reports.json` });
    reports.prune();
    const all = Object.values(reports.all);
    console.log(`Отметок на карте: ${all.length}`);
    for (const [kind, { label }] of Object.entries(KINDS)) {
        const n = all.filter((r) => r.kind === kind).length;
        if (n) console.log(`  ${label}: ${n}`);
    }
} else if (what === 'trips') {
    const trips = new Trips({ file: `${dir}/trips.json` });
    trips.prune();
    const all = Object.values(trips.all);
    console.log(`Поездок, которыми делятся: ${all.filter((t) => !t.ended).length} (всего ссылок: ${all.length})`);
} else {
    console.log('navigator-admin feedback [n] | reports | trips');
    process.exitCode = 1;
}
