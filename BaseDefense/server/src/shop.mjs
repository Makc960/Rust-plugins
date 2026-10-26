// The shop itself, apart from HTTP: orders, payments and what each player has.
//
// A player is known only by their purchase code: a random code the game makes on first launch and
// shows in the shop, so the purchases can be moved to a new phone by typing it in.

import { randomBytes } from 'node:crypto';

import { CATALOG } from './catalog.mjs';
import { readPostback } from './pally.mjs';

const CODE = /^[A-Z2-7]{20}$/;
// How long after an order the server asks Pally itself, in case the postback is late or lost.
const CHECK_AFTER = 15000;
const CHECK_EVERY = 10000;

export class ShopError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

/** A purchase code as typed: letters and digits of base 32, dashes and case ignored. */
export function normalCode(code) {
    const c = String(code || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
    return CODE.test(c) ? c : null;
}

export function today(now = Date.now()) {
    return Math.floor(now / 86400000);
}

export class Shop {
    constructor({ db, pally, token, publicUrl, now = () => Date.now() }) {
        this.db = db;
        this.pally = pally;
        this.token = token;
        this.publicUrl = publicUrl.replace(/\/+$/, '');
        this.now = now;
    }

    /** A new order for [product] by [code]: a bill in Pally and the page to pay on. */
    async order(code, productId) {
        const player = normalCode(code);
        if (!player) throw new ShopError(400, 'bad code');
        const item = CATALOG[productId];
        if (!item) throw new ShopError(400, 'no such product');
        const id = `bd${this.now().toString(36)}${randomBytes(4).toString('hex')}`;
        const order = { id, player, product: productId, rubles: item.rubles, status: 'pending', created: this.now(), checked: 0 };
        let bill;
        try {
            bill = await this.pally.createBill({
                orderId: id,
                rubles: item.rubles,
                description: `Оборона базы: ${item.title}`,
                successUrl: `${this.publicUrl}/return?order=${id}&result=success`,
                failUrl: `${this.publicUrl}/return?order=${id}&result=fail`,
            });
        } catch (e) {
            throw new ShopError(502, `payments unavailable: ${e.message}`);
        }
        order.bill = bill.billId;
        this.db.putOrder(order);
        return { order: id, url: bill.url };
    }

    /** Pally's notice of a payment. Answers whether it was taken (false: not signed by Pally). */
    postback(fields) {
        const notice = readPostback(fields, this.token);
        if (!notice) return false;
        const order = this.db.order(notice.orderId);
        if (!order) return true;
        if (notice.paid && notice.sum + 0.001 >= order.rubles) this.paid(order);
        else if (notice.failed && order.status === 'pending') this.db.putOrder({ ...order, status: 'failed' });
        return true;
    }

    /** How [orderId] stands for [code]: pending, paid (with VIP's last day) or failed. */
    async status(code, orderId) {
        const player = normalCode(code);
        let order = this.db.order(orderId);
        if (!player || !order || order.player !== player) throw new ShopError(404, 'no such order');
        const now = this.now();
        if (order.status === 'pending' && order.bill && now - order.created > CHECK_AFTER && now - order.checked > CHECK_EVERY) {
            order = { ...order, checked: now };
            this.db.putOrder(order);
            try {
                const state = await this.pally.billStatus(order.bill);
                if (state === 'paid') order = this.paid(order);
                else if (state === 'failed') this.db.putOrder((order = { ...order, status: 'failed' }));
            } catch (e) {
                // Pally did not answer: the postback may still come.
            }
        }
        const answer = { status: order.status };
        if (order.status === 'paid') answer.vipUntil = this.db.player(player).vipUntil;
        return answer;
    }

    /** Everything [code] has paid for. */
    purchases(code) {
        const player = normalCode(code);
        if (!player) throw new ShopError(400, 'bad code');
        return this.db.player(player);
    }

    /** Marks [order] paid, once, and gives the player what it bought. */
    paid(order) {
        if (order.status === 'paid') return order;
        const done = { ...order, status: 'paid', paidAt: this.now() };
        const p = this.db.player(order.player);
        const item = CATALOG[order.product];
        const day = today(this.now());
        const next = { owned: [...p.owned], vipUntil: p.vipUntil, passes: [...p.passes] };
        if (item.kind === 'vip') next.vipUntil = Math.max(day, p.vipUntil || 0) + item.days;
        else if (item.kind === 'pass') next.passes.push(day);
        else if (!next.owned.includes(order.product)) next.owned.push(order.product);
        this.db.putPlayer(order.player, next);
        this.db.putOrder(done);
        return done;
    }
}
