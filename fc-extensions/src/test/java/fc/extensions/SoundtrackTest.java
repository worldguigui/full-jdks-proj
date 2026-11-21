package fc.extensions;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiFileFormat;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** 配乐工坊：确定性作曲、SMF 往返、WAV 合成、元数据与降级。 */
public final class SoundtrackTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(SoundtrackTest.class))) {
            System.exit(1);
        }
    }

    private static Path tempDir() throws Exception {
        return Files.createTempDirectory("fc-soundtrack-test-");
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String fingerprint(Sequence sequence) {
        StringBuilder sb = new StringBuilder();
        for (Track track : sequence.getTracks()) {
            sb.append("track:").append(track.size()).append('\n');
            for (int i = 0; i < track.size(); i++) {
                var event = track.get(i);
                MidiMessage message = event.getMessage();
                sb.append(event.getTick()).append(':').append(message.getStatus());
                if (message instanceof ShortMessage shortMessage) {
                    sb.append('/').append(shortMessage.getData1())
                            .append('/').append(shortMessage.getData2());
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    @FcTest
    public void compositionIsDeterministic() throws Exception {
        Sequence first = Soundtrack.compose(0x5EEDL, "边境");
        Sequence second = Soundtrack.compose(0x5EEDL, "边境");
        Soundtrack.Stats stats = Soundtrack.stats(first);
        assert stats.tracks() == 3 : stats.tracks();
        assert stats.notes() > 20 : stats.notes();
        assert stats.events() > stats.notes() : stats.events();
        assert stats.division().equals("PPQ") : stats.division();
        assert stats.resolution() == Soundtrack.PPQ : stats.resolution();
        assert stats.microseconds() > 0 : stats.microseconds();
        assert fingerprint(first).equals(fingerprint(second)) : "同种子必须同曲";

        Sequence other = Soundtrack.compose(0x5EEDL + 1, "边境");
        assert !fingerprint(first).equals(fingerprint(other)) : "不同种子应产生不同旋律";
    }

    @FcTest
    public void midiRoundTripPreservesFormat() throws Exception {
        Path dir = tempDir();
        try {
            Sequence original = Soundtrack.compose(11L, "航路");
            Path midi = Soundtrack.writeMidi(original, dir.resolve("music/frontier.mid"));
            assert Files.size(midi) > 500 : Files.size(midi);

            MidiFileFormat format = Soundtrack.format(midi);
            assert format.getType() == 1 : format.getType();
            assert format.getDivisionType() == Sequence.PPQ : format.getDivisionType();
            assert format.getResolution() == Soundtrack.PPQ : format.getResolution();
            assert format.getByteLength() == Files.size(midi) : format.getByteLength();

            Sequence loaded = Soundtrack.readMidi(midi);
            assert loaded.getTracks().length == 3 : loaded.getTracks().length;
            assert Soundtrack.stats(loaded).notes() == Soundtrack.stats(original).notes()
                    : "往返后音符数应一致";
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void wavRenderProducesValidPcm() throws Exception {
        Path dir = tempDir();
        try {
            Sequence sequence = Soundtrack.compose(7L, "静默环");
            Path wav = Soundtrack.renderWav(sequence, dir.resolve("music/frontier.wav"));
            assert Files.size(wav) > 100_000 : Files.size(wav);

            AudioFileFormat fileFormat = AudioSystem.getAudioFileFormat(wav.toFile());
            assert fileFormat.getType() == AudioFileFormat.Type.WAVE : fileFormat.getType();
            var format = fileFormat.getFormat();
            assert format.getChannels() == 1 : format.getChannels();
            assert Math.round(format.getSampleRate()) == 44_100 : format.getSampleRate();
            assert format.getSampleSizeInBits() == 16 : format.getSampleSizeInBits();

            try (AudioInputStream stream = AudioSystem.getAudioInputStream(wav.toFile())) {
                byte[] pcm = stream.readAllBytes();
                assert pcm.length > 100_000 : pcm.length;
                boolean nonSilent = false;
                for (int i = 0; i < pcm.length; i += 2) {
                    if (pcm[i] != 0 || pcm[i + 1] != 0) {
                        nonSilent = true;
                        break;
                    }
                }
                assert nonSilent : "WAV 不应全静音";
            }
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void metadataAndInstrumentsAreWritten() throws Exception {
        Sequence sequence = Soundtrack.compose(3L, "启航");
        int names = 0;
        int tempos = 0;
        int timeSignatures = 0;
        int programs = 0;
        boolean flute = false;
        boolean pad = false;
        boolean volume = false;
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiMessage message = track.get(i).getMessage();
                if (message instanceof MetaMessage meta) {
                    switch (meta.getType()) {
                        case 0x03 -> names++;
                        case 0x51 -> tempos++;
                        case 0x58 -> timeSignatures++;
                        default -> {
                        }
                    }
                } else if (message instanceof ShortMessage shortMessage) {
                    if (shortMessage.getCommand() == ShortMessage.PROGRAM_CHANGE) {
                        programs++;
                        flute |= shortMessage.getData1() == 73;
                        pad |= shortMessage.getData1() == 89;
                    }
                    if (shortMessage.getCommand() == ShortMessage.CONTROL_CHANGE
                            && shortMessage.getData1() == 7) {
                        volume = true;
                    }
                }
            }
        }
        assert names == 3 : names;
        assert tempos == 1 : tempos;
        assert timeSignatures == 1 : timeSignatures;
        assert programs == 2 : programs;
        assert flute && pad : "应写入长笛与铺底音色";
        assert volume : "应写入音量控制器";
    }

    @FcTest
    public void invalidInputsAndDeviceProbe() throws Exception {
        Path dir = tempDir();
        try {
            boolean nullSequence = false;
            try {
                Soundtrack.writeMidi(null, dir.resolve("x.mid"));
            } catch (IllegalArgumentException e) {
                nullSequence = true;
            }
            assert nullSequence;
            boolean nullWav = false;
            try {
                Soundtrack.renderWav(null, dir.resolve("x.wav"));
            } catch (IllegalArgumentException e) {
                nullWav = true;
            }
            assert nullWav;
            boolean nullStats = false;
            try {
                Soundtrack.stats(null);
            } catch (IllegalArgumentException e) {
                nullStats = true;
            }
            assert nullStats;
            boolean available = Soundtrack.sequencerAvailable();
            assert available || !available : "设备探测不得抛异常";
        } finally {
            deleteTree(dir);
        }
    }
}
