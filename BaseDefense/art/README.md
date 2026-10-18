# Графика

Все картинки игры лежат в одном атласе: `engine/src/main/resources/sprites/atlas.png`
и его оглавлении `atlas.txt` (имя, место в атласе и видимая часть каждой картинки;
128 пикселей атласа на клетку карты). Атлас собирает `build_atlas.py`.

## Откуда картинки

Бесплатные паки [Kenney](https://kenney.nl), лицензия
[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) (общественное достояние,
можно и в платных играх; упоминать автора не обязательно, но мы упоминаем — в настройках игры):

- **Tower Defense (top-down)** — плиты и головы башен, ракеты, деревья, камни, огонь, самолёты;
- **Top-down Shooter** — люди и роботы для врагов;
- **Top-down Tanks Redux** — земля и дороги трёх миров, броневик, взрывы, дым, мешки с песком, мины.

Векторные листы паков рендерятся в 4 раза крупнее (поэтому картинки чёткие), каждая
картинка поворачивается лицом вправо и центрируется. Для пустыни и севера скрипт
перекрашивает траву в снег, камни в песчаник, деревья в заснеженные. Части,
которых в паках нет (дробовики, снайперы, огнемёты, тесла, радар, штаб, вертолёт,
кактус, вагонетка карьера, шахта), дорисованы в том же стиле прямо в скрипте.

## Как пересобрать

Нужны Python 3 с `pillow` и `numpy`, Node.js с `playwright` (Chromium рендерит SVG).

```sh
git clone --filter=blob:none --sparse https://github.com/ETdoFresh/kenney.nl kenney
git -C kenney sparse-checkout set tower-defense-top-down topdown-shooter kenney_topdowntanksredux
npm install playwright && npx playwright install chromium
python3 build_atlas.py kenney work ../engine/src/main/resources/sprites --preview preview.png
```

`ETdoFresh/kenney.nl` — зеркало паков на GitHub (собиралось с коммита `45df48c`).
`work` — папка для промежуточных рендеров, их можно переиспользовать между запусками.
`preview.png` — все картинки на одном листе в настоящем размере, удобно проверять глазами.
