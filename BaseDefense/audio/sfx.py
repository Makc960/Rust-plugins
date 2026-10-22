"""The game's sound effects. Each is mixed from layers: sounds of the GeneralUser GS SoundFont
(its gunshot, explosion, siren, jet; drums, brass and bells played as short phrases),
Kenney's recordings, and shaped noise, then given a little room and a level.
Busy sounds come in a few takes, so a long fight does not repeat itself."""
import subprocess

import numpy as np
from scipy import signal

from score import DRUMS, Song

RATE = 44100

# Programs (from 0) and banks of the SoundFont's effects and instruments.
GUNSHOT = (127, 0)
MACHINE_GUN = (127, 1)
EXPLOSION = (127, 3)
BURST = (125, 9)
SIREN = (125, 5)
JET = (125, 7)
THUNDER = (122, 2)
PUNCH = (126, 3)
TRUMPET, TROMBONE, TUBA, HORNS, BRASS = 56, 57, 58, 60, 61
STRINGS_TREM, TIMPANI, GLOCK, CELESTA, HARP, ICE_RAIN, CHOIR = 44, 47, 9, 8, 46, 96, 52
ORCHESTRA_KIT = 48
BASS_DRUM, SNARE, CYMBAL = 36, 38, 57


# --- signal helpers ---------------------------------------------------------------------

def seconds(x):
    return len(x) / RATE


def silence(sec):
    return np.zeros(int(sec * RATE), dtype=np.float64)


def filt(x, kind, freq, order=4):
    sos = signal.butter(order, freq, btype=kind, fs=RATE, output='sos')
    return signal.sosfilt(sos, x)


def hp(x, f, order=2):
    return filt(x, 'highpass', f, order)


def lp(x, f, order=2):
    return filt(x, 'lowpass', f, order)


def bp(x, lo, hi, order=2):
    return filt(x, 'bandpass', [lo, hi], order)


def env(n, attack, decay_per_s, hold=0.0):
    """Rises over [attack] seconds, holds, then falls by e every 1/[decay_per_s] seconds."""
    t = np.arange(n) / RATE
    a = np.clip(t / max(attack, 1e-4), 0, 1)
    d = np.exp(-np.maximum(0, t - attack - hold) * decay_per_s)
    return a * d


def noise(sec, rng, color='white'):
    w = rng.standard_normal(int(sec * RATE))
    if color == 'pink':
        w = lp(w, 2500, 1) + 0.3 * w
    elif color == 'brown':
        w = np.cumsum(w)
        w = hp(w, 30, 1)
    return w / (np.abs(w).max() + 1e-9)


def pitch(x, ratio):
    """Plays [x] [ratio] times faster: higher and shorter."""
    idx = np.arange(0, len(x) - 1, ratio)
    return np.interp(idx, np.arange(len(x)), x)


def glide(x, start, end):
    """Plays [x] at a speed sliding from [start] to [end], as a passing engine sounds."""
    speeds = np.linspace(start, end, len(x))
    pos = np.cumsum(speeds)
    pos = pos[pos < len(x) - 1]
    return np.interp(pos, np.arange(len(x)), x)


def room(x, length, mix, damp=4000, seed=7):
    """A small room: [x] with an exponentially fading noise echo of [length] seconds."""
    rng = np.random.default_rng(seed)
    ir = rng.standard_normal(int(length * RATE)) * np.exp(-np.arange(int(length * RATE)) / RATE * 6.9 / length)
    ir = lp(ir, damp, 1)
    ir[:int(0.004 * RATE)] = 0
    wet = signal.fftconvolve(x, ir)[:len(x) + len(ir)]
    wet /= np.abs(wet).max() + 1e-9
    dry = np.concatenate([x, np.zeros(len(wet) - len(x))])
    return dry + wet * mix * np.abs(x).max()


def mix(*layers):
    """Adds (sound, start in seconds, gain) layers together."""
    end = max(int(at * RATE) + len(x) for (x, at, g) in layers)
    out = np.zeros(end)
    for (x, at, g) in layers:
        i = int(at * RATE)
        out[i:i + len(x)] += x * g
    return out


def cut(x, sec, fade=0.03):
    """Keeps the first [sec] seconds, fading out over the last [fade]."""
    x = x[:int(sec * RATE)].copy()
    f = min(len(x), int(fade * RATE))
    x[len(x) - f:] *= np.linspace(1, 0, f) ** 2
    return x


def finish(x, peak=0.89, floor_db=-54):
    """Trims the silence after the sound, softens both ends and sets the level by the peak."""
    x = np.asarray(x, dtype=np.float64)
    a = np.abs(x)
    smooth = np.convolve(a, np.ones(256) / 256, 'same')
    alive = np.nonzero(smooth > a.max() * 10 ** (floor_db / 20))[0]
    end = min(len(x), (alive[-1] if len(alive) else len(x)) + int(0.02 * RATE))
    start = alive[0] if len(alive) else 0
    start = max(0, start - int(0.002 * RATE))
    x = x[start:end].copy()
    fin = min(len(x) // 4, int(0.002 * RATE))
    x[:fin] *= np.linspace(0, 1, fin)
    fout = min(len(x) // 3, int(0.03 * RATE))
    x[len(x) - fout:] *= np.linspace(1, 0, fout)
    return x * (peak / (np.abs(x).max() + 1e-9))


def crackle(sec, rng, rate=60, length=0.004):
    """Sparks: short clicks at random moments."""
    out = np.zeros(int(sec * RATE))
    k = int(sec * rate)
    for t in rng.uniform(0, sec, k):
        i = int(t * RATE)
        n = int(length * RATE * rng.uniform(0.5, 1.5))
        burst = rng.standard_normal(n) * np.exp(-np.arange(n) / (n / 4)) * rng.uniform(0.3, 1)
        out[i:i + n] += burst[:len(out) - i]
    return hp(out, 2500, 2)


# --- the sources ----------------------------------------------------------------------

class Sources:
    """Renders every MIDI phrase the effects need in one go, and reads Kenney's files."""

    def __init__(self, bank, kenney, work, render, read):
        self.bank = bank
        self.kenney = kenney
        self.work = work
        self.render = render
        self.read = read
        self.songs = {}
        self.clips = {}

    def phrase(self, name, bpm=120, beats=4):
        """A new short song to write a phrase in; it is rendered by [render_all]."""
        s = Song(bpm, max(1, int(np.ceil(beats / 4))), seed=5)
        self.songs[name] = s
        return s

    def hit(self, name, program_bank, note=60, vel=110, length=3.0):
        """One note of an effect patch of the SoundFont."""
        s = self.phrase(name)
        program, bank = program_bank
        s.part(0, program, bank=bank, volume=127, reverb=0).note(0, length, note, vel)

    def drum(self, name, kit, note, vel=110, length=2.0):
        s = self.phrase(name)
        s.part(DRUMS, kit, volume=127, reverb=0).note(0, length, note, vel)

    def render_all(self):
        jobs = []
        for name, song in self.songs.items():
            mid = self.work / f'sfx_{name}.mid'
            song.save(mid, humanize=False)
            jobs.append((mid, self.work / f'sfx_{name}.wav'))
        self.render(self.bank, jobs, self.work)
        for name in self.songs:
            self.clips[name] = self.read(self.work / f'sfx_{name}.wav').mean(axis=1).astype(np.float64)

    def __getitem__(self, name):
        return self.clips[name]

    def k(self, path):
        """A Kenney recording, as mono at our rate."""
        raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(self.kenney / path), '-f', 'f32le', '-ac', '1', '-ar', str(RATE), '-'],
                             capture_output=True, check=True).stdout
        return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


IMPACT = 'kenney_impactsounds/Audio/'
UI = 'kenney_interfacesounds/Audio/'
RPG = 'kenney_rpgaudio/Audio/'
CASINO = 'kenney_casinoaudio/Audio/'
DIGITAL = 'kenney_digitalaudio/Audio/'


def prepare(src):
    """Asks for every SoundFont sound the recipes below use."""
    for name, note in (('gun_hi', 64), ('gun', 60), ('gun_lo', 55), ('gun_deep', 50)):
        src.hit(name, GUNSHOT, note, 100, 1.0)
    src.hit('mg', MACHINE_GUN, 60, 100, 0.5)
    for name, note in (('boom_hi', 64), ('boom', 60), ('boom_lo', 55)):
        src.hit(name, EXPLOSION, note, 110, 2.5)
    src.hit('burst', BURST, 60, 100, 1.0)
    src.hit('siren', SIREN, 60, 110, 2.6)
    src.hit('jet', JET, 60, 110, 3.0)
    src.hit('thunder', THUNDER, 60, 100, 2.0)
    src.hit('punch', PUNCH, 60, 110, 0.3)
    src.drum('kick', ORCHESTRA_KIT, BASS_DRUM, 120, 1.0)
    src.drum('cymbal', ORCHESTRA_KIT, CYMBAL, 100, 2.0)

    # The wave's bugle call over a rising snare roll.
    s = src.phrase('wave', bpm=120, beats=6)
    s.part(0, TRUMPET, volume=120, reverb=70).line(0, 'G4:.33 C5:.33 E5:.33 G5:1.5', 110, legato=0.92)
    s.part(1, HORNS, volume=100, reverb=70).line(0, 'G3:.33 C4:.33 E4:.33 G4:1.5', 96, legato=0.92)
    d = s.part(DRUMS, ORCHESTRA_KIT, volume=110, reverb=50)
    for i in range(16):
        d.note(i * 0.125, 0.1, SNARE, 40 + i * 5)
    d.note(2, 1, BASS_DRUM, 118).note(2, 2, CYMBAL, 90)

    # Victory: a fanfare in D major, the brass in chords, timpani and a cymbal.
    s = src.phrase('win', bpm=120, beats=8)
    s.part(0, TRUMPET, volume=120, reverb=70).line(0, 'A4:.25 A4:.25 A4:.25 D5:.75 F#5:.5 A5:3', 112, legato=0.9)
    s.part(1, HORNS, volume=110, reverb=70).line(0, 'F#4:.25 F#4:.25 F#4:.25 A4:.75 D5:.5 F#5:3', 100, legato=0.9)
    s.part(2, TROMBONE, volume=106, reverb=70).notes_at(1.5, 3, [50, 54, 57], 100)
    s.part(3, TUBA, volume=100, reverb=60).note(1.5, 3, 38, 100)
    s.part(4, TIMPANI, volume=110, reverb=60).note(0.75, 0.5, 45, 100).note(1.5, 2, 38, 120)
    g = s.part(5, GLOCK, volume=90, reverb=70)
    for k, p in enumerate((74, 78, 81, 86)):
        g.note(1.5 + k * 0.125, 1.5, p, 90)
    s.part(DRUMS, ORCHESTRA_KIT, volume=110, reverb=60).note(1.5, 3, CYMBAL, 110).note(1.5, 1, BASS_DRUM, 120)

    # Defeat: low brass falling in D minor over a soft timpani roll.
    s = src.phrase('lose', bpm=72, beats=6)
    s.part(0, TROMBONE, volume=118, reverb=70).line(0, 'A3:.75 G3:.75 F3:.75 D3:3', 100, legato=1.0)
    s.part(1, HORNS, volume=104, reverb=70).line(0, 'F4:.75 E4:.75 D4:.75 A3:3', 90, legato=1.0)
    s.part(2, TUBA, volume=104, reverb=60).note(2.25, 3, 38, 96)
    t = s.part(3, TIMPANI, volume=104, reverb=60)
    for i in range(18):
        t.note(2.25 + i * 0.125, 0.12, 38, 70 - i * 3)
    s.part(4, STRINGS_TREM, volume=80, reverb=70).notes_at(2.25, 3, [62, 65, 69], 70)

    # An achievement: a brass "ta-da" and bells.
    s = src.phrase('achievement', bpm=120, beats=4)
    s.part(0, TRUMPET, volume=120, reverb=70).line(0, 'G4:.25 C5:1.75', 110, legato=0.95)
    s.part(1, BRASS, volume=104, reverb=70).notes_at(0.25, 1.75, [60, 64, 67], 96)
    g = s.part(2, GLOCK, volume=100, reverb=70)
    for k, p in enumerate((84, 88, 91, 96)):
        g.note(0.25 + k * 0.1, 1.2, p, 96)

    # An upgrade: a harp and bell run upwards.
    s = src.phrase('upgrade', bpm=120, beats=3)
    h = s.part(0, HARP, volume=120, reverb=60)
    b = s.part(1, GLOCK, volume=96, reverb=60)
    for k, p in enumerate((60, 64, 67, 72, 76, 79)):
        h.note(k * 0.09, 1.0, p, 100)
    b.note(0.45, 1.0, 84, 90).note(0.45, 1.0, 91, 80)

    # Freezing: celesta and bells falling, with the "ice rain" pad.
    s = src.phrase('freeze', bpm=120, beats=4)
    c = s.part(0, CELESTA, volume=110, reverb=80)
    for k, p in enumerate((96, 93, 91, 88, 86, 84, 81, 79)):
        c.note(k * 0.07, 1.2, p, 96 - k * 4)
    s.part(1, ICE_RAIN, volume=100, reverb=80).notes_at(0, 2.2, [79, 84, 88], 90)
    s.part(2, GLOCK, volume=80, reverb=80).note(0, 1.5, 96, 80)

    # Repaired: two bright bell notes.
    s = src.phrase('repaired', bpm=120, beats=2)
    s.part(0, GLOCK, volume=100, reverb=60).note(0, 0.8, 79, 96).note(0.2, 1.0, 84, 100)


def recipes(src):
    """name -> list of takes. Every effect the game plays is here."""
    rng = np.random.default_rng(2024)
    out = {}

    def rifle(base, hi_ratio):
        crack = hp(src[base], 180)
        body = cut(crack, 0.22, 0.12) * env(int(0.22 * RATE), 0.0005, 14)
        snap = hp(pitch(src['gun_hi'], hi_ratio), 2500)[:int(0.03 * RATE)]
        return finish(room(mix((body, 0, 1.0), (snap, 0, 0.35)), 0.25, 0.25, 3500))

    out['shoot'] = [rifle('gun', 1.0), rifle('gun_hi', 1.1), rifle('gun', 1.25)]

    def shotgun(base):
        blast = cut(lp(src[base], 6000), 0.5, 0.2)
        thump = cut(lp(src['kick'], 300), 0.3)
        pellets = bp(noise(0.25, rng), 1500, 6000) * env(int(0.25 * RATE), 0.001, 25)
        pump = cut(src.k(RPG + 'metalLatch.ogg'), 0.26)
        return finish(room(mix((blast, 0, 1.0), (thump, 0, 0.8), (pellets, 0.002, 0.25), (pump, 0.32, 0.22)), 0.45, 0.3, 3000))

    out['shotgun'] = [shotgun('gun_lo'), shotgun('gun_deep')]

    def sniper(base, take):
        crack = cut(src[base], 0.6, 0.3)
        snap = hp(src['gun_hi'], 3000)[:int(0.02 * RATE)]
        thump = cut(lp(src['kick'], 200), 0.4)
        dry = mix((crack, 0, 1.0), (snap, 0, 0.6), (thump, 0, 0.7))
        return finish(room(dry, 1.3, 0.55, 2500, seed=11 + take))

    out['sniper'] = [sniper('gun_lo', 0), sniper('gun_deep', 1)]

    def flame(seed):
        r = np.random.default_rng(seed)
        roar = lp(noise(0.75, r, 'brown'), 900) * 0.8 + bp(noise(0.75, r), 300, 2500) * 0.5
        flutter = 1 + 0.35 * np.sin(2 * np.pi * r.uniform(13, 19) * np.arange(len(roar)) / RATE + r.uniform(0, 6))
        body = roar * flutter * env(len(roar), 0.06, 5, 0.25)
        sparks = crackle(0.75, r, 50) * env(int(0.75 * RATE), 0.05, 4, 0.2)
        burst = cut(lp(src['burst'], 2500), 0.4)
        return finish(room(mix((body, 0, 1.0), (sparks, 0, 0.5), (burst, 0, 0.35)), 0.3, 0.2, 3000))

    out['flame'] = [flame(1), flame(2)]

    def whoosh(sec, f0, f1, seed, q=0.5):
        r = np.random.default_rng(seed)
        nz = noise(sec, r)
        n = len(nz)
        out_ = np.zeros(n)
        block = 512
        for i in range(0, n, block):
            f = f0 * (f1 / f0) ** (i / n)
            seg = nz[max(0, i - 2048):i + block]
            y = bp(seg, f * (1 - q / 2), f * (1 + q / 2))[-min(block, n - i):]
            out_[i:i + len(y)] = y
        return out_ / (np.abs(out_).max() + 1e-9)

    def rocket(seed):
        launch = cut(lp(src['kick'], 400), 0.35) * 1.0
        pop = cut(hp(src['punch'], 150), 0.2)
        trail = whoosh(0.9, 250, 1400, seed) * env(int(0.9 * RATE), 0.04, 3.2, 0.1)
        hiss = hp(noise(0.9, np.random.default_rng(seed + 5)), 4000) * env(int(0.9 * RATE), 0.02, 5)
        return finish(room(mix((launch, 0, 0.9), (pop, 0, 0.5), (trail, 0.01, 0.9), (hiss, 0.01, 0.15)), 0.35, 0.2))

    out['rocket'] = [rocket(3), rocket(4)]

    def missile(seed):
        pop = cut(hp(src['punch'], 400), 0.15)
        trail = whoosh(0.6, 900, 3600, seed, q=0.4) * env(int(0.6 * RATE), 0.02, 4.5, 0.05)
        hiss = hp(noise(0.6, np.random.default_rng(seed + 9)), 5000) * env(int(0.6 * RATE), 0.01, 6)
        return finish(room(mix((pop, 0, 0.6), (trail, 0.005, 1.0), (hiss, 0, 0.25)), 0.3, 0.18))

    out['missile'] = [missile(5), missile(6)]

    def zap(seed):
        r = np.random.default_rng(seed)
        sec = 0.38
        t = np.arange(int(sec * RATE)) / RATE
        # A buzzing arc: a jittery sawtooth, crackling sparks and a short digital zap.
        f = 95 * (1 + 0.04 * r.standard_normal(len(t)).cumsum() / np.sqrt(np.arange(1, len(t) + 1)))
        phase = np.cumsum(f) / RATE
        saw = 2 * (phase % 1) - 1
        arc = bp(saw, 600, 6000) * env(len(t), 0.002, 9)
        gate = (r.uniform(0, 1, len(t) // 300 + 1) > 0.35).repeat(300)[:len(t)]
        sparks = crackle(sec, r, 140, 0.003) * env(len(t), 0.001, 7) * gate
        digital = cut(hp(src.k(DIGITAL + ('zap1.ogg' if seed % 2 else 'zap2.ogg')), 800), 0.3, 0.15)
        return finish(mix((arc, 0, 0.8), (sparks, 0, 1.0), (digital, 0, 0.35)))

    out['zap'] = [zap(7), zap(8)]

    def boom(base, take):
        blast = cut(src[base], 1.6, 0.6)
        thump = cut(lp(src['kick'], 160), 0.8)
        debris = cut(hp(src.k(IMPACT + f'impactMining_00{take}.ogg'), 1500), 0.9, 0.4)
        rumble = lp(noise(1.4, np.random.default_rng(20 + take), 'brown'), 120) * env(int(1.4 * RATE), 0.01, 3)
        return finish(room(mix((blast, 0, 1.0), (thump, 0, 0.9), (debris, 0.04, 0.25), (rumble, 0, 0.5)), 0.9, 0.3, 2500, seed=30 + take))

    out['boom'] = [boom('boom', 0), boom('boom_lo', 1), boom('boom_hi', 2)]

    def mine(take):
        blast = cut(pitch(src['boom'], 1.25), 0.9, 0.4)
        thump = cut(lp(src['kick'], 200), 0.6)
        dirt = cut(src.k(IMPACT + f'impactSoft_heavy_00{take}.ogg'), 0.5)
        clank = cut(src.k(IMPACT + f'impactMetal_light_00{take}.ogg'), 0.3)
        return finish(room(mix((blast, 0, 1.0), (thump, 0, 1.0), (dirt, 0.02, 0.5), (clank, 0, 0.2)), 0.6, 0.25))

    out['mine'] = [mine(0), mine(1)]

    plate = src.k(IMPACT + 'impactPlate_heavy_000.ogg')
    metal = src.k(IMPACT + 'impactMetal_heavy_001.ogg')
    out['base_hit'] = [finish(room(mix((plate, 0, 1.0), (metal, 0.01, 0.7), (cut(lp(src['boom_lo'], 800), 0.6), 0, 0.6),
                                       (cut(lp(src['kick'], 150), 0.5), 0, 0.8)), 0.7, 0.35))]

    out['coin'] = [finish(cut(src.k(CASINO + f'chipsCollide{k}.ogg'), 0.25, 0.08), peak=0.7) for k in (1, 3)]
    out['sell'] = [finish(mix((cut(src.k(RPG + 'handleCoins2.ogg'), 0.34), 0, 1.0), (cut(src.k(CASINO + 'chipsStack2.ogg'), 0.17), 0.12, 0.7)))]

    def build(take):
        bag = src.k(RPG + 'dropLeather.ogg')
        thud = src.k(IMPACT + f'impactSoft_heavy_00{take + 2}.ogg')
        click = cut(src.k(RPG + 'metalClick.ogg'), 0.2, 0.08)
        return finish(room(mix((thud, 0, 1.0), (bag, 0.03, 0.8), (click, 0.2, 0.35)), 0.3, 0.15))

    out['build'] = [build(0), build(1)]

    latch = src.k(RPG + 'metalLatch.ogg')
    out['upgrade'] = [finish(mix((cut(latch, 0.26), 0, 0.6), (src['upgrade'], 0.05, 1.0)))]

    out['wave'] = [finish(src['wave'])]
    out['win'] = [finish(src['win'])]
    out['lose'] = [finish(src['lose'])]
    out['achievement'] = [finish(src['achievement'])]

    out['click'] = [finish(mix((src.k(UI + 'click_002.ogg'), 0, 1.0), (src.k(UI + 'select_001.ogg'), 0, 0.4)), peak=0.6)]
    out['error'] = [finish(src.k(UI + 'error_004.ogg'), peak=0.7)]

    jet = src['jet']
    fly = glide(jet[:int(2.8 * RATE)], 1.12, 0.86)
    swell = np.sin(np.linspace(0, np.pi, len(fly))) ** 1.5
    out['plane'] = [finish(lp(fly * swell, 7000))]

    freeze_noise = hp(noise(1.2, np.random.default_rng(40)), 6000) * env(int(1.2 * RATE), 0.01, 3)
    crystal = src.k(UI + 'glass_002.ogg')
    out['freeze'] = [finish(mix((src['freeze'], 0, 1.0), (freeze_noise, 0, 0.25), (crystal, 0, 0.5), (crystal, 0.15, 0.3)))]

    clink = src.k(IMPACT + 'impactMetal_light_002.ogg')
    ratchet = mix(*[(cut(src.k(UI + 'click_004.ogg'), 0.02, 0.005), 0.25 + k * 0.035, 0.6) for k in range(7)])
    out['repair'] = [finish(mix((cut(clink, 0.3), 0, 0.9), (cut(latch, 0.26), 0.12, 0.6), (ratchet, 0, 0.7), (src['repaired'], 0.5, 0.7)))]

    siren = src['siren'][:int(2.4 * RATE)]
    siren = siren * np.minimum(1, np.linspace(0, 6, len(siren))) * np.minimum(1, np.linspace(5, 0, len(siren)))
    out['siren'] = [finish(siren)]

    def jam(seed):
        r = np.random.default_rng(seed)
        sec = 0.5
        hiss = bp(noise(sec, r), 900, 5000)
        gate = (r.uniform(0, 1, int(sec * 60) + 1) > 0.3).repeat(int(RATE / 60))[:len(hiss)] * r.uniform(0.4, 1, int(sec * 60) + 1).repeat(int(RATE / 60))[:len(hiss)]
        gate = np.convolve(gate, np.ones(64) / 64, 'same')
        whine = np.sin(2 * np.pi * np.cumsum(np.full(len(hiss), 1400) + 300 * np.sin(np.arange(len(hiss)) / RATE * 2 * np.pi * 7)) / RATE) * 0.25
        glitch = src.k(UI + 'glitch_001.ogg')
        body = (hiss + whine) * gate * env(len(hiss), 0.01, 3, 0.25)
        return finish(mix((body, 0, 1.0), (glitch, 0, 0.6), (glitch, 0.21, 0.5)))

    out['jam'] = [jam(50)]

    def clear(take):
        chop = src.k(RPG + 'chop.ogg')
        wood = src.k(IMPACT + f'impactWood_heavy_00{take}.ogg')
        rubble = cut(src.k(IMPACT + f'impactMining_00{take + 2}.ogg'), 0.7, 0.3)
        return finish(room(mix((chop, 0, 0.8), (wood, 0.01, 1.0), (rubble, 0.12, 0.7)), 0.3, 0.15))

    out['clear'] = [clear(0), clear(1)]

    def rail(seed):
        r = np.random.default_rng(seed)
        sec = 0.32
        t = np.arange(int(sec * RATE)) / RATE
        f = 300 * (3000 / 300) ** (t / sec)
        whine = np.sin(2 * np.pi * np.cumsum(f) / RATE) * (0.5 + 0.5 * np.sin(2 * np.pi * 30 * t)) * np.minimum(1, t / sec * 1.5)
        crack = cut(hp(src['gun_hi'], 300), 0.4, 0.2)
        sizzle = crackle(0.5, r, 160, 0.003) * env(int(0.5 * RATE), 0.001, 6)
        tail = cut(lp(src['boom_hi'], 1200), 0.8, 0.5)
        return finish(room(mix((whine, 0, 0.35), (crack, sec, 1.0), (sizzle, sec, 0.6), (tail, sec, 0.5)), 0.6, 0.3))

    out['rail'] = [rail(60), rail(61)]
    return out


def build(bank, kenney, work, out, names, render, read, encode):
    src = Sources(bank, kenney, work, render, read)
    prepare(src)
    src.render_all()
    for old in out.glob('sfx_*.ogg'):
        if not names or old.stem[4:].rsplit('_', 1)[0] in names:
            old.unlink()
    for name, takes in recipes(src).items():
        if names and name not in names:
            continue
        for k, x in enumerate(takes):
            encode(np.stack([x, x], axis=1), out / f'sfx_{name}_{k + 1}.ogg', 3, mono=True)
        print(f'sfx_{name}: {len(takes)} x {max(seconds(x) for x in takes):.2f} s')
