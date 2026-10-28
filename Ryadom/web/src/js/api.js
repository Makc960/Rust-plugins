// The app's server (server/ in the project): accounts, friends, positions. [http] is the
// platform's (see platform.js), so the phone makes the requests itself.

export class ApiError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

export class Api {
    constructor({ server, http, token = null }) {
        this.server = server.replace(/\/+$/, '');
        this.http = http;
        this.token = token;
    }

    async call(method, path, body) {
        let answer;
        try {
            answer = await this.http(`${this.server}${path}`, {
                method,
                body: body === undefined ? null : JSON.stringify(body),
                type: body === undefined ? '' : 'application/json',
                headers: this.token ? { Authorization: `Bearer ${this.token}` } : {},
                timeout: 15000,
            });
        } catch (e) {
            throw new ApiError(0, 'offline');
        }
        let json = null;
        try {
            json = JSON.parse(answer.text);
        } catch (e) {
            json = null;
        }
        if (answer.status < 200 || answer.status >= 300) throw new ApiError(answer.status, json?.error || `http ${answer.status}`);
        if (!json) throw new ApiError(answer.status, 'not json');
        return json;
    }

    /** A new account: resolves to {token, me}, and this client uses the token from then on. */
    async register(name, color) {
        const answer = await this.call('POST', '/api/register', { name, color });
        this.token = answer.token;
        return answer;
    }

    me() {
        return this.call('GET', '/api/me');
    }

    update(fields) {
        return this.call('PATCH', '/api/me', fields);
    }

    newCode() {
        return this.call('POST', '/api/me/code', {});
    }

    remove() {
        return this.call('DELETE', '/api/me');
    }

    /** {me, friends, incoming, outgoing, now}. */
    view() {
        return this.call('GET', '/api/friends');
    }

    /** {result: requested|friends|already, name}. */
    add(code) {
        return this.call('POST', '/api/friends', { code });
    }

    accept(id) {
        return this.call('POST', `/api/friends/${encodeURIComponent(id)}/accept`, {});
    }

    decline(id) {
        return this.call('POST', `/api/friends/${encodeURIComponent(id)}/decline`, {});
    }

    unfriend(id) {
        return this.call('DELETE', `/api/friends/${encodeURIComponent(id)}`);
    }

    locate(fix) {
        return this.call('POST', '/api/location', fix);
    }
}

/** The address an invite link has for [code] on [server]. */
export function inviteLink(server, code) {
    return `${server.replace(/\/+$/, '')}/add/${code}`;
}

/** A friend code in a link or a text someone pasted: «…/add/ABCD-EFGH», «ryadom://add/abcdefgh». */
export function codeIn(text) {
    const t = String(text || '').toUpperCase();
    const m = t.match(/ADD\/([A-Z2-9]{4})-?([A-Z2-9]{4})(?![A-Z0-9])/) || t.match(/(?<![A-Z0-9])([A-Z2-9]{4})-?([A-Z2-9]{4})(?![A-Z0-9])/);
    return m ? `${m[1]}-${m[2]}` : null;
}
