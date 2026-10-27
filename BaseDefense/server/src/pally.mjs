// Pally (pally.info, formerly PayPalych): bills to pay, their status, and the payment notice Pally
// posts back.
//
// Everything that depends on Pally's API is here, so it can be checked against Pally's own
// documentation in one place (paypalych.com/en/reference/api): the address and fields of a bill,
// the statuses, and how the postback is signed (MD5 of "OutSum:InvId:API token", upper case).

import { createHash, timingSafeEqual } from 'node:crypto';

export const DEFAULT_API = 'https://pal24.pro/api/v1';

const PAID = new Set(['SUCCESS', 'OVERPAID']);
const FAILED = new Set(['FAIL']);

export class PallyError extends Error {
    constructor(message, status = 0) {
        super(message);
        this.status = status;
    }
}

export function makePally({ token, shopId, api = DEFAULT_API, fetch = globalThis.fetch, timeout = 15000, log = console }) {
    // Pally takes success_url and fail_url only on the domain of the shop's site in its cabinet. Once
    // it refuses them, bills go without: the player then comes back to the game by hand, and the game
    // asks about the order when it is in front again.
    let returnUrls = true;

    async function call(path, { method = 'GET', form, needSuccess = true } = {}) {
        const ctrl = new AbortController();
        const timer = setTimeout(() => ctrl.abort(), timeout);
        try {
            const res = await fetch(`${api}${path}`, {
                method,
                headers: {
                    authorization: `Bearer ${token}`,
                    accept: 'application/json',
                    ...(form ? { 'content-type': 'application/x-www-form-urlencoded' } : {}),
                },
                body: form ? new URLSearchParams(form).toString() : undefined,
                signal: ctrl.signal,
            });
            const text = await res.text();
            let json = null;
            try {
                json = JSON.parse(text);
            } catch (e) {
                json = null;
            }
            const ok = json && (!needSuccess || json.success === true || json.success === 'true');
            if (!res.ok || !ok) throw new PallyError(`Pally ${path}: ${res.status} ${text.slice(0, 300)}`, res.status);
            return json;
        } finally {
            clearTimeout(timer);
        }
    }

    return {
        /** A bill for [order]; resolves to {billId, url} — the page the player pays on. */
        async createBill({ orderId, rubles, description, successUrl, failUrl }) {
            const form = {
                amount: rubles.toFixed(2),
                order_id: orderId,
                description,
                name: description,
                type: 'normal',
                shop_id: shopId,
                currency_in: 'RUB',
                custom: orderId,
            };
            let json;
            if (returnUrls) {
                try {
                    json = await call('/bill/create', { method: 'POST', form: { ...form, success_url: successUrl, fail_url: failUrl } });
                } catch (e) {
                    // 401 and 403 are the token or the account, not the addresses.
                    if (!(e instanceof PallyError) || e.status < 400 || e.status === 401 || e.status === 403) throw e;
                    json = await call('/bill/create', { method: 'POST', form });
                    returnUrls = false;
                    log.warn(`Pally refused success_url/fail_url (${e.message}); bills go without them from now on`);
                }
            } else {
                json = await call('/bill/create', { method: 'POST', form });
            }
            const url = json.link_page_url || json.link_url;
            if (!url) throw new PallyError('Pally gave no payment link');
            return { billId: String(json.bill_id || json.id || ''), url };
        },

        /**
         * Whether Pally takes the token: asks about a bill that is not there. 'ok' (Pally answers
         * "no such bill"), 'bad-token' (401), 'banned' (403) or 'unreachable'.
         */
        async checkToken() {
            try {
                await call('/bill/status?id=basedefense-check', { needSuccess: false });
                return 'ok';
            } catch (e) {
                if (e.status === 401) return 'bad-token';
                if (e.status === 403) return 'banned';
                return e.status >= 400 && e.status < 500 ? 'ok' : 'unreachable';
            }
        },

        /** Whether a bill is paid: 'paid', 'failed' or 'pending'. Its "success" may be about the payment, so only "status" counts. */
        async billStatus(billId) {
            const json = await call(`/bill/status?id=${encodeURIComponent(billId)}`, { needSuccess: false });
            const status = String(json.status || '').toUpperCase();
            return PAID.has(status) ? 'paid' : FAILED.has(status) ? 'failed' : 'pending';
        },
    };
}

export function signature(outSum, invId, token) {
    return createHash('md5').update(`${outSum}:${invId}:${token}`).digest('hex').toUpperCase();
}

/**
 * Reads a postback from Pally: the order, the sum and whether it was paid; null when the signature
 * does not match the API token (anyone could post to the address).
 */
export function readPostback(fields, token) {
    const outSum = fields.OutSum;
    const invId = fields.InvId;
    const sign = String(fields.SignatureValue || '').toUpperCase();
    if (!outSum || !invId || !sign) return null;
    const want = Buffer.from(signature(outSum, invId, token));
    const got = Buffer.from(sign);
    if (want.length !== got.length || !timingSafeEqual(want, got)) return null;
    const status = String(fields.Status || 'SUCCESS').toUpperCase();
    return { orderId: String(invId), sum: Number(outSum), paid: PAID.has(status), failed: FAILED.has(status) };
}
