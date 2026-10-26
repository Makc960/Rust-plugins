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
// Unpaid orders this young are checked again when the player asks what they have.
const RECHECK_FOR = 60 * 60 * 1000;

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
        if (!player || !order || this.db.resolve(order.player) !== this.db.resolve(player)) throw new ShopError(404, 'no such order');
        order = await this.refresh(order);
        const answer = { status: order.status };
        if (order.status === 'paid') answer.vipUntil = this.db.player(player).vipUntil;
        return answer;
    }

    /** Everything [code] has paid for, with its recent unpaid orders checked with Pally first. */
    async purchases(code) {
        const player = normalCode(code);
        if (!player) throw new ShopError(400, 'bad code');
        const now = this.now();
        const recent = this.db.ordersOf(player).filter((o) => o.status === 'pending' && now - o.created < RECHECK_FOR);
        for (const order of recent.slice(-3)) await this.refresh(order);
        return this.db.player(player);
    }

    /**
     * Moves [from]'s purchases into [to], the code typed on a new phone (or the code of the old one
     * typed on this phone), and has [from] mean [to] from now on. Answers what [to] has then.
     */
    async move(from, to) {
        const a = normalCode(from);
        const b = normalCode(to);
        if (!a || !b) throw new ShopError(400, 'bad code');
        const source = this.db.resolve(a);
        const target = this.db.resolve(b);
        if (source !== target) {
            const p = this.db.player(source);
            const q = this.db.player(target);
            const until = [p.vipUntil, q.vipUntil].filter((day) => day != null);
            this.db.putPlayer(target, {
                owned: [...new Set([...q.owned, ...p.owned])],
                vipUntil: until.length ? Math.max(...until) : null,
                passes: [...q.passes, ...p.passes],
            });
            this.db.alias(source, target);
        }
        return this.purchases(target);
    }

    /** Asks Pally about a pending [order] when its notice is late; answers the order as it is then. */
    async refresh(order) {
        const now = this.now();
        if (order.status !== 'pending' || !order.bill || now - order.created <= CHECK_AFTER || now - order.checked <= CHECK_EVERY) return order;
        let current = { ...order, checked: now };
        this.db.putOrder(current);
        try {
            const state = await this.pally.billStatus(order.bill);
            if (state === 'paid') current = this.paid(current);
            else if (state === 'failed') this.db.putOrder((current = { ...current, status: 'failed' }));
        } catch (e) {
            // Pally did not answer: the postback may still come.
        }
        return current;
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
