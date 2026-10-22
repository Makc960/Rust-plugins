"""The game's three themes, written for a small orchestra with field drums:
the menu's march, the battle and the boss fight. Each is one loop."""
from score import DRUMS, Song, chord, n

# General MIDI programs (from 0) and the orchestra kit's notes, as GeneralUser GS has them.
TRUMPET, TROMBONE, TUBA, HORNS, BRASS = 56, 57, 58, 60, 61
STRINGS_TREM, STRINGS_FAST, STRINGS_SLOW, CONTRABASS = 44, 48, 49, 43
TIMPANI, CHOIR, ORCH_HIT, GLOCK, HARP, PIZZ = 47, 52, 55, 9, 46, 45
ORCHESTRA_KIT, STANDARD_KIT, POWER_KIT = 48, 0, 16
BASS_DRUM, SNARE, SNARE2, CYMBAL, CYMBAL2 = 36, 38, 40, 57, 59
KICK, POWER_SNARE, TOM_LOW, TOM_LOW2, TOM_MID, TOM_MID2, TOM_HI, CRASH = 36, 38, 41, 43, 45, 47, 48, 49

ROOTS = {'C': 0, 'C#': 1, 'Db': 1, 'D': 2, 'D#': 3, 'Eb': 3, 'E': 4, 'F': 5, 'F#': 6, 'Gb': 6, 'G': 7, 'G#': 8,
         'Ab': 8, 'A': 9, 'A#': 10, 'Bb': 10, 'B': 11}


def root(ch, octave):
    """The root of chord [ch] ('Dm', 'Bb', 'A', 'F#') in [octave]."""
    name = ch[:2] if len(ch) > 1 and ch[1] in '#b' else ch[:1]
    return ROOTS[name] + 12 * (octave + 1)


def triad(ch, octave):
    """Root, third and fifth of [ch] from its root in [octave]."""
    r = root(ch, octave)
    minor = ch.endswith('m')
    return [r, r + (3 if minor else 4), r + 7]


def march_snare(d, bar, vel=96):
    """A field drum's march bar: accents on one and three, sixteenths leading into them."""
    b = bar * 4
    for (t, v) in ((0, 1.0), (0.75, 0.6), (1, 0.8), (1.5, 0.65), (2, 1.0), (2.75, 0.6), (3, 0.8), (3.25, 0.55), (3.5, 0.7)):
        d.note(b + t, 0.1, SNARE, vel * v)


def roll(d, beat, beats, note, start, end, step=0.125):
    """A drum roll from [start] to [end] loudness."""
    k = int(beats / step)
    for i in range(k):
        d.note(beat + i * step, step * 0.9, note, start + (end - start) * i / max(1, k - 1))


def ostinato(p, bar, ch, octave, vel, pattern=(0, 0, 12, 0, 0, 0, 7, 0), step=0.5, length=0.4):
    r = root(ch, octave)
    for i, iv in enumerate(pattern):
        p.note(bar * 4 + i * step, length, r + iv, vel * (1.0 if i % 4 == 0 else 0.8))


def menu():
    """'The base' — a heroic march in D minor: a bugle call, the trumpets' theme, the strings' lift and a fanfare back to the top."""
    s = Song(100, 24, seed=11)
    harmony = ['Dm', 'Dm', 'Bb', 'A',
               'Dm', 'Bb', 'F', 'C', 'Dm', 'Bb', 'Gm', 'A',
               'F', 'C', 'Dm', 'Bb', 'F', 'C', 'Bb', 'A',
               'Dm', 'Bb', 'Gm', 'A']
    trumpet = s.part(0, TRUMPET, volume=108, pan=74, reverb=48)
    horns = s.part(1, HORNS, volume=96, pan=48, reverb=56)
    trombones = s.part(2, TROMBONE, volume=84, pan=58, reverb=48)
    tuba = s.part(3, TUBA, volume=92, pan=64, reverb=40)
    low = s.part(4, STRINGS_FAST, volume=96, pan=44, reverb=44)
    violins = s.part(5, STRINGS_SLOW, volume=100, pan=84, reverb=60)
    timpani = s.part(6, TIMPANI, volume=104, pan=64, reverb=50)
    choir = s.part(7, CHOIR, volume=64, pan=64, reverb=70)
    drums = s.part(DRUMS, ORCHESTRA_KIT, volume=100, pan=64, reverb=44)

    # Intro: the bugle call over the low strings and the drums coming in.
    trumpet.line(0, 'A4:.5 A4:.25 A4:.25 D5:2 r:1', 96)
    trumpet.line(8, 'F4:.5 F4:.25 F4:.25 Bb4:.75 C5:.25 D5:1 C5:1', 100)
    trumpet.line(12, 'C#5:.5 C#5:.25 C#5:.25 E5:2 r:1', 104)
    for bar, notes in ((0, 'D4 F4 A4'), (2, 'Bb3 D4 F4'), (3, 'A3 C#4 E4')):
        horns.notes_at(bar * 4, 7.8 if bar == 0 else 3.9, chord(notes), 78 + bar * 4)
    horns.swell(0, 16, 70, 120)

    # The theme on the trumpets.
    trumpet.line(16, 'D5:1.5 A4:.5 D5 F5  F5 G5:.5 F5:.5 D5 Bb4  C5:1.5 F4:.5 A4 C5  E5:2 D5 C5', 108)
    trumpet.line(32, 'D5:1.5 A4:.5 D5 F5  G5 F5:.5 G5:.5 A5 Bb5  Bb5:1.5 A5:.5 G5 D5  E5:2 C#5 A4', 112)
    # The horns double it an octave down.
    horns.line(16, 'D4:1.5 A3:.5 D4 F4  F4 G4:.5 F4:.5 D4 Bb3  C4:1.5 F3:.5 A3 C4  E4:2 D4 C4', 78)
    horns.line(32, 'D4:1.5 A3:.5 D4 F4  G4 F4:.5 G4:.5 A4 Bb4  Bb4:1.5 A4:.5 G4 D4  E4:2 C#4 A3', 82)
    # The lift: violins sing, horns answer in half notes.
    violins.line(48, 'A5:2 G5 F5  E5:2 G5:2  F5:2 E5 D5  D5:3 C5  A5:2 C6 D6  E6:2 D6 C6  D6:2 Bb5 G5  A5:4', 100, legato=1.0)
    violins.swell(48, 32, 80, 127)
    horns.line(48, 'F4:2 A4:2  G4:2 E4:2  F4:2 A4:2  Bb4:2 F4:2  A4:2 C5:2  G4:2 C5:2  F4:2 D5:2  E5:2 C#5:2', 88)
    # The fanfare that leads back to the top.
    trumpet.line(80, 'D5:.75 D5:.25 F5 A5:2  Bb5:.75 A5:.25 G5 F5:2  G5:.75 F5:.25 D5 Bb4:2  A4:.75 B4:.25 C#5 E5:2', 116)
    horns.line(80, 'A4:.75 A4:.25 D5 F5:2  F5:.75 F5:.25 D5 D5:2  D5:.75 D5:.25 Bb4 G4:2  E4:.75 E4:.25 A4 C#5:2', 98)

    for bar, ch in enumerate(harmony):
        b = bar * 4
        section = 'intro' if bar < 4 else 'A' if bar < 12 else 'B' if bar < 20 else 'coda'
        # Low strings drive the march; in the lift they hold long notes.
        if section == 'B':
            low.note(b, 1.9, root(ch, 2), 84).note(b + 2, 1.9, root(ch, 2) + 12, 78)
        else:
            ostinato(low, bar, ch, 2, 88 if section != 'intro' else 70 + bar * 5)
        if section != 'intro':
            tuba.note(b, 1.8, root(ch, 2), 90).note(b + 2, 1.8, root(ch, 2) + (7 if section == 'B' else 0), 82)
        if section in ('A', 'coda'):
            for half in (0, 2):
                trombones.notes_at(b + half, 1.6, triad(ch, 3), 80 if half == 0 else 72)
        if section == 'B':
            choir.notes_at(b, 3.95, [x + 12 for x in triad(ch, 3)], 62)
        # Timpani on the root and fifth; a roll into each new section.
        t_root = root(ch, 2)
        if t_root < n('D2'):
            t_root += 12
        if bar in (3, 11, 19, 23):
            timpani.note(b, 0.9, t_root, 96)
            roll(timpani, b + 2, 2, root(harmony[(bar + 1) % 24], 2) + (12 if root(harmony[(bar + 1) % 24], 2) < n('D2') else 0), 60, 112)
        elif section != 'intro' or bar >= 1:
            timpani.note(b, 0.9, t_root, 100).note(b + 2, 0.9, t_root + 7 if t_root + 7 <= n('A3') else t_root - 5, 84)
        # Field drums: soft in the intro, the full march in the theme and the fanfare, quiet in the lift.
        if section in ('A', 'coda'):
            march_snare(drums, bar, 98)
            drums.note(b, 0.5, BASS_DRUM, 100).note(b + 2, 0.5, BASS_DRUM, 88)
        elif section == 'intro' and bar >= 2:
            march_snare(drums, bar, 64 + (bar - 2) * 14)
        elif section == 'B':
            drums.note(b, 0.5, BASS_DRUM, 84)
            if bar == 19:
                roll(drums, b, 4, SNARE, 40, 118)
    for bar in (0, 4, 12, 20):
        drums.note(bar * 4, 2, CYMBAL, 104 if bar else 92)
    drums.note(48 + 30, 2, CYMBAL2, 70)
    return s


def battle():
    """The battle: E minor at a gallop, spiccato strings and war drums, the horns' theme, then the trumpets'."""
    s = Song(132, 32, seed=21)
    harmony = ['Em', 'Em', 'C', 'D', 'Em', 'Em', 'C', 'B',
               'Em', 'C', 'G', 'D', 'Em', 'C', 'Am', 'B',
               'Am', 'Em', 'C', 'B', 'Am', 'Em', 'C', 'B',
               'Em', 'C', 'Am', 'B', 'Em', 'C', 'D', 'B']
    spiccato = s.part(0, STRINGS_FAST, volume=96, pan=80, reverb=40)
    low = s.part(1, STRINGS_FAST, volume=100, pan=44, reverb=36)
    horns = s.part(2, HORNS, volume=108, pan=52, reverb=52)
    trumpet = s.part(3, TRUMPET, volume=104, pan=76, reverb=50)
    brass = s.part(4, BRASS, volume=96, pan=64, reverb=44)
    tuba = s.part(5, TUBA, volume=86, pan=60, reverb=36)
    timpani = s.part(6, TIMPANI, volume=104, pan=64, reverb=46)
    choir = s.part(7, CHOIR, volume=66, pan=64, reverb=66)
    drums = s.part(DRUMS, POWER_KIT, volume=96, pan=64, reverb=36)

    horns.line(32, 'E4:1.5 B3:.5 E4 G4  C5:1.5 B4:.5 A4 G4  B4:1.5 A4:.5 G4 D4  F#4:3 A4  '
                   'E4:1.5 B3:.5 E4 G4  C5:1.5 D5:.5 E5 C5  A4:1.5 C5:.5 B4 A4  B4:2 D#5:2', 108)
    trumpet.line(64, 'E5 A5 C6:1.5 B5:.5  B5:2 G5 E5  G5 C6 E6:1.5 D6:.5  B5:2 F#5 D#5  '
                     'E5 A5 C6:1.5 B5:.5  B5:1.5 A5:.5 G5 E5  E5 G5 C6:2  B5:3 r', 106)
    horns.line(64, 'C4:2 E4:2  B3:2 E4:2  C4:2 G4:2  B3:2 D#4:2  C4:2 E4:2  B3:2 E4:2  C4:2 E4:2  D#4:4', 88)
    choir.swell(64, 32, 70, 120)

    for bar, ch in enumerate(harmony):
        b = bar * 4
        cycle = bar // 8
        # Sixteenths on the strings: root and fifth, the octave on the off-beats.
        r = root(ch, 3)
        third = 3 if ch.endswith('m') else 4
        for i, iv in enumerate((0, 0, 7, 0, 0, 0, third, 0, 0, 0, 7, 0, 12, 7, third, 0)):
            spiccato.note(b + i * 0.25, 0.18, r + iv, 92 if i % 4 == 0 else 70)
        ostinato(low, bar, ch, 2, 90, pattern=(0, 0, 0, 12, 0, 0, 7, 12))
        tuba.note(b, 3.8, root(ch, 2), 84 if cycle != 2 else 74)
        if cycle == 2:
            choir.notes_at(b, 3.95, [x + 12 for x in triad(ch, 3)], 64)
        # Brass stabs in the first and last cycles, under the trumpets' theme a held chord.
        if cycle in (0, 3):
            for t in (0, 0.75, 1.5, 2.5, 3):
                brass.notes_at(b + t, 0.3, triad(ch, 3), 96 if t == 0 else 84)
        elif cycle == 2:
            brass.notes_at(b, 3.9, triad(ch, 3), 60)
        # Timpani: one and three, a roll at the end of every eight bars.
        tr = root(ch, 2)
        if tr < n('D2'):
            tr += 12
        if bar % 8 == 7:
            timpani.note(b, 0.5, tr, 100)
            roll(timpani, b + 2, 2, root(harmony[(bar + 1) % 32], 2) + (12 if root(harmony[(bar + 1) % 32], 2) < n('D2') else 0), 64, 118)
        else:
            timpani.note(b, 0.5, tr, 104).note(b + 2, 0.5, tr, 86)
        # War drums: a driving tom groove, the snare on two and four, a fill every eighth bar.
        if bar % 8 == 7:
            for i in range(16):
                drums.note(b + i * 0.25, 0.2, (TOM_HI, TOM_MID2, TOM_MID, TOM_LOW2)[i // 4], 70 + i * 3)
            drums.note(b, 0.4, KICK, 104)
        else:
            for t in (0, 1.5, 2, 2.75):
                drums.note(b + t, 0.4, KICK, 104 if t in (0, 2) else 84)
            for t in (1, 3):
                drums.note(b + t, 0.3, POWER_SNARE, 100)
            for t in (0.5, 2.5, 3.5):
                drums.note(b + t, 0.25, TOM_LOW, 76)
            if cycle == 3:
                drums.note(b + 3.75, 0.2, TOM_MID, 72)
        if bar % 8 == 0:
            drums.note(b, 2, CRASH, 100)
    return s


def boss():
    """The boss: C minor with a Phrygian D-flat, low brass riffs, a dark choir and pounding drums."""
    s = Song(140, 32, seed=31)
    harmony = ['Cm', 'Cm', 'Db', 'Cm', 'Cm', 'Ab', 'Bb', 'G',
               'Cm', 'Cm', 'Db', 'Cm', 'Ab', 'Fm', 'Db', 'G',
               'Cm', 'Ab', 'Db', 'G', 'Cm', 'Ab', 'Db', 'G',
               'Cm', 'Cm', 'Db', 'Db', 'Cm', 'Ab', 'Db', 'G']
    riff = s.part(0, TROMBONE, volume=104, pan=56, reverb=40)
    tuba = s.part(1, TUBA, volume=100, pan=64, reverb=36)
    tremolo = s.part(2, STRINGS_TREM, volume=86, pan=84, reverb=56)
    low = s.part(3, STRINGS_FAST, volume=100, pan=44, reverb=36)
    choir = s.part(4, CHOIR, volume=86, pan=64, reverb=70)
    horns = s.part(5, HORNS, volume=106, pan=48, reverb=52)
    trumpet = s.part(6, TRUMPET, volume=100, pan=78, reverb=50)
    timpani = s.part(7, TIMPANI, volume=110, pan=64, reverb=46)
    hits = s.part(8, ORCH_HIT, volume=80, pan=64, reverb=56)
    drums = s.part(DRUMS, POWER_KIT, volume=100, pan=64, reverb=34)

    horns.line(32, 'C4:2 Eb4 D4  C4:3 G3  Ab3:2 Bb3 C4  Eb4:3 D4  C4:2 Eb4 F4  G4:3 Ab4  Ab4:2 G4 F4  G4:4', 104)
    trumpet.line(64, 'G5:1.5 Ab5:.5 G5:2  Eb5:4  F5:1.5 Gb5:.5 F5:2  B4:4  G5:1.5 Ab5:.5 G5:2  C6:4  Db6:1.5 C6:.5 Bb5:2  B5:4', 104)
    tremolo.swell(96, 32, 60, 127)

    for bar, ch in enumerate(harmony):
        b = bar * 4
        section = bar // 8
        # Phone speakers lose everything much under 100 Hz: the riff sits an octave up.
        r = root(ch, 3)
        # The riff: the root hammered in eighths with a lurch to the flat second.
        for i, iv in enumerate((0, 0, 12, 0, 1, 0, 0, 12)):
            riff.note(b + i * 0.5, 0.35, r + iv, 104 if i in (0, 4) else 84)
        ostinato(low, bar, ch, 2, 92, pattern=(0, 0, 0, 0, 0, 0, 0, 0))
        tuba.note(b, 1.9, root(ch, 2), 92).note(b + 2, 1.9, root(ch, 2), 84)
        # High strings shiver on the fifth and the flat sixth above.
        top = root(ch, 5) + 7
        tremolo.note(b, 3.95, top, 80).note(b, 3.95, top + 1, 70)
        if section >= 1:
            choir.notes_at(b, 3.95, triad(ch, 3) + [root(ch, 4)], 76 if section < 3 else 64)
        if section == 2:
            if bar % 2 == 0:
                hits.notes_at(b, 0.5, triad(ch, 4), 96)
        tr = root(ch, 2)
        if tr < n('D2'):
            tr += 12
        if bar % 8 == 7:
            roll(timpani, b, 4, tr, 70, 124)
        else:
            for t in (0, 1, 2, 3):
                timpani.note(b + t, 0.4, tr, 108 if t in (0, 2) else 82)
        # Drums: kick in eighths, toms answering, the snare cracking on two and four.
        if bar % 8 == 7:
            for i in range(16):
                drums.note(b + i * 0.25, 0.2, (TOM_LOW, TOM_LOW2, TOM_MID, TOM_HI)[i // 4], 74 + i * 3)
                drums.note(b + i * 0.25, 0.2, KICK, 90 if i % 2 == 0 else 70)
        else:
            for i in range(8):
                drums.note(b + i * 0.5, 0.3, KICK, 108 if i % 2 == 0 else 86)
            for t in (1, 3):
                drums.note(b + t, 0.3, POWER_SNARE, 106)
            for t in (1.5, 3.5):
                drums.note(b + t, 0.25, TOM_LOW2, 84)
        if bar % 8 == 0:
            drums.note(b, 2, CRASH, 108)
    return s


SONGS = {'menu': menu, 'battle': battle, 'boss': boss}
