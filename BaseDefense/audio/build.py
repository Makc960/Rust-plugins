"""Builds the game's audio: the music and the sound effects, rendered with a SoundFont and
mixed from recordings, into OGG files the app plays.

usage: python3 build.py <soundfont.sf2> <kenney packs dir> <work dir> <out dir> [music | sfx | names...]
"""
import subprocess
import sys
from pathlib import Path

import mido
import numpy as np
from scipy.io import wavfile

import music
import sfx

RATE = 44100
SEAM = 2205
HERE = Path(__file__).resolve().parent


def read(path):
    rate, data = wavfile.read(path)
    assert rate == RATE, (path, rate)
    data = data.astype(np.float32)
    if data.dtype != np.float32 or np.abs(data).max() > 2:
        data /= 32768.0
    return data if data.ndim == 2 else np.stack([data, data], axis=1)


def render(bank, jobs, work):
    """Renders (midi, wav) pairs in one run of the synthesizer."""
    args = ['node', str(HERE / 'render.mjs'), str(bank)]
    for mid, wav in jobs:
        args += [str(mid), str(wav)]
    subprocess.run(args, check=True, cwd=work, stdout=subprocess.DEVNULL)


def encode(data, path, quality, mono=False):
    """Writes [data] as Ogg Vorbis through ffmpeg."""
    wav = path.with_suffix('.wav')
    pcm = data.mean(axis=1) if mono else data
    wavfile.write(wav, RATE, (np.clip(pcm, -1, 1) * 32767).astype(np.int16))
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(wav), '-c:a', 'libvorbis', '-q:a', str(quality), str(path)], check=True)
    wav.unlink()


def master(data, rms_db, peak_db=-1.0):
    """Brings [data] to a loudness, then rounds off any peak above [peak_db] instead of clipping it."""
    rms = np.sqrt(np.mean(data ** 2)) + 1e-9
    data = data * (10 ** (rms_db / 20) / rms)
    ceiling = 10 ** (peak_db / 20)
    over = np.abs(data) > ceiling * 0.8
    if over.any():
        knee = ceiling * 0.8
        s = np.sign(data)
        a = np.abs(data)
        a = np.where(a > knee, knee + (ceiling - knee) * np.tanh((a - knee) / (ceiling - knee)), a)
        data = s * a
    return data


def build_music(bank, work, out, names):
    jobs = []
    for name, make in music.SONGS.items():
        if names and name not in names:
            continue
        song = make()
        mid = work / f'music_{name}.mid'
        song.save(mid, loops=3)
        jobs.append((name, song, mid, work / f'music_{name}.wav'))
    if not jobs:
        return
    render(bank, [(j[2], j[3]) for j in jobs], work)
    for name, song, _, wav in jobs:
        data = read(wav)
        # The tempo as the MIDI file has it, in whole microseconds a beat.
        loop = int(round(song.beats * mido.bpm2tempo(song.bpm) / 1e6 * RATE))
        # The middle time round: its start carries the first's tails and its end the third's
        # early notes, just as when it plays on repeat. Its first moments are blended with
        # the third time's, which really follow its end, so no click is left at the seam.
        piece = data[loop:2 * loop].copy()
        fade = np.linspace(0, 1, SEAM)[:, None]
        piece[:SEAM] = piece[:SEAM] * fade + data[2 * loop:2 * loop + SEAM] * (1 - fade)
        piece = master(piece, -17.0)
        encode(piece, out / f'music_{name}.ogg', 4)
        print(f'music_{name}.ogg  {len(piece) / RATE:.1f} s')


def main():
    bank, kenney, work, out = (Path(a) for a in sys.argv[1:5])
    names = set(sys.argv[5:])
    work.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    # Names pick what to build: a song, an effect, or "music" or "sfx" for all of either.
    if not names or 'music' in names or names & set(music.SONGS):
        build_music(bank, work, out, set() if not names or 'music' in names else names)
    if not names or 'sfx' in names or names - set(music.SONGS) - {'music'}:
        sfx.build(bank, kenney, work, out, set() if not names or 'sfx' in names else names - set(music.SONGS),
                  render=render, read=read, encode=encode)


if __name__ == '__main__':
    main()
