# Графика

Все картинки игры лежат в одном атласе: `engine/src/main/resources/sprites/atlas.png`
и его оглавлении `atlas.txt` (имя, место в атласе и видимая часть каждой картинки;
128 пикселей атласа на клетку карты). Атлас собирает `build_atlas.py`.

## Откуда картинки

Бесплатные паки [Kenney](https://kenney.nl), лицензия
[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) (общественное достояние,
можно и в платных играх; упоминать автора не обязательно, но мы упоминаем — в настройках игры):

- **Tower Defense (top-down)** — ракеты, деревья, камни, огонь, самолёты;
- **Top-down Shooter** — солдаты для отрядов, люди и роботы для врагов;
- **Top-down Tanks Redux** — земля и дороги трёх миров, мешки с песком для окопов, броневик, взрывы, дым, мины;
- **Game Icons** и **Game Icons Expansion** — белые иконки интерфейса (`icon.*`), игра красит их сама.

Векторные листы паков рендерятся в 4 раза крупнее (поэтому картинки чёткие), каждая
картинка поворачивается лицом вправо и центрируется. Для пустыни и севера скрипт
перекрашивает траву в снег, камни в песчаник, деревья в заснеженные. Солдат из пака
переодевается в цвет своего отряда, а оружие (гранатомёты, огнемёты, ПЗРК, тесла-винтовки,
снайперские винтовки и прочее) дорисовано в том же стиле прямо в скрипте, как и радар,
вертолёт, кактус, вагонетка снабжения и шахта. Иконки сердца, черепа, книги, календаря, бесконечности
и шевронов нарисованы скриптом в стиле Game Icons.

## Как пересобрать

Нужны Python 3 с `pillow` и `numpy`, Node.js с `playwright` (Chromium рендерит SVG).

```sh
git clone --filter=blob:none --sparse https://github.com/ETdoFresh/kenney.nl kenney
git -C kenney sparse-checkout set tower-defense-top-down topdown-shooter kenney_topdowntanksredux gameicons gameicons-expansion
npm install playwright && npx playwright install chromium
python3 build_atlas.py kenney work ../engine/src/main/resources/sprites --preview preview.png
```

`ETdoFresh/kenney.nl` — зеркало паков на GitHub (собиралось с коммита `45df48c`).
`work` — папка для промежуточных рендеров, их можно переиспользовать между запусками.
`preview.png` — все картинки на одном листе в настоящем размере, удобно проверять глазами.
