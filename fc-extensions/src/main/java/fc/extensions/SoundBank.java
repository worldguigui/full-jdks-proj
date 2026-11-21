package fc.extensions;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * 音效能力探测与合成提示音（javax.sound.sampled）。
 *
 * <p>无音频设备/无头环境是正常情况：{@link #available()} 返回 false，
 * {@link #playTone(int, int)} 直接返回 false，不抛异常、不阻塞游戏。</p>
 */
public final class SoundBank {

    private static final AudioFormat FORMAT =
            new AudioFormat(44_100f, 16, 1, true, false);
    private static final DataLine.Info LINE_INFO =
            new DataLine.Info(SourceDataLine.class, FORMAT);

    private SoundBank() {
    }

    /** 当前环境是否支持 44.1kHz/16bit/单声道输出。 */
    public static boolean available() {
        try {
            return AudioSystem.isLineSupported(LINE_INFO);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 播放一个正弦提示音。
     *
     * @param hertz 频率（80–12000）
     * @param millis 时长（20–2000 毫秒）
     * @return 是否真正播放；无音频设备时 false
     */
    public static boolean playTone(int hertz, int millis) {
        if (hertz < 80 || hertz > 12_000) {
            throw new IllegalArgumentException("频率超出范围: " + hertz);
        }
        if (millis < 20 || millis > 2_000) {
            throw new IllegalArgumentException("时长超出范围: " + millis);
        }
        if (!available()) {
            return false;
        }
        int frames = (int) (FORMAT.getSampleRate() * millis / 1000.0);
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            // 两端 10% 淡入淡出，避免爆音。
            double envelope = Math.min(1.0, Math.min(i, frames - i) / (frames * 0.1));
            short sample = (short) (Math.sin(2 * Math.PI * hertz * i / FORMAT.getSampleRate())
                    * 0.22 * envelope * Short.MAX_VALUE);
            pcm[i * 2] = (byte) (sample & 0xFF);
            pcm[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        try (SourceDataLine line = AudioSystem.getSourceDataLine(FORMAT)) {
            line.open(FORMAT, pcm.length);
            line.start();
            line.write(pcm, 0, pcm.length);
            line.drain();
            line.stop();
            return true;
        } catch (LineUnavailableException | IllegalArgumentException
                 | SecurityException e) {
            return false;
        }
    }
}
