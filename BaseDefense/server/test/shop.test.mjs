import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

import { CATALOG } from '../src/catalog.mjs';
import { admin } from '../src/admin.mjs';
import { readEnv } from '../src/check.mjs';
import { Db } from '../src/db.mjs';
import { makePally, readPostback, signature } from '../src/pally.mjs';
import { makeServer } from '../src/server.mjs';
import { normalCode, Shop, today } from '../src/shop.mjs';

const TOKEN = 'test-token';
const CODE = 'ABCDEFGHIJKLMNOPQRST';

/** Pally as the tests want it: bills remembered, statuses set by hand. */
function fakePally() {
    const bills = [];
    const states = new Map();
    return {
        bills,
        states,
        async createBill(b) {
            bills.push(b);
            return { billId: `bill${bills.length}`, url: `https://pay.example/${b.orderId}` };
        },
        async billStatus(id) {
            return states.get(id) || 'pending';
        },
    };
}

function shopAt(time = { now: Date.UTC(2026, 9, 26, 12) }) {
    const pally = fakePally();
    const shop = new Shop({ db: new Db(null), pally, token: TOKEN, publicUrl: 'https://shop.example/', now: () => time.now });
    return { shop, pally, time };
}

function notice(orderId, sum, status = 'SUCCESS') {
    const OutSum = sum.toFixed(2);
    return { InvId: orderId, OutSum, Status: status, CurrencyIn: 'RUB', SignatureValue: signature(OutSum, orderId, TOKEN) };
}

test('the server sells what the game sells, at the same prices', () => {
    const kotlin = readFileSync(new URL('../../engine/src/main/kotlin/com/makc/basedefense/engine/Shop.kt', import.meta.url), 'utf8');
    const products = [...kotlin.matchAll(/^\s+\w+\("(\w+)", (\d+)(.*)\),$/gm)].map(([, id, rubles, rest]) => ({
        id, rubles: Number(rubles), subscription: rest.includes('subscription = true'), consumable: rest.includes('consumable = true'),
    }));
    assert.equal(products.length, Object.keys(CATALOG).length);
    for (const p of products) {
        const item = CATALOG[p.id];
        assert.ok(item, `${p.id} is not on sale here`);
        assert.equal(item.rubles, p.rubles, p.id);
        assert.equal(item.kind, p.subscription ? 'vip' : p.consumable ? 'pass' : 'once', p.id);
    }
});

test('purchase codes are read as typed', () => {
    assert.equal(normalCode('abcd-efgh-ijkl-mnop-qrst'), CODE);
    assert.equal(normalCode(' ABCD EFGH IJKL MNOP QRST '), CODE);
    assert.equal(normalCode('ABCD-EFGH'), null);
    assert.equal(normalCode('ABCDEFGHIJKLMNOPQRS1'), null);
});

test('an order makes a bill, and a signed notice from Pally hands over what was bought', async () => {
    const { shop, pally } = shopAt();
    const { order, url } = await shop.order(CODE, 'commander_grom');
    assert.equal(url, `https://pay.example/${order}`);
    assert.equal(pally.bills[0].rubles, 149);
    assert.equal(pally.bills[0].successUrl, `https://shop.example/return?order=${order}&result=success`);
    assert.deepEqual((await shop.status(CODE, order)).status, 'pending');
    assert.equal(shop.postback(notice(order, 149)), true);
    assert.deepEqual(await shop.status(CODE, order), { status: 'paid', vipUntil: null });
    assert.deepEqual((await shop.purchases(CODE)).owned, ['commander_grom']);
    // The same notice twice changes nothing.
    shop.postback(notice(order, 149));
    assert.deepEqual((await shop.purchases(CODE)).owned, ['commander_grom']);
});

test('notices not signed with the API token, or paying too little, are refused', async () => {
    const { shop } = shopAt();
    const { order } = await shop.order(CODE, 'vip_forever');
    assert.equal(shop.postback({ ...notice(order, 399), SignatureValue: 'F'.repeat(32) }), false);
    assert.equal(shop.postback({ InvId: order, OutSum: '399.00' }), false);
    assert.equal(shop.postback(notice(order, 100)), true);
    assert.equal((await shop.status(CODE, order)).status, 'pending');
    assert.deepEqual((await shop.purchases(CODE)).owned, []);
    assert.equal(readPostback(notice(order, 399), 'another-token'), null);
});

test('VIP adds 30 days, from today or from when it ends, and passes count by day', async () => {
    const { shop } = shopAt();
    const day = today(Date.UTC(2026, 9, 26, 12));
    const a = await shop.order(CODE, 'vip_month');
    shop.postback(notice(a.order, 50));
    assert.equal((await shop.purchases(CODE)).vipUntil, day + 30);
    const b = await shop.order(CODE, 'vip_month');
    shop.postback(notice(b.order, 50));
    assert.equal((await shop.purchases(CODE)).vipUntil, day + 60);
    assert.equal((await shop.status(CODE, b.order)).vipUntil, day + 60);
    const c = await shop.order(CODE, 'season_pass');
    shop.postback(notice(c.order, 149));
    assert.deepEqual((await shop.purchases(CODE)).passes, [day]);
});

test('with no notice from Pally, the order is checked with Pally after a while', async () => {
    const { shop, pally, time } = shopAt();
    const { order } = await shop.order(CODE, 'skin_night');
    pally.states.set('bill1', 'paid');
    assert.equal((await shop.status(CODE, order)).status, 'pending', 'not asked at once');
    time.now += 20000;
    assert.equal((await shop.status(CODE, order)).status, 'paid');
    assert.deepEqual((await shop.purchases(CODE)).owned, ['skin_night']);
});

test("another player's code sees no order, and unknown products are refused", async () => {
    const { shop } = shopAt();
    const { order } = await shop.order(CODE, 'skin_night');
    await assert.rejects(shop.status('ZZZZZZZZZZZZZZZZZZZZ', order), /no such order/);
    await assert.rejects(shop.order(CODE, 'free_gold'), /no such product/);
    await assert.rejects(shop.order('bad', 'skin_night'), /bad code/);
});

test('a code typed on another phone takes over the purchases, and the old code follows it', async () => {
    const { shop } = shopAt();
    const OLD = 'AAAAAAAAAAAAAAAAAAAA';
    const NEW = 'BBBBBBBBBBBBBBBBBBBB';
    const a = await shop.order(OLD, 'vip_month');
    shop.postback(notice(a.order, 50));
    const b = await shop.order(NEW, 'skin_night');
    shop.postback(notice(b.order, 99));
    const pending = await shop.order(OLD, 'commander_grom');
    const day = today(Date.UTC(2026, 9, 26, 12));
    assert.deepEqual(await shop.move(OLD, NEW), { owned: ['skin_night'], vipUntil: day + 30, passes: [] });
    // The old code, still on the old phone, sees the same, and its unpaid order counts when paid.
    shop.postback(notice(pending.order, 149));
    assert.deepEqual((await shop.purchases(OLD)).owned, ['skin_night', 'commander_grom']);
    assert.equal((await shop.status(NEW, pending.order)).status, 'paid');
    // Moving back and forth makes no loop.
    await shop.move(NEW, OLD);
    await shop.move(OLD, NEW);
    assert.deepEqual((await shop.purchases(NEW)).owned, ['skin_night', 'commander_grom']);
    await assert.rejects(shop.move(OLD, 'short'), /bad code/);
});

test('asking what a player has checks their recent unpaid orders with Pally', async () => {
    const { shop, pally, time } = shopAt();
    await shop.order(CODE, 'skin_parade');
    pally.states.set('bill1', 'paid');
    time.now += 20000;
    assert.deepEqual((await shop.purchases(CODE)).owned, ['skin_parade']);
});

test('support sees orders and players, and can hand a purchase over', async () => {
    const { shop } = shopAt();
    const now = Date.UTC(2026, 9, 26, 12);
    const { order } = await shop.order(CODE, 'skin_parade');
    assert.match(admin(shop.db, ['orders'], now)[0], new RegExp(`${order}.*skin_parade.*149 ₽.*ждёт оплаты`));
    assert.deepEqual(admin(shop.db, ['orders', 'ZZZZZZZZZZZZZZZZZZZZ'], now), ['Заказов нет.']);
    assert.match(admin(shop.db, ['grant', 'abcd-efgh-ijkl-mnop-qrst', 'commander_grom'], now)[0], /Выдано: Командир: капитан Гром/);
    assert.deepEqual((await shop.purchases(CODE)).owned, ['commander_grom']);
    const player = admin(shop.db, ['player', CODE], now).join('\n');
    assert.match(player, /куплено: Командир: капитан Гром/);
    assert.match(player, /VIP до: —/);
    assert.match(admin(shop.db, ['grant', CODE, 'free_gold'], now)[0], /Пример/);
    assert.match(admin(shop.db, ['player', 'short'], now)[0], /20 букв/);
});

test('the Pally client sends a bill and reads its link and status', async () => {
    const calls = [];
    const fetch = async (url, init) => {
        calls.push({ url, init });
        const body = url.includes('/bill/create')
            ? { success: 'true', bill_id: 'B42', link_url: 'https://pally/link', link_page_url: 'https://pally/page' }
            : { success: 'true', status: 'SUCCESS' };
        return { ok: true, status: 200, text: async () => JSON.stringify(body) };
    };
    const pally = makePally({ token: TOKEN, shopId: 'S1', fetch });
    const bill = await pally.createBill({ orderId: 'bd1', rubles: 50, description: 'VIP', successUrl: 's', failUrl: 'f' });
    assert.deepEqual(bill, { billId: 'B42', url: 'https://pally/page' });
    assert.equal(calls[0].init.headers.authorization, `Bearer ${TOKEN}`);
    const form = new URLSearchParams(calls[0].init.body);
    assert.equal(form.get('amount'), '50.00');
    assert.equal(form.get('shop_id'), 'S1');
    assert.equal(form.get('order_id'), 'bd1');
    assert.equal(form.get('success_url'), 's');
    assert.equal(form.has('payer_pays_commission'), false);
    assert.equal(await pally.billStatus('B42'), 'paid');
    const failing = makePally({ token: TOKEN, shopId: 'S1', fetch: async () => ({ ok: false, status: 401, text: async () => '{"success":"false"}' }) });
    await assert.rejects(failing.createBill({ orderId: 'x', rubles: 1, description: '', successUrl: '', failUrl: '' }), /401/);
});

test('when Pally refuses the return addresses, bills go without them from then on', async () => {
    const forms = [];
    const warnings = [];
    const fetch = async (url, init) => {
        const form = new URLSearchParams(init.body);
        forms.push(form);
        if (form.has('success_url')) return { ok: false, status: 422, text: async () => '{"success":false,"message":"success_url domain"}' };
        return { ok: true, status: 200, text: async () => '{"success":true,"bill_id":"B1","link_page_url":"https://pally/page"}' };
    };
    const pally = makePally({ token: TOKEN, shopId: 'S1', fetch, log: { warn: (m) => warnings.push(m) } });
    const bill = { orderId: 'bd1', rubles: 99, description: 'Ночь', successUrl: 'https://shop/s', failUrl: 'https://shop/f' };
    assert.equal((await pally.createBill(bill)).url, 'https://pally/page');
    await pally.createBill(bill);
    assert.deepEqual(forms.map((f) => f.has('success_url')), [true, false, false], 'tried once with them, then never again');
    assert.equal(warnings.length, 1);
    // A wrong token is not about the addresses: no second try.
    const calls = [];
    const denied = makePally({ token: 'x', shopId: 'S1', fetch: async (u, i) => (calls.push(i), { ok: false, status: 401, text: async () => '{}' }) });
    await assert.rejects(denied.createBill(bill), /401/);
    assert.equal(calls.length, 1);
});

test("a bill's status counts even where Pally's \"success\" is false for an unpaid bill", async () => {
    let answer;
    const pally = makePally({ token: TOKEN, shopId: 'S1', fetch: async () => ({ ok: true, status: 200, text: async () => JSON.stringify(answer) }) });
    answer = { success: false, status: 'NEW' };
    assert.equal(await pally.billStatus('B1'), 'pending');
    answer = { success: false, status: 'FAIL' };
    assert.equal(await pally.billStatus('B1'), 'failed');
    answer = { success: 'true', status: 'OVERPAID' };
    assert.equal(await pally.billStatus('B1'), 'paid');
    answer = { success: false, status: 'UNDERPAID' };
    assert.equal(await pally.billStatus('B1'), 'pending');
});

test('the token check tells a wrong token from a right one', async () => {
    const answering = (status) => makePally({ token: TOKEN, shopId: 'S1', fetch: async () => ({ ok: status < 300, status, text: async () => '{"success":false}' }) });
    assert.equal((await answering(422).checkToken()).state, 'ok', 'no such bill: the token is fine');
    assert.equal((await answering(401).checkToken()).state, 'bad-token');
    assert.equal((await answering(403).checkToken()).state, 'banned');
    const down = await answering(502).checkToken();
    assert.equal(down.state, 'unreachable');
    assert.match(down.detail, /502/, 'what Pally said is shown');
    const offline = makePally({ token: TOKEN, shopId: 'S1', fetch: async () => { throw new TypeError('fetch failed'); } });
    assert.deepEqual(await offline.checkToken(), { state: 'unreachable', detail: 'fetch failed' });
    const echoing = makePally({ token: 'secret-token', shopId: 'S1', fetch: async (url, init) => ({ ok: false, status: 401, text: async () => init.headers.authorization }) });
    assert.doesNotMatch((await echoing.checkToken()).detail, /secret-token/, 'the token never shows');
    assert.deepEqual(readEnv('# note\nPALLY_TOKEN="a=b"\n\nPALLY_SHOP_ID=XY\n# PORT=1\n'), { PALLY_TOKEN: 'a=b', PALLY_SHOP_ID: 'XY' });
});

test('the HTTP side: orders, status, purchases, notices and the page back to the game', async () => {
    const { shop } = shopAt({ now: Date.now() });
    const server = makeServer(shop, { log: { warn() {}, error() {} } });
    await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
    const base = `http://127.0.0.1:${server.address().port}`;
    try {
        let r = await fetch(`${base}/api/order`, { method: 'POST', body: JSON.stringify({ code: CODE, product: 'commander_grom' }) });
        assert.equal(r.status, 200);
        const { order } = await r.json();
        const n = notice(order, 149);
        r = await fetch(`${base}/pally/result`, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(n).toString() });
        assert.equal(await r.text(), 'OK');
        r = await fetch(`${base}/api/order/${order}?code=${CODE}`);
        assert.deepEqual(await r.json(), { status: 'paid', vipUntil: null });
        r = await fetch(`${base}/api/purchases?code=${CODE}`);
        assert.deepEqual((await r.json()).owned, ['commander_grom']);
        r = await fetch(`${base}/api/move`, { method: 'POST', body: JSON.stringify({ from: CODE, to: 'CCCCCCCCCCCCCCCCCCCC' }) });
        assert.deepEqual((await r.json()).owned, ['commander_grom']);
        r = await fetch(`${base}/pally/result`, { method: 'POST', body: 'InvId=1&OutSum=1&SignatureValue=00' });
        assert.equal(r.status, 400);
        r = await fetch(`${base}/api/purchases?code=nope`);
        assert.equal(r.status, 400);
        r = await fetch(`${base}/api/order`, { method: 'POST', body: '{not json' });
        assert.equal(r.status, 400);
        r = await fetch(`${base}/return?order=${order}&result=success`);
        assert.match(await r.text(), /com\.makc\.basedefense:\/\/paid/);
        // Pally itself comes back with a POST, its payment fields in the body.
        r = await fetch(`${base}/return?result=fail`, { method: 'POST', body: new URLSearchParams(n).toString(), headers: { 'content-type': 'application/x-www-form-urlencoded' } });
        assert.equal(r.status, 200);
        assert.match(await r.text(), /Оплата не прошла/);
        // Too many new orders from one address are turned away.
        let last = 200;
        for (let i = 0; i < 25 && last === 200; i++) {
            last = (await fetch(`${base}/api/order`, { method: 'POST', body: JSON.stringify({ code: CODE, product: 'skin_night' }) })).status;
        }
        assert.equal(last, 429);
    } finally {
        server.close();
    }
});
