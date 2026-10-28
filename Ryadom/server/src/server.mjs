// The server's HTTP side. Behind nginx on HTTPS; it listens on 127.0.0.1 only.
//
//   POST   /api/register          {name, color?}       -> {token, me}     a new account
//   GET    /api/me                                     -> me
//   PATCH  /api/me                {name?, color?, sharing?}
//   POST   /api/me/code                                -> me with a new friend code
//   DELETE /api/me                                     deletes the account for good
//   GET    /api/friends                                -> {me, friends, incoming, outgoing, now}
//   POST   /api/friends           {code}               -> {result: requested|friends|already, name}
//   POST   /api/friends/:id/accept | /decline
//   DELETE /api/friends/:id                            ends a friendship or takes back a request
//   POST   /api/location          {lat, lon, accuracy, speed, heading, battery, charging, time}
//                                                      -> {taken, incoming}
//   GET    /add/:code             the page an invite link opens
//   GET    /privacy               the privacy policy
//   GET    /health
//
// Everything under /api but register wants "Authorization: Bearer <token>".
// Settings come from the environment (see deploy/ryadom.env.example).

import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';

import { Friends, FriendsError, normalCode, showCode } from './friends.mjs';
import { invitePage, privacyPage } from './pages.mjs';

const MAX_BODY = 8 * 1024;

/** At most [max] events per [key] in [windowMs]. */
export function limiter(max, windowMs) {
    const seen = new Map();
    return (key, now = Date.now()) => {
        const times = (seen.get(key) || []).filter((t) => now - t < windowMs);
        if (times.length >= max) {
            seen.set(key, times);
            return false;
        }
        times.push(now);
        seen.set(key, times);
        if (seen.size > 50000) seen.clear();
        return true;
    };
}

export function makeHandler(friends, { log = console, download = 'https://github.com/Makc960/Rust-plugins/releases/tag/ryadom', contact = '' } = {}) {
    const registers = limiter(10, 60 * 60 * 1000);
    const adds = limiter(30, 60 * 60 * 1000);
    const requests = limiter(600, 60 * 1000);

    return async (req, res) => {
        const url = new URL(req.url, 'http://localhost');
        // Behind the proxy on this machine, the client is the address the proxy added last.
        const local = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress);
        const ip = (local && String(req.headers['x-forwarded-for'] || '').split(',').pop().trim()) || req.socket.remoteAddress;
        const path = url.pathname;
        try {
            if (!requests(ip)) throw new FriendsError(429, 'slow down');
            if (path === '/health') return text(res, 200, 'ok');
            if (req.method === 'GET' && path === '/privacy') return html(res, privacyPage({ contact }));
            const invite = path.match(/^\/add\/([A-Za-z0-9-]{4,20})$/);
            if (req.method === 'GET' && invite) {
                const code = normalCode(invite[1]);
                const owner = code && friends.users[friends.store.data.codes[code]];
                return html(res, invitePage({ code: code ? showCode(code) : invite[1], name: owner?.name, color: owner?.color, download }));
            }
            if (!path.startsWith('/api/')) return text(res, 404, 'not found');

            if (req.method === 'POST' && path === '/api/register') {
                if (!registers(ip)) throw new FriendsError(429, 'too many accounts');
                const body = await readJson(req);
                return json(res, 200, friends.register(body.name, body.color));
            }

            const token = String(req.headers.authorization || '').replace(/^Bearer\s+/i, '');
            const user = friends.auth(token);
            if (!user) throw new FriendsError(401, 'who are you');

            if (path === '/api/me') {
                if (req.method === 'GET') return json(res, 200, friends.me(user));
                if (req.method === 'PATCH') return json(res, 200, friends.update(user, await readJson(req)));
                if (req.method === 'DELETE') {
                    friends.remove(user);
                    return json(res, 200, { deleted: true });
                }
            }
            if (req.method === 'POST' && path === '/api/me/code') return json(res, 200, friends.newCode(user));
            if (req.method === 'GET' && path === '/api/friends') return json(res, 200, friends.view(user));
            if (req.method === 'POST' && path === '/api/friends') {
                if (!adds(user.id)) throw new FriendsError(429, 'too many requests');
                const body = await readJson(req);
                return json(res, 200, friends.add(user, body.code));
            }
            const one = path.match(/^\/api\/friends\/([A-Za-z0-9_-]{6,20})(\/accept|\/decline)?$/);
            if (one) {
                const [, id, action] = one;
                if (req.method === 'POST' && action === '/accept') friends.accept(user, id);
                else if (req.method === 'POST' && action === '/decline') friends.decline(user, id);
                else if (req.method === 'DELETE' && !action) friends.unfriend(user, id);
                else throw new FriendsError(405, 'method');
                return json(res, 200, friends.view(user));
            }
            if (req.method === 'POST' && path === '/api/location') {
                const taken = friends.locate(user, await readJson(req));
                return json(res, 200, { taken, incoming: friends.view(user).incoming });
            }
            throw new FriendsError(404, 'not found');
        } catch (e) {
            const status = e instanceof FriendsError ? e.status : e instanceof SyntaxError ? 400 : 500;
            if (status >= 500) log.error(e);
            json(res, status, { error: status >= 500 ? 'server error' : e.message });
        }
    };
}

export function makeServer(friends, options) {
    return createServer(makeHandler(friends, options));
}

async function readJson(req) {
    const body = await new Promise((resolve, reject) => {
        let size = 0;
        const parts = [];
        req.on('data', (chunk) => {
            size += chunk.length;
            if (size > MAX_BODY) {
                reject(new FriendsError(413, 'too large'));
                req.destroy();
            } else {
                parts.push(chunk);
            }
        });
        req.on('end', () => resolve(Buffer.concat(parts).toString('utf8')));
        req.on('error', reject);
    });
    const value = JSON.parse(body || '{}');
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new FriendsError(400, 'body');
    return value;
}

function json(res, status, body) {
    res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
    res.end(JSON.stringify(body));
}

function text(res, status, body) {
    res.writeHead(status, { 'content-type': 'text/plain; charset=utf-8' });
    res.end(body);
}

function html(res, body) {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
    res.end(body);
}

// ---- Run ----

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const env = process.env;
    const dir = env.DATA_DIR || '/var/lib/ryadom';
    const friends = new Friends({ file: `${dir}/friends.json`, placesFile: `${dir}/places.json` });
    const port = Number(env.PORT || 8790);
    const host = env.HOST || '127.0.0.1';
    makeServer(friends, { download: env.DOWNLOAD_URL || undefined, contact: env.CONTACT || '' })
        .listen(port, host, () => console.log(`ryadom on ${host}:${port}`));
    // Positions change all the time: they go to disk every half minute, and on the way out.
    setInterval(() => friends.snapshot(), 30000);
    for (const signal of ['SIGTERM', 'SIGINT']) {
        process.on(signal, () => {
            friends.snapshot();
            process.exit(0);
        });
    }
}
