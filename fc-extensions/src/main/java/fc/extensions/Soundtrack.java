package fc.extensions;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiFileFormat;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 配乐工坊：按世界状态确定性生成 MIDI 配乐，并可离线合成 WAV。
 *
 * <p>四个小节的五声音阶主题 + 铺底和弦 + 打击乐，全部由种子决定；同一世界状态
 * 得到同一份 MIDI。写盘使用 {@link MidiSystem#write(Sequence, int, java.io.File)}
 * （SMF type 1），读取使用 {@link MidiSystem#getSequence(java.io.File)}；
 * WAV 渲染是一个极简正弦合成器，经 {@link AudioSystem#write} 写出，
 * 不需要声卡，也不依赖系统时钟。</p>
 *
 * <p>播放交给外部 MIDI 播放器；{@link #sequencerAvailable()} 仅供 UI 决定
 * 是否显示「播放」按钮。</p>
 */
public final class Soundtrack {

    /** 每四分音符的 tick 数。 */
    public static final int PPQ = 480;
    /** 主题速度（BPM）。 */
    public static final int BPM = 96;
    /** 曲长：4 小节 × 4 拍。 */
    public static final int BARS = 4;
    public static final int BEATS_PER_BAR = 4;

    private static final int[] SCALE = {60, 62, 64, 67, 69, 72, 74, 76};
    private static final int CHANNEL_LEAD = 0;
    private static final int CHANNEL_PAD = 1;
    private static final int CHANNEL_DRUMS = 9;

    private static final AudioFormat PCM =
            new AudioFormat(44_100f, 16, 1, true, false);

    /** 配乐统计。 */
    public record Stats(int tracks, int events, int notes, String division,
                        int resolution, long microseconds) {
    }

    private Soundtrack() {
    }

    /** 按种子生成 4 小节配乐（PPQ=480，96 BPM，4/4）。 */
    public static Sequence compose(long seed, String mood) throws InvalidMidiDataException {
        String title = (mood == null || mood.isBlank()) ? "Frontier" : mood.strip();
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track lead = sequence.createTrack();
        Track pad = sequence.createTrack();
        Track drums = sequence.createTrack();

        long ticksPerBeat = PPQ;
        long totalTicks = (long) BARS * BEATS_PER_BAR * ticksPerBeat;
        name(lead, "Lead — " + title);
        name(pad, "Pad");
        name(drums, "Percussion");
        tempo(lead, BPM);
        timeSignature(lead);

        program(lead, CHANNEL_LEAD, 73);   // Flute
        program(pad, CHANNEL_PAD, 89);     // Warm Pad
        controller(lead, CHANNEL_LEAD, 7, 100);
        controller(lead, CHANNEL_LEAD, 10, 64);
        controller(pad, CHANNEL_PAD, 7, 72);
        controller(pad, CHANNEL_PAD, 10, 36);

        Random random = new Random(seed);
        for (int bar = 0; bar < BARS; bar++) {
            long barStart = bar * BEATS_PER_BAR * ticksPerBeat;
            int root = 48 + SCALE[random.nextInt(4)];
            // 铺底三和弦：根音 + 纯四度 + 纯五度，整小节保持。
            for (int interval : new int[] {0, 5, 7}) {
                note(pad, CHANNEL_PAD, root + interval, 56,
                        barStart, barStart + BEATS_PER_BAR * ticksPerBeat - 1);
            }
            // 主旋律：每拍两个八分音符，从五声音阶取音。
            for (int beat = 0; beat < BEATS_PER_BAR * 2; beat++) {
                int note = SCALE[random.nextInt(SCALE.length)] + 12;
                long start = barStart + beat * (ticksPerBeat / 2);
                note(lead, CHANNEL_LEAD, note, 78 + random.nextInt(20),
                        start, start + ticksPerBeat / 2 - 12);
            }
            // 打击乐：底鼓 1、3 拍，军鼓 2、4 拍，踩镲每半拍。
            for (int beat = 0; beat < BEATS_PER_BAR; beat++) {
                long tick = barStart + beat * ticksPerBeat;
                int drum = switch (beat) {
                    case 0, 2 -> 36;
                    case 1, 3 -> 38;
                    default -> 42;
                };
                note(drums, CHANNEL_DRUMS, drum, 92, tick, tick + 60);
                if (beat == 3) {
                    note(drums, CHANNEL_DRUMS, 49, 88, tick + 240, tick + 300);
                }
                note(drums, CHANNEL_DRUMS, 42, 58, tick + 240, tick + 260);
            }
        }
        endOfTrack(lead, totalTicks);
        endOfTrack(pad, totalTicks);
        endOfTrack(drums, totalTicks);
        return sequence;
    }

    /** 写出标准 MIDI 文件（SMF type 1）；自动创建父目录。 */
    public static Path writeMidi(Sequence sequence, Path output) throws IOException {
        if (sequence == null) {
            throw new IllegalArgumentException("sequence 不能为空");
        }
        Path normalized = prepare(output);
        MidiSystem.write(sequence, 1, normalized.toFile());
        return normalized;
    }

    /** 读取 MIDI 文件为 {@link Sequence}。 */
    public static Sequence readMidi(Path input) throws IOException, InvalidMidiDataException {
        return MidiSystem.getSequence(input.toFile());
    }

    /** 读取 MIDI 文件头（类型、分辨率、时长、字节数）。 */
    public static MidiFileFormat format(Path input) throws IOException, InvalidMidiDataException {
        return MidiSystem.getMidiFileFormat(input.toFile());
    }

    /**
     * 离线渲染为 16-bit/44.1kHz/单声道 WAV。
     *
     * <p>只用正弦波与简单包络，无外部合成器；音符频率按十二平均律从 MIDI 音高换算。</p>
     */
    public static Path renderWav(Sequence sequence, Path output) throws IOException {
        if (sequence == null) {
            throw new IllegalArgumentException("sequence 不能为空");
        }
        List<Note> notes = collectNotes(sequence);
        long totalTicks = 0;
        for (Track track : sequence.getTracks()) {
            if (track.size() > 0) {
                totalTicks = Math.max(totalTicks, track.get(track.size() - 1).getTick());
            }
        }
        double secondsPerTick = totalTicks == 0 ? 0
                : sequence.getMicrosecondLength() / 1_000_000.0 / totalTicks;
        double seconds = secondsPerTick * totalTicks + 0.4;
        int frames = Math.max(1, (int) Math.round(seconds * PCM.getSampleRate()));
        double[] mix = new double[frames];
        for (Note note : notes) {
            double frequency = 440.0 * Math.pow(2.0, (note.pitch() - 69) / 12.0);
            int start = (int) Math.round(note.startTick() * secondsPerTick * PCM.getSampleRate());
            int end = (int) Math.round(note.endTick() * secondsPerTick * PCM.getSampleRate());
            end = Math.min(end, frames);
            double amplitude = switch (note.channel()) {
                case CHANNEL_DRUMS -> 0.18;
                case CHANNEL_PAD -> 0.07;
                default -> 0.12;
            };
            for (int i = Math.max(0, start); i < end; i++) {
                double t = (i - start) / PCM.getSampleRate();
                double duration = (end - start) / PCM.getSampleRate();
                double envelope = Math.min(1.0, t / 0.01) * Math.min(1.0,
                        (duration - t) / 0.06);
                mix[i] += Math.sin(2 * Math.PI * frequency * t) * amplitude
                        * Math.max(0, envelope);
            }
        }
        double peak = 0;
        for (double v : mix) {
            peak = Math.max(peak, Math.abs(v));
        }
        double gain = peak > 0.95 ? 0.95 / peak : 1.0;
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            short sample = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE,
                    Math.round(mix[i] * gain * Short.MAX_VALUE)));
            pcm[i * 2] = (byte) (sample & 0xFF);
            pcm[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        Path normalized = prepare(output);
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), PCM, frames)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, normalized.toFile());
        }
        return normalized;
    }

    /** 统计轨道、事件、音符与时长。 */
    public static Stats stats(Sequence sequence) {
        if (sequence == null) {
            throw new IllegalArgumentException("sequence 不能为空");
        }
        Track[] tracks = sequence.getTracks();
        int events = 0;
        int notes = 0;
        for (Track track : tracks) {
            events += track.size();
            for (int i = 0; i < track.size(); i++) {
                MidiMessage message = track.get(i).getMessage();
                if (message instanceof ShortMessage shortMessage
                        && shortMessage.getCommand() == ShortMessage.NOTE_ON
                        && shortMessage.getData2() > 0) {
                    notes++;
                }
            }
        }
        return new Stats(tracks.length, events, notes,
                sequence.getDivisionType() == Sequence.PPQ ? "PPQ" : "SMPTE",
                sequence.getResolution(), sequence.getMicrosecondLength());
    }

    /** 系统是否有可用的 MIDI 合成器/音序器（无设备时返回 false，不抛异常）。 */
    public static boolean sequencerAvailable() {
        try {
            var sequencer = MidiSystem.getSequencer(false);
            if (sequencer == null) {
                return false;
            }
            sequencer.close();
            return true;
        } catch (MidiUnavailableException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------ MIDI 辅助

    private record Note(int channel, int pitch, long startTick, long endTick) {
    }

    private static List<Note> collectNotes(Sequence sequence) {
        List<Note> notes = new ArrayList<>();
        for (Track track : sequence.getTracks()) {
            List<long[]> starts = new ArrayList<>();
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (!(message instanceof ShortMessage shortMessage)) {
                    continue;
                }
                int command = shortMessage.getCommand();
                int pitch = shortMessage.getData1();
                if (command == ShortMessage.NOTE_ON && shortMessage.getData2() > 0) {
                    starts.add(new long[] {pitch, event.getTick()});
                } else if (command == ShortMessage.NOTE_OFF
                        || (command == ShortMessage.NOTE_ON && shortMessage.getData2() == 0)) {
                    for (int j = starts.size() - 1; j >= 0; j--) {
                        if (starts.get(j)[0] == pitch) {
                            long start = starts.remove(j)[1];
                            notes.add(new Note(shortMessage.getChannel(), pitch,
                                    start, event.getTick()));
                            break;
                        }
                    }
                }
            }
        }
        notes.sort(Comparator.comparingLong(Note::startTick).thenComparingInt(Note::pitch));
        return notes;
    }

    private static void note(Track track, int channel, int pitch, int velocity,
                             long start, long end) throws InvalidMidiDataException {
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, channel, pitch,
                Math.max(1, Math.min(127, velocity))), start));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, channel, pitch, 0),
                Math.max(start + 1, end)));
    }

    private static void program(Track track, int channel, int program)
            throws InvalidMidiDataException {
        track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel,
                program, 0), 0));
    }

    private static void controller(Track track, int channel, int controller, int value)
            throws InvalidMidiDataException {
        track.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel,
                controller, value), 0));
    }

    private static void name(Track track, String name) throws InvalidMidiDataException {
        byte[] bytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        track.add(new MidiEvent(new MetaMessage(0x03, bytes, bytes.length), 0));
    }

    private static void tempo(Track track, int bpm) throws InvalidMidiDataException {
        int microsPerQuarter = 60_000_000 / bpm;
        byte[] data = {(byte) (microsPerQuarter >> 16),
                (byte) (microsPerQuarter >> 8), (byte) microsPerQuarter};
        track.add(new MidiEvent(new MetaMessage(0x51, data, data.length), 0));
    }

    private static void timeSignature(Track track) throws InvalidMidiDataException {
        byte[] data = {4, 2, 24, 8};
        track.add(new MidiEvent(new MetaMessage(0x58, data, data.length), 0));
    }

    private static void endOfTrack(Track track, long tick) throws InvalidMidiDataException {
        track.add(new MidiEvent(new MetaMessage(0x2F, new byte[0], 0), tick));
    }

    private static Path prepare(Path output) throws IOException {
        if (output == null) {
            throw new IllegalArgumentException("输出路径不能为空");
        }
        Path normalized = output.toAbsolutePath().normalize();
        if (normalized.getParent() != null) {
            Files.createDirectories(normalized.getParent());
        }
        return normalized;
    }
}
