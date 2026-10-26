// The shop server's HTTP side. Behind Caddy (or nginx) on HTTPS; it listens on 127.0.0.1 only.
//
//   POST /api/order          {code, product}  -> {order, url}       the game opens url to pay
//   GET  /api/order/:id?code=                -> {status, vipUntil?} pending, paid or failed
//   GET  /api/purchases?code=                -> {owned, vipUntil, passes}
//   POST /pally/result       Pally's payment notice (Result URL in Pally's cabinet)
//   GET  /return             the page Pally sends the player back to, with a button into the game
//   GET  /health
//
// Settings come from the environment (see deploy/shop.env.example).

import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';

import { Db } from './db.mjs';
import { DEFAULT_API, makePally } from './pally.mjs';
import { Shop, ShopError } from './shop.mjs';

const MAX_BODY = 16 * 1024;
// New orders a single address may make in ORDER_WINDOW: enough for a player, not for a flood.
const ORDERS_PER_WINDOW = 20;
const ORDER_WINDOW = 10 * 60 * 1000;

export function makeServer(shop, { log = console } = {}) {
    const recent = new Map();

    function allowOrder(ip) {
        const now = Date.now();
        const times = (recent.get(ip) || []).filter((t) => now - t < ORDER_WINDOW);
        if (times.length >= ORDERS_PER_WINDOW) return false;
        times.push(now);
        recent.set(ip, times);
        if (recent.size > 10000) recent.clear();
        return true;
    }

    return createServer(async (req, res) => {
        const url = new URL(req.url, 'http://localhost');
        // Behind the proxy on this machine, the client is the address the proxy added last.
        const local = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress);
        const ip = (local && String(req.headers['x-forwarded-for'] || '').split(',').pop().trim()) || req.socket.remoteAddress;
        try {
            if (req.method === 'POST' && url.pathname === '/api/order') {
                if (!allowOrder(ip)) throw new ShopError(429, 'too many orders');
                const body = JSON.parse((await readBody(req)) || '{}');
                return json(res, 200, await shop.order(body.code, body.product));
            }
            const orderPath = url.pathname.match(/^\/api\/order\/([a-z0-9]{6,40})$/);
            if (req.method === 'GET' && orderPath) {
                return json(res, 200, await shop.status(url.searchParams.get('code'), orderPath[1]));
            }
            if (req.method === 'GET' && url.pathname === '/api/purchases') {
                return json(res, 200, shop.purchases(url.searchParams.get('code')));
            }
            if (req.method === 'POST' && url.pathname === '/pally/result') {
                const body = await readBody(req);
                const type = String(req.headers['content-type'] || '');
                const fields = type.includes('json') ? JSON.parse(body || '{}') : Object.fromEntries(new URLSearchParams(body));
                const taken = shop.postback(fields);
                if (!taken) log.warn(`postback with a wrong signature from ${ip}`);
                res.writeHead(taken ? 200 : 400, { 'content-type': 'text/plain; charset=utf-8' });
                return res.end(taken ? 'OK' : 'bad signature');
            }
            if (req.method === 'GET' && url.pathname === '/return') {
                res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
                return res.end(returnPage(url.searchParams.get('result') !== 'fail'));
            }
            if (req.method === 'GET' && url.pathname === '/health') {
                res.writeHead(200, { 'content-type': 'text/plain' });
                return res.end('ok');
            }
            res.writeHead(404);
            res.end();
        } catch (e) {
            const status = e instanceof ShopError ? e.status : e instanceof SyntaxError ? 400 : 500;
            if (status >= 500) log.error(e);
            json(res, status, { error: status >= 500 ? 'server error' : e.message });
        }
    });
}

function readBody(req) {
    return new Promise((resolve, reject) => {
        let size = 0;
        const parts = [];
        req.on('data', (chunk) => {
            size += chunk.length;
            if (size > MAX_BODY) {
                reject(new ShopError(413, 'too large'));
                req.destroy();
            } else {
                parts.push(chunk);
            }
        });
        req.on('end', () => resolve(Buffer.concat(parts).toString('utf8')));
        req.on('error', reject);
    });
}

function json(res, status, body) {
    res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
    res.end(JSON.stringify(body));
}

/** Where Pally sends the player after paying: a button back into the game, which checks the order. */
function returnPage(success) {
    const title = success ? 'Оплата прошла' : 'Оплата не прошла';
    const text = success ? 'Спасибо за поддержку! Вернитесь в игру — покупка появится сама.' : 'Деньги не списаны. Вернитесь в игру и попробуйте ещё раз.';
    return `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title}</title><style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#1d2116;color:#e9e4cf;font:17px/1.4 system-ui,Roboto,sans-serif;text-align:center}
main{padding:28px;max-width:420px}h1{font-size:26px;margin:0 0 12px;color:${success ? '#e8c34a' : '#e86a4a'}}
a{display:inline-block;margin-top:22px;padding:16px 28px;border-radius:12px;background:#e8c34a;color:#1d2116;font-weight:700;text-decoration:none}
</style></head><body><main><h1>${title}</h1><p>${text}</p><a href="com.makc.basedefense://paid">Вернуться в игру</a></main></body></html>`;
}

// ---- Run ----

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const env = process.env;
    for (const name of ['PALLY_TOKEN', 'PALLY_SHOP_ID', 'PUBLIC_URL']) {
        if (!env[name]) {
            console.error(`${name} is not set: see deploy/shop.env.example`);
            process.exit(1);
        }
    }
    const db = new Db(env.DATA_FILE || '/var/lib/basedefense-shop/db.json');
    const pally = makePally({ token: env.PALLY_TOKEN, shopId: env.PALLY_SHOP_ID, api: env.PALLY_API || DEFAULT_API });
    const shop = new Shop({ db, pally, token: env.PALLY_TOKEN, publicUrl: env.PUBLIC_URL });
    const port = Number(env.PORT || 8787);
    makeServer(shop).listen(port, env.HOST || '127.0.0.1', () => console.log(`shop on ${env.HOST || '127.0.0.1'}:${port}`));
}
