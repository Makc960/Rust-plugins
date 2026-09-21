#!/usr/bin/env bash
# Проверка, что оптимизированный XSkinMenu рисует ровно тот же CUI, что версия автора.
#
# 1. берёт XSkinMenu.cs из базового коммита (авторская 1.8.6) и записывает эталон:
#    транскрипт DestroyUi/AddUi с полным JSON для 324 экранов в фиксированном состоянии;
# 2. собирает рабочую копию и сравнивает её транскрипт с эталоном побайтно.
#
# Совпадение = игрок не увидит разницы. Это замена скриншотам там, где сервера нет.
set -euo pipefail
cd "$(dirname "$0")"
BASE="${BASE_COMMIT:-078ac45}"
mkdir -p plugins
git show "$BASE:XSkinMenu.cs" > plugins/XSkinMenu.cs
GOLDEN=record GOLDEN_FILE=xskin.golden dotnet run -v q --nologo 2>&1 | grep -v "warning CS" | tail -1
cp ../../XSkinMenu.cs plugins/XSkinMenu.cs
GOLDEN=check GOLDEN_FILE=xskin.golden dotnet run -v q --nologo 2>&1 | grep -v "warning CS" | tail -3
