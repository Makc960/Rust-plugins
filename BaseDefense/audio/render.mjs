// Renders MIDI files to WAV with a SoundFont, through spessasynth_core.
// usage: node render.mjs <soundfont.sf2> <in.mid> <out.wav> [<in.mid> <out.wav> ...]
import * as fs from "node:fs/promises";
import { audioToWav, BasicMIDI, SoundBankLoader, SpessaSynthProcessor, SpessaSynthSequencer } from "spessasynth_core";

const RATE = 44_100;
// The sequencer moves on once a block: small blocks keep the notes on time, and every loop alike.
const BLOCK = 32;
// Room for the last notes and the reverb to die away.
const TAIL = 3;

const [bankPath, ...jobs] = process.argv.slice(2);
if (!bankPath || jobs.length === 0 || jobs.length % 2 !== 0) {
    console.error("usage: node render.mjs <soundfont.sf2> <in.mid> <out.wav> [...]");
    process.exit(2);
}
const bank = SoundBankLoader.fromArrayBuffer((await fs.readFile(bankPath)).buffer);

for (let j = 0; j < jobs.length; j += 2) {
    const midi = BasicMIDI.fromArrayBuffer((await fs.readFile(jobs[j])).buffer);
    const synth = new SpessaSynthProcessor(RATE, { eventsEnabled: false });
    synth.soundBankManager.addSoundBank(bank, "main");
    await synth.processorInitialized;
    synth.setSystemParameter("autoAllocateVoices", true);
    const seq = new SpessaSynthSequencer(synth);
    seq.loadNewSongList([midi]);
    seq.play();
    const total = Math.ceil(RATE * (midi.duration + TAIL));
    const left = new Float32Array(total);
    const right = new Float32Array(total);
    for (let done = 0; done < total; done += BLOCK) {
        seq.processTick();
        synth.process(left, right, done, Math.min(BLOCK, total - done));
    }
    await fs.writeFile(jobs[j + 1], new Uint8Array(audioToWav([left, right], RATE)));
    console.log(`${jobs[j + 1]}: ${(total / RATE).toFixed(1)} s`);
}
