# Звуки и музыка

Всё, что звучит в игре, собирается здесь скриптами в Ogg-файлы, которые лежат в `app/src/main/assets/audio`.
Игра их только проигрывает: `AndroidPlatform` грузит эффекты в SoundPool, а темы крутит по кругу в MediaPlayer.

## Откуда звук

- **GeneralUser GS** (S. Christian Collins, лицензия GeneralUser GS License v2.0: можно без ограничений использовать
  для своей музыки, в том числе коммерчески, и в программах) — банк инструментов General MIDI. Им сыграны все три темы
  и фанфары, из него же взяты выстрел, пулемёт, взрыв, сирена и реактивный самолёт для эффектов. Банк приходит
  npm-пакетом `generaluser`, в репозиторий он не кладётся.
- **Kenney** (CC0): Impact Sounds, Interface Sounds, RPG Audio, Casino Audio, Digital Audio — удары, мешки с песком,
  монеты, щелчки интерфейса, металл, дерево, щебень.
- Остальное — шум, свисты и треск, сделанные в `sfx.py` (огнемёт, хвосты ракет, дуга теслы, рельсотрон, помехи).

## Что где

- `score.py` — маленький нотный редактор: партии нот в долях такта, запись в MIDI.
- `music.py` — три темы для оркестра с полевым барабаном: марш в ре миноре для меню (горн, тема у труб, подъём
  у скрипок, фанфара), бой в ми миноре (струнные шестнадцатыми, барабаны, тема у валторн, потом у труб)
  и босс в до миноре (рифф тромбонов, тёмный хор, литавры).
- `sfx.py` — рецепты всех эффектов игры: из каких слоёв каждый смешан, сколько у него вариантов.
- `render.mjs` — рендер MIDI в WAV банком SoundFont через `spessasynth_core`.
- `build.py` — собирает всё: темы рендерятся трижды подряд и вырезается средний проход, а его начало сводится
  с началом третьего, поэтому петля без щелчка; эффекты обрезаются по тишине и выравниваются по пику; всё
  кодируется в Ogg Vorbis через ffmpeg.

## Как пересобрать

Нужны Python 3 с `mido`, `numpy`, `scipy`, Node.js и ffmpeg с libvorbis, а также паки Kenney
(зеркало `ETdoFresh/kenney.nl`, как для графики).

```sh
git clone --filter=blob:none --sparse https://github.com/ETdoFresh/kenney.nl kenney
git -C kenney sparse-checkout set kenney_impactsounds kenney_interfacesounds kenney_rpgaudio kenney_casinoaudio kenney_digitalaudio
npm install
python3 build.py node_modules/generaluser/GeneralUser.sf2 kenney work ../app/src/main/assets/audio
```

Можно собрать часть: `music`, `sfx` или имена через пробел (`menu`, `boss`, `shoot`, `boom`…).
`AudioTest` в движке проверяет, что у каждого звука игры и у каждой темы есть файл и лишних нет.
