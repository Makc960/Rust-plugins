#!/usr/bin/env bash
# Installs or updates the Base Defense shop server on Ubuntu or Debian:
#
#   sudo bash install.sh shop.example.ru            the shop is the whole site
#   sudo bash install.sh example.ru/basedefense     the shop lives under a path of a site
#   sudo bash install.sh --pally                    asks for the Pally shop ID and token again
#
# Without a domain it uses <ip>.sslip.io, a name that always points at this server's address. When
# the domain is already a site of the nginx on this machine, the shop joins it under /basedefense.
# Asks for the Pally shop ID and API token once and keeps them in /etc/basedefense-shop/shop.env.
# Run it again to update the server: settings and purchases are kept.
set -euo pipefail

APP=/opt/basedefense-shop
ETC=/etc/basedefense-shop
ENV_FILE=$ETC/shop.env
DATA=/var/lib/basedefense-shop
USER_NAME=basedefense-shop
PORT=8787
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SRC=$(dirname "$HERE")

say() { printf '\n\033[1;33m== %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m%s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "Запустите через sudo: sudo bash $0 ваш-домен"
command -v apt-get >/dev/null || die "Этот скрипт для Ubuntu и Debian (нужен apt-get)."
[ -f "$SRC/src/server.mjs" ] || die "Не найден $SRC/src/server.mjs: запускайте install.sh из папки BaseDefense/server/deploy."
export DEBIAN_FRONTEND=noninteractive

say "Пакеты"
apt-get update -qq
apt-get install -y -qq curl ca-certificates gnupg iproute2 >/dev/null

# ---- Settings file ----

install -d -m 700 "$ETC"
[ -f "$ENV_FILE" ] || install -m 600 "$HERE/shop.env.example" "$ENV_FILE"
chown root:root "$ENV_FILE"
chmod 600 "$ENV_FILE"

get_var() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1; }
set_var() {
	local tmp
	tmp=$(mktemp "$ETC/.shop.env.XXXXXX")
	K="$1" V="$2" awk 'index($0, ENVIRON["K"] "=") == 1 { if (!done) print ENVIRON["K"] "=" ENVIRON["V"]; done = 1; next }
		{ print } END { if (!done) print ENVIRON["K"] "=" ENVIRON["V"] }' "$ENV_FILE" >"$tmp"
	cat "$tmp" >"$ENV_FILE"
	rm -f "$tmp"
}

ADDRESS=
ASK_PALLY=0
for a in "$@"; do
	case "$a" in
	--pally) ASK_PALLY=1 ;;
	*) ADDRESS=$a ;;
	esac
done
# The shop's address: a domain, maybe with a path, as typed or as set up before.
[ -n "$ADDRESS" ] || ADDRESS=$(get_var PUBLIC_URL)
ADDRESS=${ADDRESS#https://}
ADDRESS=${ADDRESS#http://}
ADDRESS=${ADDRESS%/}
DOMAIN=${ADDRESS%%/*}
PREFIX=
[ "$ADDRESS" = "$DOMAIN" ] || PREFIX="/${ADDRESS#*/}"
[ "$DOMAIN" = shop.example.ru ] && DOMAIN= && PREFIX=
MY_IP=$(curl -4 -fsS --max-time 10 https://api.ipify.org || true)
if [ -z "$DOMAIN" ]; then
	[ -n "$MY_IP" ] || die "Не удалось узнать IP сервера. Укажите домен: sudo bash $0 shop.ваш-домен.ru"
	DOMAIN="${MY_IP//./-}.sslip.io"
fi
case "$DOMAIN" in *[!a-zA-Z0-9.-]* | "") die "Странный домен: $DOMAIN" ;; esac
[[ "$PREFIX" =~ ^(/[A-Za-z0-9_-]+)?$ ]] || die "Странный путь: $PREFIX (нужно вроде /basedefense)"

if [ "$ASK_PALLY" = 1 ]; then
	set_var PALLY_SHOP_ID ""
	set_var PALLY_TOKEN ""
fi
if [ -t 0 ]; then
	if [ -z "$(get_var PALLY_SHOP_ID)" ]; then
		read -rp "ID магазина (shop_id) из кабинета Pally: " v
		set_var PALLY_SHOP_ID "$(printf '%s' "$v" | tr -d '[:space:]')"
	fi
	if [ -z "$(get_var PALLY_TOKEN)" ]; then
		read -rsp "API-токен Pally (при вводе не виден): " v
		echo
		set_var PALLY_TOKEN "$(printf '%s' "$v" | tr -d '[:space:]')"
	fi
fi
CONFIGURED=1
TOKEN_BAD=0
CHECK=
[ -n "$(get_var PALLY_SHOP_ID)" ] && [ -n "$(get_var PALLY_TOKEN)" ] || CONFIGURED=0

# ---- Node.js ----

say "Node.js"
node_major() { node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0; }
if [ "$(node_major)" -lt 20 ]; then
	curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >/dev/null
	apt-get install -y -qq nodejs >/dev/null
fi
NODE=$(command -v node)
echo "$NODE $(node --version)"

# ---- The server ----

say "Сервер магазина"
id -u "$USER_NAME" >/dev/null 2>&1 ||
	useradd --system --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin "$USER_NAME"
rm -rf "$APP"
install -d -m 755 "$APP" "$APP/src"
install -m 644 "$SRC/package.json" "$APP/package.json"
install -m 644 "$SRC"/src/*.mjs "$APP/src/"
sed "s#^ExecStart=.*#ExecStart=$NODE $APP/src/server.mjs#" "$HERE/basedefense-shop.service" \
	>/etc/systemd/system/basedefense-shop.service
sed "s#^NODE=.*#NODE=$NODE#" "$HERE/basedefense-shop" >/usr/local/bin/basedefense-shop
chmod 755 /usr/local/bin/basedefense-shop
systemctl daemon-reload
systemctl enable basedefense-shop >/dev/null 2>&1
if [ "$CONFIGURED" = 1 ]; then
	systemctl restart basedefense-shop
	for _ in $(seq 1 20); do
		curl -fsS --max-time 2 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && break
		sleep 0.5
	done
	curl -fsS --max-time 2 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 ||
		die "Сервер не запустился. Журнал: journalctl -u basedefense-shop -n 50"
	echo "работает на 127.0.0.1:$PORT"
	CHECK=$("$NODE" "$APP/src/check.mjs" "$ENV_FILE") || TOKEN_BAD=1
	echo "$CHECK"
fi

# A copy of the purchases every day, the last 30 kept.
cat >/etc/cron.daily/basedefense-shop-backup <<EOF
#!/bin/sh
[ -f $DATA/db.json ] || exit 0
install -d -m 700 /var/backups/basedefense-shop
cp $DATA/db.json /var/backups/basedefense-shop/db-\$(date +%F).json
chmod 600 /var/backups/basedefense-shop/*.json
ls -1t /var/backups/basedefense-shop/db-*.json | tail -n +31 | xargs -r rm -f
EOF
chmod 755 /etc/cron.daily/basedefense-shop-backup

# ---- HTTPS ----

say "HTTPS для $DOMAIN"
if command -v ufw >/dev/null && ufw status | grep -q 'Status: active'; then
	ufw allow 80/tcp >/dev/null
	ufw allow 443/tcp >/dev/null
fi
BUSY=$(ss -ltnpH '( sport = :80 or sport = :443 )' 2>/dev/null | grep -v '"caddy"' || true)
WEB=caddy
if [ -n "$BUSY" ] && ! printf '%s\n' "$BUSY" | grep -qv '"nginx"'; then
	# nginx already serves this machine's sites. A site of it on the shop's domain gets the shop
	# under a path; otherwise the shop becomes one more site, its certificate from Let's Encrypt
	# through certbot (which renews it by itself).
	WEB=nginx
	echo "На сервере уже работает nginx: подключаю магазин через него."
	install -d -m 700 /var/backups/basedefense-shop
	DOMAIN_RE=$(printf '%s' "$DOMAIN" | sed 's/[.]/\\./g')
	NAMED="^[[:space:]]*server_name[[:space:]]([^;]*[[:space:]])?$DOMAIN_RE([[:space:];]|\$)"
	# The files nginx loads, and which of them name the domain: the site's own, or the shop's.
	LOADED=$(nginx -T 2>/dev/null | sed -n 's/^# configuration file \(.*\):$/\1/p' | sort -u)
	SITE_FILES=
	OWN_FILE=
	for f in $LOADED; do
		grep -qE "$NAMED" "$f" 2>/dev/null || continue
		case "$f" in */basedefense-shop*) OWN_FILE=$f ;; *) SITE_FILES="$SITE_FILES $f" ;; esac
	done
	SITE_FILES=${SITE_FILES# }
	location_block() {
		cat <<LOC
location ^~ $1/ {
    proxy_pass http://127.0.0.1:$PORT/;
    proxy_set_header Host \$host;
    proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto \$scheme;
    client_max_body_size 16k;
}
LOC
	}
	EDITED=
	NEED_CERT=0
	if [ -n "$SITE_FILES" ]; then
		# The domain is a site already: the shop goes under a path of it, the site untouched.
		[ -n "$PREFIX" ] || PREFIX=/basedefense
		echo "$DOMAIN уже есть в nginx ($SITE_FILES): магазин будет на https://$DOMAIN$PREFIX"
		SNIPPET=/etc/nginx/snippets/basedefense-shop.conf
		install -d -m 755 /etc/nginx/snippets
		{
			echo "# The Base Defense shop under $PREFIX, put here by install.sh and included by the $DOMAIN site."
			location_block "$PREFIX"
		} >"$SNIPPET"
		for f in $SITE_FILES; do
			grep -qF "include $SNIPPET;" "$f" && continue
			cp -p "$f" "/var/backups/basedefense-shop/nginx-$(basename "$f").$(date +%s).bak"
			EDITED="$EDITED $f"
			tmp=$(mktemp)
			D="$DOMAIN_RE" INC="include $SNIPPET;" awk '
				{ print }
				$0 ~ ("^[ \t]*server_name[ \t]([^;]*[ \t])?" ENVIRON["D"] "([ \t;]|$)") {
					match($0, /^[ \t]*/)
					print substr($0, 1, RLENGTH) ENVIRON["INC"]
				}' "$f" >"$tmp"
			cat "$tmp" >"$f"
			rm -f "$tmp"
		done
		# Its certificate is the site's; a site without one gets one.
		grep -qE 'listen[^;]*443|ssl_certificate' $SITE_FILES || NEED_CERT=1
	elif [ -z "$OWN_FILE" ]; then
		OWN_FILE=/etc/nginx/conf.d/basedefense-shop-$DOMAIN.conf
		{
			echo "# The Base Defense shop, put here by install.sh; certbot adds HTTPS below."
			echo "server {"
			echo "    listen 80;"
			echo "    server_name $DOMAIN;"
			location_block "$PREFIX" | sed 's/^/    /'
			echo "}"
		} >"$OWN_FILE"
		EDITED=$OWN_FILE
	fi
	if ! nginx -t >/dev/null 2>&1; then
		nginx -t || true
		for f in $EDITED; do
			b=$(ls -1t /var/backups/basedefense-shop/nginx-"$(basename "$f")".*.bak 2>/dev/null | head -n 1 || true)
			if [ -n "$b" ]; then cat "$b" >"$f"; else rm -f "$f"; fi
		done
		die "nginx не принял настройки магазина (см. выше), всё возвращено как было."
	fi
	systemctl reload nginx
	curl -fsS --max-time 5 -H "Host: $DOMAIN" "http://127.0.0.1$PREFIX/health" >/dev/null 2>&1 ||
		curl -fsSk --max-time 5 --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN$PREFIX/health" >/dev/null 2>&1 ||
		echo "nginx пока не отдаёт магазин по адресу $DOMAIN$PREFIX/health."
	DNS_IP=$(getent ahostsv4 "$DOMAIN" | awk 'NR == 1 { print $1 }' || true)
	if [ -n "$MY_IP" ] && [ "$DNS_IP" != "$MY_IP" ]; then
		echo "Внимание: $DOMAIN указывает на ${DNS_IP:-ничто}, а этот сервер — $MY_IP. В DNS домена нужна A-запись на $MY_IP."
	fi
	if [ -z "$SITE_FILES" ] || [ "$NEED_CERT" = 1 ]; then
		if [ ! -d "/etc/letsencrypt/live/$DOMAIN" ] || [ "$NEED_CERT" = 1 ]; then
			apt-get install -y -qq certbot python3-certbot-nginx >/dev/null
			certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos --register-unsafely-without-email --redirect ||
				echo "certbot не получил сертификат (см. выше). Проверьте, что домен смотрит на этот сервер."
		fi
	fi
elif [ -n "$BUSY" ]; then
	WEB=manual
	echo "Порты 80/443 уже заняты другим веб-сервером, Caddy не ставлю:"
	echo "$BUSY"
	echo "Подключите магазин через него, пример для nginx: $HERE/nginx.conf"
else
	[ -z "$PREFIX" ] || die "Путь в адресе ($PREFIX) нужен только для сайта на nginx; укажите просто домен."

	if ! command -v caddy >/dev/null; then
		if ! apt-get install -y -qq caddy >/dev/null 2>&1; then
			curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/gpg.key |
				gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
			curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt \
				>/etc/apt/sources.list.d/caddy-stable.list
			apt-get update -qq
			apt-get install -y -qq caddy >/dev/null
		fi
	fi
	CF=/etc/caddy/Caddyfile
	BLOCK=$(sed "s/shop\.example\.ru/$DOMAIN/" "$HERE/Caddyfile")
	if [ ! -s "$CF" ] || grep -q '/usr/share/caddy' "$CF"; then
		printf '%s\n' "$BLOCK" >"$CF"
	elif ! grep -qF "$DOMAIN {" "$CF"; then
		printf '\n%s\n' "$BLOCK" >>"$CF"
	fi
	caddy validate --config "$CF" --adapter caddyfile >/dev/null 2>&1 || die "Caddy не принял $CF: caddy validate --config $CF"
	systemctl enable caddy >/dev/null 2>&1
	systemctl reload-or-restart caddy
fi

BASE="https://$DOMAIN$PREFIX"
if [ "$(get_var PUBLIC_URL)" != "$BASE" ]; then
	set_var PUBLIC_URL "$BASE"
	[ "$CONFIGURED" = 1 ] && systemctl restart basedefense-shop
fi

# ---- Done ----

OK=0
if [ "$WEB" != manual ]; then
	echo "Жду сертификат..."
	for _ in $(seq 1 30); do
		curl -fsS --max-time 5 "$BASE/health" >/dev/null 2>&1 && OK=1 && break
		sleep 2
	done
fi

say "Готово"
if [ "$CONFIGURED" = 0 ]; then
	echo "Не заданы ID магазина и токен Pally. Задать их: sudo bash $0 --pally"
elif [ "$TOKEN_BAD" = 1 ]; then
	echo "$CHECK"
	echo "Ввести ID магазина и токен заново: sudo bash $0 --pally"
else
	echo "$CHECK"
fi
if [ "$OK" = 1 ]; then
	echo "Магазин отвечает: $BASE/health"
elif [ "$WEB" = nginx ]; then
	echo "$BASE пока не отвечает. Проверьте вывод certbot выше и журнал: journalctl -u nginx -n 50"
elif [ "$WEB" = caddy ]; then
	echo "$BASE пока не отвечает. Проверьте, что домен смотрит на этот сервер и порты 80/443 открыты"
	echo "у хостинга. Журнал Caddy: journalctl -u caddy -n 50"
fi
cat <<EOF

В кабинете Pally, в настройках магазина, укажите:
  Result URL:   $BASE/pally/result   (метод POST)
  Success URL:  $BASE/return?result=success
  Fail URL:     $BASE/return?result=fail

Адрес магазина для сборки игры (SHOP_SERVER): $BASE

Покупки игроков: $DATA/db.json (копия каждый день в /var/backups/basedefense-shop)
Журнал сервера:  journalctl -u basedefense-shop -f
Заказы и игроки: sudo basedefense-shop orders | player КОД | grant КОД ТОВАР | check
Обновить:        git pull и снова sudo bash $0
EOF
