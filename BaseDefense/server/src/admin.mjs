// Support for the shop on the server (as root, through the basedefense-shop command install.sh puts
// in /usr/local/bin):
//
//   basedefense-shop orders [CODE]          the last orders, or a player's
//   basedefense-shop player CODE            what a player has
//   basedefense-shop grant CODE PRODUCT     hands a purchase over by hand (the command stops the
//                                           service for it: the service keeps the records in memory)
//
// Answers in Russian, for whoever runs the shop.

import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

import { CATALOG } from './catalog.mjs';
import { readEnv } from './check.mjs';
import { Db } from './db.mjs';
import { normalCode, Shop, today } from './shop.mjs';

const STATUS = { pending: 'ждёт оплаты', paid: 'оплачен', failed: 'не прошёл' };

function day(n) {
    return n == null ? '—' : new Date(n * 86400000).toISOString().slice(0, 10);
}

function time(ms) {
    return new Date(ms).toISOString().slice(0, 16).replace('T', ' ');
}

/** Runs one command on [db]; returns the lines to print. */
export function admin(db, args, now = Date.now()) {
    const [command, codeArg, product] = args;
    const code = codeArg && normalCode(codeArg);
    if (codeArg && !code) return ['Код покупок — 20 букв и цифр, например ABCD-EFGH-IJKL-MNOP-QRST.'];
    switch (command) {
        case 'orders': {
            const all = code ? db.ordersOf(code) : Object.values(db.data.orders);
            const last = all.sort((a, b) => b.created - a.created).slice(0, 30);
            if (!last.length) return ['Заказов нет.'];
            return last.map((o) => `${time(o.created)}  ${o.id}  ${o.player}  ${o.product}  ${o.rubles} ₽  ${STATUS[o.status] || o.status}`);
        }
        case 'player': {
            if (!code) return ['Укажите код покупок.'];
            const p = db.player(code);
            const lines = [`Код ${code}${db.resolve(code) !== code ? ` (перенесён на ${db.resolve(code)})` : ''}`];
            lines.push(`  VIP до: ${day(p.vipUntil)}${p.vipUntil != null && p.vipUntil < today(now) ? ' (закончился)' : ''}`);
            lines.push(`  куплено: ${p.owned.length ? p.owned.map((id) => CATALOG[id]?.title || id).join(', ') : 'ничего'}`);
            lines.push(`  пропуски сезона: ${p.passes.length ? p.passes.map(day).join(', ') : 'нет'}`);
            return lines;
        }
        case 'grant': {
            if (!code || !CATALOG[product]) return [`Пример: grant ABCD-EFGH-IJKL-MNOP-QRST commander_grom. Товары: ${Object.keys(CATALOG).join(', ')}`];
            const shop = new Shop({ db, pally: null, token: '', publicUrl: '', now: () => now });
            const order = { id: `manual${now.toString(36)}`, player: code, product, rubles: 0, status: 'pending', created: now, checked: 0 };
            db.putOrder(order);
            shop.paid(order);
            return [`Выдано: ${CATALOG[product].title}. Игроку достаточно открыть экран VIP или перезапустить игру.`];
        }
        default:
            return ['Команды: orders [КОД], player КОД, grant КОД ТОВАР'];
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    let env = {};
    try {
        env = readEnv(readFileSync(process.env.SHOP_ENV || '/etc/basedefense-shop/shop.env', 'utf8'));
    } catch (e) {
        // No settings file: the default place for the records.
    }
    const db = new Db(env.DATA_FILE || '/var/lib/basedefense-shop/db.json');
    for (const line of admin(db, process.argv.slice(2))) console.log(line);
}
