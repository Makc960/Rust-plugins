// Checks the settings in a shop.env file against Pally: node src/check.mjs /etc/basedefense-shop/shop.env
// Prints what is wrong in Russian, for install.sh; exits 0 when Pally takes the token.

import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

import { DEFAULT_API, makePally } from './pally.mjs';

/** KEY=value lines, as systemd reads them: comments and blank lines skipped, quotes taken off. */
export function readEnv(text) {
    const env = {};
    for (const line of text.split('\n')) {
        const m = line.match(/^\s*([A-Z_][A-Z0-9_]*)=(.*)$/);
        if (m) env[m[1]] = m[2].trim().replace(/^(['"])(.*)\1$/, '$2');
    }
    return env;
}

const MESSAGES = {
    ok: 'Pally принял токен.',
    'bad-token': 'Pally не принял токен (401): проверьте PALLY_TOKEN в кабинете Pally, раздел API.',
    banned: 'Pally ответил 403: магазин или аккаунт заблокирован, напишите в поддержку Pally.',
    unreachable: 'Pally не отвечает: проверьте, что с сервера открывается https://pal24.pro.',
};

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const env = readEnv(readFileSync(process.argv[2] || '/etc/basedefense-shop/shop.env', 'utf8'));
    if (!env.PALLY_TOKEN || !env.PALLY_SHOP_ID) {
        console.log('Не заданы PALLY_TOKEN и PALLY_SHOP_ID.');
        process.exit(1);
    }
    const pally = makePally({ token: env.PALLY_TOKEN, shopId: env.PALLY_SHOP_ID, api: env.PALLY_API || DEFAULT_API });
    const state = await pally.checkToken();
    console.log(MESSAGES[state]);
    process.exit(state === 'ok' ? 0 : 1);
}
