#!/usr/bin/env bash
# Installs or updates the Base Defense shop server on Ubuntu or Debian:
#
#   sudo bash install.sh shop.example.ru
#
# Without a domain it uses <ip>.sslip.io, a name that always points at this server's address.
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

DOMAIN=${1:-}
DOMAIN=${DOMAIN#https://}
DOMAIN=${DOMAIN%%/*}
if [ -z "$DOMAIN" ]; then
	DOMAIN=$(get_var PUBLIC_URL | sed -n 's#^https://\([^/]*\).*#\1#p')
	[ "$DOMAIN" = shop.example.ru ] && DOMAIN=
fi
if [ -z "$DOMAIN" ]; then
	IP=$(curl -4 -fsS --max-time 10 https://api.ipify.org || true)
	[ -n "$IP" ] || die "Не удалось узнать IP сервера. Укажите домен: sudo bash $0 shop.ваш-домен.ru"
	DOMAIN="${IP//./-}.sslip.io"
fi
case "$DOMAIN" in *[!a-zA-Z0-9.-]* | "") die "Странный домен: $DOMAIN" ;; esac
set_var PUBLIC_URL "https://$DOMAIN"

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
	"$NODE" "$APP/src/check.mjs" "$ENV_FILE" || TOKEN_BAD=1
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
	# nginx already serves this machine's sites: the shop becomes one more site of it, with its
	# certificate from Let's Encrypt through certbot (which renews it by itself).
	WEB=nginx
	echo "На сервере уже работает nginx: подключаю магазин через него."
	NC=/etc/nginx/conf.d/basedefense-shop.conf
	if [ ! -f "$NC" ]; then
		{
			echo "# The Base Defense shop, put here by install.sh; certbot adds HTTPS below."
			sed -e "s/shop\.example\.ru/$DOMAIN/" -e '/^#/d' "$HERE/nginx.conf"
		} >"$NC"
		if ! nginx -t >/dev/null 2>&1; then
			rm -f "$NC"
			nginx -t || true
			die "nginx не принял настройки магазина (см. выше)."
		fi
		systemctl reload nginx
	fi
	curl -fsS --max-time 5 -H "Host: $DOMAIN" http://127.0.0.1/health >/dev/null 2>&1 ||
		echo "nginx не отдаёт магазин по http://$DOMAIN: проверьте, что он читает /etc/nginx/conf.d/*.conf."
	if [ ! -d "/etc/letsencrypt/live/$DOMAIN" ]; then
		apt-get install -y -qq certbot python3-certbot-nginx >/dev/null
		certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos --register-unsafely-without-email --redirect ||
			echo "certbot не получил сертификат (см. выше). Если дело в лимитах Let's Encrypt для sslip.io, возьмите свой домен: sudo bash $0 shop.ваш-домен.ru"
	fi
elif [ -n "$BUSY" ]; then
	WEB=manual
	echo "Порты 80/443 уже заняты другим веб-сервером, Caddy не ставлю:"
	echo "$BUSY"
	echo "Подключите магазин через него, пример для nginx: $HERE/nginx.conf"
else
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

# ---- Done ----

OK=0
if [ "$CONFIGURED" = 1 ] && [ "$WEB" != manual ]; then
	echo "Жду сертификат..."
	for _ in $(seq 1 30); do
		curl -fsS --max-time 5 "https://$DOMAIN/health" >/dev/null 2>&1 && OK=1 && break
		sleep 2
	done
fi

say "Готово"
if [ "$CONFIGURED" = 0 ]; then
	echo "Не заданы PALLY_SHOP_ID и PALLY_TOKEN. Впишите их в $ENV_FILE и выполните:"
	echo "  systemctl restart basedefense-shop"
elif [ "$TOKEN_BAD" = 1 ]; then
	echo "Pally не принял настройки (см. выше). Исправьте $ENV_FILE и выполните:"
	echo "  systemctl restart basedefense-shop && basedefense-shop check"
elif [ "$OK" = 1 ]; then
	echo "Магазин отвечает: https://$DOMAIN/health"
elif [ "$WEB" = nginx ]; then
	echo "https://$DOMAIN пока не отвечает. Проверьте вывод certbot выше и журнал: journalctl -u nginx -n 50"
elif [ "$WEB" = caddy ]; then
	echo "https://$DOMAIN пока не отвечает. Проверьте, что домен смотрит на этот сервер и порты 80/443 открыты"
	echo "у хостинга. Журнал Caddy: journalctl -u caddy -n 50"
fi
cat <<EOF

В кабинете Pally, в настройках магазина, укажите:
  Result URL:   https://$DOMAIN/pally/result   (метод POST)
  Success URL:  https://$DOMAIN/return?result=success
  Fail URL:     https://$DOMAIN/return?result=fail

Адрес магазина для сборки игры (SHOP_SERVER): https://$DOMAIN

Покупки игроков: $DATA/db.json (копия каждый день в /var/backups/basedefense-shop)
Журнал сервера:  journalctl -u basedefense-shop -f
Заказы и игроки: sudo basedefense-shop orders | player КОД | grant КОД ТОВАР | check
Обновить:        git pull и снова sudo bash $0
EOF
