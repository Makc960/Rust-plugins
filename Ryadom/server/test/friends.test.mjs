import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

import { cleanName, COLORS, Friends, MAX_FRIENDS, normalCode } from '../src/friends.mjs';
import { limiter, makeServer } from '../src/server.mjs';

function world(start = Date.UTC(2026, 9, 28, 12)) {
    const clock = { now: start };
    const friends = new Friends({ now: () => clock.now });
    return { friends, clock };
}

/** Two people who are friends: [a] asked, [b] accepted. */
function pair(friends) {
    const a = friends.register('Аня');
    const b = friends.register('Боря');
    const userA = friends.auth(a.token);
    const userB = friends.auth(b.token);
    assert.equal(friends.add(userA, b.me.code).result, 'requested');
    friends.accept(userB, userA.id);
    return { a, b, userA, userB };
}

test('an account has a name, a color, a friend code and a secret token kept only as a hash', () => {
    const { friends } = world();
    const { token, me } = friends.register('  Маша   Петрова ');
    assert.equal(me.name, 'Маша Петрова');
    assert.ok(COLORS.includes(me.color));
    assert.match(me.code, /^[A-Z2-9]{4}-[A-Z2-9]{4}$/);
    assert.equal(friends.auth(token).id, me.id);
    assert.equal(friends.auth('nope'), null);
    assert.equal(friends.auth(''), null);
    assert.ok(!JSON.stringify(friends.store.data).includes(token), 'the token itself is not stored');
    assert.throws(() => friends.register('   '), /name/);
    assert.equal(cleanName('a\u0000b\u202ec'), 'abc');
    assert.equal([...cleanName('я'.repeat(50))].length, 30);
});

test('friend codes are read as typed', () => {
    const { friends } = world();
    const { me } = friends.register('Аня');
    assert.equal(normalCode(me.code.toLowerCase().replace('-', ' ')), me.code.replace('-', ''));
    assert.equal(normalCode('ABCD-EFG'), null);
    assert.equal(normalCode('ABCD-EFG0'), null, 'no 0, O, 1 or I in codes');
});

test('asking and accepting makes friends both ways; each then sees where the other is', () => {
    const { friends } = world();
    const a = friends.register('Аня');
    const b = friends.register('Боря');
    const userA = friends.auth(a.token);
    const userB = friends.auth(b.token);
    assert.deepEqual(friends.add(userA, b.me.code), { result: 'requested', name: 'Боря' });
    assert.equal(friends.add(userA, b.me.code).result, 'already');
    assert.deepEqual(friends.view(userB).incoming.map((p) => p.name), ['Аня']);
    assert.deepEqual(friends.view(userA).outgoing.map((p) => p.name), ['Боря']);
    friends.locate(userA, { lat: 55.75, lon: 37.62, accuracy: 12, battery: 81 });
    assert.deepEqual(friends.view(userB).friends, [], 'not before accepting');
    friends.accept(userB, userA.id);
    const seen = friends.view(userB).friends;
    assert.equal(seen.length, 1);
    assert.equal(seen[0].name, 'Аня');
    assert.deepEqual([seen[0].place.lat, seen[0].place.lon, seen[0].place.accuracy, seen[0].place.battery], [55.75, 37.62, 12, 81]);
    assert.equal(friends.view(userA).friends[0].place, null, 'Боря has not sent a position yet');
    assert.deepEqual(friends.view(userA).outgoing, []);
    assert.deepEqual(friends.view(userB).incoming, []);
});

test('asking someone who already asked you makes you friends at once', () => {
    const { friends } = world();
    const a = friends.register('Аня');
    const b = friends.register('Боря');
    friends.add(friends.auth(a.token), b.me.code);
    assert.equal(friends.add(friends.auth(b.token), a.me.code).result, 'friends');
    assert.equal(friends.view(friends.auth(a.token)).friends.length, 1);
});

test('declining, taking back and ending a friendship', () => {
    const { friends } = world();
    const { userA, userB, a } = pair(friends);
    friends.unfriend(userA, userB.id);
    assert.deepEqual(friends.view(userB).friends, []);
    assert.deepEqual(friends.view(userA).friends, []);
    friends.add(userB, a.me.code);
    friends.decline(userA, userB.id);
    assert.deepEqual(friends.view(userB).outgoing, []);
    friends.add(userB, a.me.code);
    friends.unfriend(userB, userA.id);
    assert.deepEqual(friends.view(userA).incoming, [], 'a request taken back is gone');
    assert.throws(() => friends.accept(userA, userB.id), /no such request/);
    assert.throws(() => friends.add(userA, a.me.code), /self/);
    assert.throws(() => friends.add(userA, 'ZZZZ-ZZZZ'), /no such code/);
});

test('hiding takes the position away from friends at once, and showing brings it back', () => {
    const { friends } = world();
    const { userA, userB } = pair(friends);
    friends.locate(userA, { lat: 55.75, lon: 37.62 });
    friends.update(userA, { sharing: false });
    const hidden = friends.view(userB).friends[0];
    assert.equal(hidden.sharing, false);
    assert.equal(hidden.place, null);
    friends.update(userA, { sharing: true });
    assert.equal(friends.view(userB).friends[0].place.lat, 55.75);
});

test('a new code stops the old one; deleting an account takes it from everyone', () => {
    const { friends } = world();
    const { userA, userB, a } = pair(friends);
    const old = a.me.code;
    const fresh = friends.newCode(userA).code;
    assert.notEqual(fresh, old);
    const c = friends.register('Вера');
    assert.throws(() => friends.add(friends.auth(c.token), old), /no such code/);
    friends.add(friends.auth(c.token), fresh);
    friends.locate(userA, { lat: 1, lon: 2 });
    friends.remove(userA);
    assert.equal(friends.auth(a.token), null);
    assert.deepEqual(friends.view(userB).friends, []);
    assert.deepEqual(friends.view(friends.auth(c.token)).outgoing, []);
    assert.equal(friends.places.has(userA.id), false);
});

test('positions: checked, rounded, one every two seconds at most', () => {
    const { friends, clock } = world();
    const { userA } = pair(friends);
    assert.throws(() => friends.locate(userA, { lat: 91, lon: 0 }), /position/);
    assert.throws(() => friends.locate(userA, { lat: 'x', lon: 0 }), /position/);
    assert.equal(friends.locate(userA, { lat: 55.123456789, lon: 37.1, accuracy: -5, battery: 300, time: clock.now + 99999 }), true);
    const p = friends.places.get(userA.id);
    assert.deepEqual([p.lat, p.accuracy, p.battery, p.time], [55.123457, 50, null, clock.now], 'a time from the future is now');
    assert.equal(friends.locate(userA, { lat: 1, lon: 1 }), false, 'too soon');
    clock.now += 2500;
    assert.equal(friends.locate(userA, { lat: 1, lon: 1 }), true);
});

test('friends and requests have limits', () => {
    const { friends } = world();
    const star = friends.register('Звезда');
    const starUser = friends.auth(star.token);
    for (let i = 0; i < MAX_FRIENDS; i++) {
        const f = friends.register(`Друг ${i}`);
        friends.add(friends.auth(f.token), star.me.code);
        if (starUser.incoming.length) friends.accept(starUser, starUser.incoming[0]);
    }
    assert.equal(starUser.friends.length, MAX_FRIENDS);
    const late = friends.register('Опоздал');
    assert.throws(() => friends.add(friends.auth(late.token), star.me.code), /too many friends/);
});

test('accounts and positions survive a restart', () => {
    const dir = mkdtempSync(join(tmpdir(), 'ryadom-'));
    try {
        const files = { file: join(dir, 'friends.json'), placesFile: join(dir, 'places.json') };
        const first = new Friends(files);
        const { userA, a } = pair(first);
        first.locate(userA, { lat: 55.7, lon: 37.6 });
        first.snapshot();
        assert.ok(!readFileSync(files.file, 'utf8').includes(a.token));
        const second = new Friends(files);
        const again = second.auth(a.token);
        assert.equal(again.name, 'Аня');
        assert.equal(second.view(second.users[again.friends[0]]).friends[0].place.lat, 55.7);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test('the limiter lets a few through and then waits', () => {
    const allow = limiter(2, 1000);
    assert.deepEqual([allow('x', 0), allow('x', 10), allow('x', 20), allow('y', 20), allow('x', 1500)], [true, true, false, true, true]);
});

test('the HTTP side: register, add by code, accept, positions, invite page, privacy', async () => {
    const { friends } = world(Date.now());
    const server = makeServer(friends, { log: { error() {} } });
    await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
    const base = `http://127.0.0.1:${server.address().port}`;
    const call = async (method, path, token, body) => {
        const r = await fetch(base + path, {
            method,
            headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), 'content-type': 'application/json' },
            body: body === undefined ? undefined : JSON.stringify(body),
        });
        return { status: r.status, body: r.headers.get('content-type')?.includes('json') ? await r.json() : await r.text() };
    };
    try {
        const a = (await call('POST', '/api/register', null, { name: 'Аня', color: '#46A758' })).body;
        const b = (await call('POST', '/api/register', null, { name: 'Боря' })).body;
        assert.equal(a.me.color, '#46A758');
        assert.equal((await call('GET', '/api/me', 'wrong')).status, 401);
        assert.equal((await call('GET', '/api/me', a.token)).body.name, 'Аня');
        assert.deepEqual((await call('POST', '/api/friends', a.token, { code: b.me.code })).body, { result: 'requested', name: 'Боря' });
        const loc = await call('POST', '/api/location', b.token, { lat: 55.75, lon: 37.62, accuracy: 9 });
        assert.deepEqual(loc.body, { taken: true, incoming: [{ id: a.me.id, name: 'Аня', color: '#46A758' }] }, 'the background service hears of requests');
        const view = (await call('POST', `/api/friends/${a.me.id}/accept`, b.token)).body;
        assert.equal(view.friends[0].name, 'Аня');
        const seen = (await call('GET', '/api/friends', a.token)).body;
        assert.equal(seen.friends[0].place.lat, 55.75);
        assert.equal(seen.friends[0].sharing, true);
        assert.equal((await call('PATCH', '/api/me', b.token, { sharing: false })).body.sharing, false);
        assert.equal((await call('GET', '/api/friends', a.token)).body.friends[0].place, null);
        assert.equal((await call('POST', '/api/friends', a.token, { code: 'ZZZZ-ZZZZ' })).status, 404);
        assert.equal((await call('POST', '/api/location', a.token, { lat: 'north' })).status, 400);
        assert.equal((await call('POST', '/api/register', null, [1, 2])).status, 400);
        const invite = await call('GET', `/add/${a.me.code}`);
        assert.match(invite.body, /Аня зовёт тебя в друзья/);
        assert.match(invite.body, new RegExp(`ryadom://add/${a.me.code}`));
        assert.match((await call('GET', '/add/QQQQ-QQQQ')).body, /Приглашение не найдено/);
        assert.match((await call('GET', '/privacy')).body, /История перемещений не ведётся|история перемещений не ведётся/);
        assert.deepEqual((await call('DELETE', '/api/me', b.token)).body, { deleted: true });
        assert.deepEqual((await call('GET', '/api/friends', a.token)).body.friends, []);
        // Names go into the invite page escaped.
        const evil = (await call('POST', '/api/register', null, { name: '<script>x</script>' })).body;
        assert.doesNotMatch((await call('GET', `/add/${evil.me.code}`)).body, /<script>x/);
    } finally {
        server.close();
    }
});
