// The two web pages the server shows people: an invite (what a friend's link opens) and the
// privacy policy the stores ask for.

const STYLE = `body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0f1a14;color:#eef3ee;
font:17px/1.5 system-ui,Roboto,sans-serif}main{padding:28px 22px;max-width:520px}h1{font-size:26px;margin:0 0 12px}
.dot{width:84px;height:84px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:34px;font-weight:700;color:#fff;margin:0 auto 18px;border:4px solid #fff3}
a.button{display:block;margin:14px 0 0;padding:15px 20px;border-radius:14px;background:#3ddc84;color:#06240f;font-weight:700;text-decoration:none;text-align:center}
a.second{background:#ffffff1f;color:#eef3ee}.code{font:700 22px ui-monospace,monospace;letter-spacing:2px;color:#3ddc84}
.small{color:#a9b8ad;font-size:14px}h2{font-size:19px;margin:22px 0 6px}li{margin:4px 0}`;

function escape(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function page(title, body) {
    return `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title)}</title><style>${STYLE}</style></head><body><main>${body}</main></body></html>`;
}

function initials(name) {
    const words = String(name).split(/\s+/).filter(Boolean);
    return escape(((words[0]?.[0] || '') + (words[1]?.[0] || '')).toUpperCase() || '?');
}

/** The page an invite link opens: who asks, and buttons into the app or to get it. */
export function invitePage({ code, name, color, download }) {
    if (!name) {
        return page('Приглашение в «Рядом»', `<h1>Приглашение не найдено</h1>
<p>Код <span class="code">${escape(code)}</span> не подходит. Возможно, друг сменил код — попроси новую ссылку.</p>
<a class="button second" href="${escape(download)}">Скачать «Рядом»</a>`);
    }
    return page(`${name} зовёт в «Рядом»`, `<div class="dot" style="background:${escape(color)}">${initials(name)}</div>
<h1>${escape(name)} зовёт тебя в друзья</h1>
<p>В «Рядом» друзья видят друг друга на карте. Код друга: <span class="code">${escape(code)}</span></p>
<a class="button" href="ryadom://add/${escape(code)}">Открыть в приложении</a>
<a class="button second" href="${escape(download)}">Скачать «Рядом»</a>
<p class="small">Если приложение уже стоит, но кнопка не сработала: открой «Рядом» → «Добавить друга» и введи код.</p>`);
}

export function privacyPage({ contact }) {
    return page('«Рядом»: политика конфиденциальности', `<h1>Политика конфиденциальности «Рядом»</h1>
<p>«Рядом» показывает друзьям, где ты находишься, — только тем, кого ты сам добавил и кто добавил тебя.</p>
<h2>Что хранится на сервере</h2>
<ul>
<li>Имя и цвет, которые ты выбрал, и код друга. Телефон, почта и контакты не нужны и не собираются.</li>
<li>Последнее местоположение: координаты, точность, скорость и направление, заряд батареи и время.
Хранится только последняя точка — история перемещений не ведётся.</li>
<li>Список друзей и заявок в друзья.</li>
</ul>
<h2>Кто это видит</h2>
<ul>
<li>Только твои друзья — те, с кем дружба подтверждена с обеих сторон.</li>
<li>Кнопка «Скрыть меня» сразу прячет твоё местоположение от всех друзей.</li>
<li>Данные не продаются и не передаются третьим лицам, реклама не показывается.</li>
</ul>
<h2>Как удалить</h2>
<p>В приложении: Профиль → «Удалить аккаунт». Аккаунт, местоположение и дружбы стираются с сервера сразу.</p>
<h2>Карта</h2>
<p>Спутниковые снимки загружаются с серверов Esri, схема и подписи — с OpenFreeMap. Эти сервисы видят твой IP-адрес, как любой сайт, но не твоё местоположение и не аккаунт.</p>
${contact ? `<h2>Связь</h2><p>${escape(contact)}</p>` : ''}`);
}
