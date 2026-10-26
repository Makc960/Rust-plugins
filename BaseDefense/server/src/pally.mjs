// Pally (pally.info): bills to pay, their status, and the payment notice Pally posts back.
//
// Everything that depends on Pally's API is here, so it can be checked against Pally's own
// documentation in one place: the address and fields of a bill, the statuses, and how the
// postback is signed (MD5 of "OutSum:InvId:API token", upper case).

import { createHash, timingSafeEqual } from 'node:crypto';

export const DEFAULT_API = 'https://pal24.pro/api/v1';

const PAID = new Set(['SUCCESS', 'OVERPAID']);
const FAILED = new Set(['FAIL']);

export class PallyError extends Error {}

export function makePally({ token, shopId, api = DEFAULT_API, fetch = globalThis.fetch, timeout = 15000 }) {
    async function call(path, { method = 'GET', form } = {}) {
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
            const ok = json && (json.success === true || json.success === 'true');
            if (!res.ok || !ok) throw new PallyError(`Pally ${path}: ${res.status} ${text.slice(0, 300)}`);
            return json;
        } finally {
            clearTimeout(timer);
        }
    }

    return {
        /** A bill for [order]; resolves to {billId, url} — the page the player pays on. */
        async createBill({ orderId, rubles, description, successUrl, failUrl }) {
            const json = await call('/bill/create', {
                method: 'POST',
                form: {
                    amount: rubles.toFixed(2),
                    order_id: orderId,
                    description,
                    name: description,
                    type: 'normal',
                    shop_id: shopId,
                    currency_in: 'RUB',
                    custom: orderId,
                    payer_pays_commission: '0',
                    success_url: successUrl,
                    fail_url: failUrl,
                },
            });
            const url = json.link_page_url || json.link_url;
            if (!url) throw new PallyError('Pally gave no payment link');
            return { billId: String(json.bill_id || json.id || ''), url };
        },

        /** Whether a bill is paid: 'paid', 'failed' or 'pending'. */
        async billStatus(billId) {
            const json = await call(`/bill/status?id=${encodeURIComponent(billId)}`);
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
