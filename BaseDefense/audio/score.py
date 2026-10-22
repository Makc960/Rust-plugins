"""A small score writer: parts of notes in beats, saved as a MIDI file for render.mjs."""
import random

import mido

TPB = 480
DRUMS = 9

NAMES = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}


def n(name):
    """A note name like 'D5', 'F#3' or 'Bb4' as a MIDI pitch (C4 is 60)."""
    pitch = NAMES[name[0]]
    rest = name[1:]
    while rest and rest[0] in '#b':
        pitch += 1 if rest[0] == '#' else -1
        rest = rest[1:]
    return pitch + 12 * (int(rest) + 1)


def chord(names):
    return [n(x) for x in names.split()]


class Part:
    def __init__(self, song, channel, program, bank=0, volume=100, pan=64, reverb=40, chorus=0):
        self.song = song
        self.channel = channel
        self.program = program
        self.bank = bank
        self.volume = volume
        self.pan = pan
        self.reverb = reverb
        self.chorus = chorus
        self.notes = []      # (beat, length, pitch, velocity)
        self.controls = []   # (beat, controller, value)

    def note(self, beat, length, pitch, vel=90):
        if isinstance(pitch, str):
            pitch = n(pitch)
        self.notes.append((beat, length, pitch, max(1, min(127, int(vel)))))
        return self

    def notes_at(self, beat, length, pitches, vel=90):
        for p in pitches:
            self.note(beat, length, p, vel)
        return self

    def line(self, beat, spec, vel=90, legato=0.95):
        """Plays 'D5:1.5 A4:.5 D5 F5' from [beat]: a note and its length in beats (1 if left out), 'r' rests."""
        for token in spec.split():
            name, _, length = token.partition(':')
            length = float(length) if length else 1.0
            if name != 'r':
                self.note(beat, length * legato, name, vel)
            beat += length
        return beat

    def cc(self, beat, controller, value):
        self.controls.append((beat, controller, int(value)))
        return self

    def swell(self, beat, length, start, end, controller=11, steps=16):
        """Expression from [start] to [end] over [length] beats."""
        for k in range(steps + 1):
            self.cc(beat + length * k / steps, controller, start + (end - start) * k / steps)
        return self


class Song:
    def __init__(self, bpm, bars, beats_per_bar=4, seed=1):
        self.bpm = bpm
        self.bars = bars
        self.beats_per_bar = beats_per_bar
        self.parts = []
        self.rng = random.Random(seed)

    @property
    def beats(self):
        return self.bars * self.beats_per_bar

    @property
    def seconds(self):
        return self.beats * 60.0 / self.bpm

    def part(self, *args, **kw):
        p = Part(self, *args, **kw)
        self.parts.append(p)
        return p

    def save(self, path, loops=1, humanize=True):
        """Writes the song [loops] times over, so a renderer can cut one loop out of the middle with its tails."""
        mid = mido.MidiFile(ticks_per_beat=TPB)
        meta = mido.MidiTrack()
        meta.append(mido.MetaMessage('set_tempo', tempo=mido.bpm2tempo(self.bpm), time=0))
        meta.append(mido.MetaMessage('time_signature', numerator=self.beats_per_bar, denominator=4, time=0))
        mid.tracks.append(meta)
        for p in self.parts:
            events = []
            setup = [(0, 'control_change', dict(control=0, value=p.bank)), (0, 'control_change', dict(control=32, value=0)),
                     (0, 'program_change', dict(program=p.program)), (0, 'control_change', dict(control=7, value=p.volume)),
                     (0, 'control_change', dict(control=10, value=p.pan)), (0, 'control_change', dict(control=91, value=p.reverb)),
                     (0, 'control_change', dict(control=93, value=p.chorus)), (0, 'control_change', dict(control=11, value=127))]
            for k, (t, kind, args) in enumerate(setup):
                events.append((t, 0, k, kind, args))
            # A player's small unevenness, the same every time round so the loop stays seamless.
            rng = random.Random(p.channel * 1000 + p.program * 7 + p.bank)
            spread = (6 if p.channel != DRUMS else 3) if humanize else 0
            feel = [(rng.randint(-spread, spread), rng.randint(-6, 6) if humanize else 0) for _ in p.notes]
            for loop in range(loops):
                off = loop * self.beats
                for (beat, ctl, value) in p.controls:
                    events.append((int((off + beat) * TPB), 1, 0, 'control_change', dict(control=ctl, value=value)))
                for (beat, length, pitch, vel), (jitter, dv) in zip(p.notes, feel):
                    v = vel + dv
                    start = max(0, int((off + beat) * TPB) + jitter)
                    end = max(start + 1, int((off + beat + length) * TPB) + jitter)
                    events.append((start, 2, 0, 'note_on', dict(note=pitch, velocity=max(1, min(127, v)))))
                    events.append((end, 0, 1, 'note_off', dict(note=pitch, velocity=0)))
            events.sort(key=lambda e: (e[0], e[1], e[2]))
            track = mido.MidiTrack()
            last = 0
            for (t, _, _, kind, args) in events:
                track.append(mido.Message(kind, channel=p.channel, time=t - last, **args))
                last = t
            mid.tracks.append(track)
        # Silence to the end of the last loop, so the file is as long as the music.
        end = mido.MidiTrack()
        end.append(mido.MetaMessage('end_of_track', time=int(loops * self.beats * TPB)))
        mid.tracks.append(end)
        mid.save(path)
