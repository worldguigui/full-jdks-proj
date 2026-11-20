package fc.world;

/**
 * 主体需求（Needs）：饥饿、疲劳、士气，取值 0..100。
 *
 * <p>语义：{@code hunger}/{@code fatigue} 越高越糟，{@code morale} 越高越好。
 * 不可变值对象；变化一律返回新实例。</p>
 */
public record Needs(int hunger, int fatigue, int morale) {

    public static final Needs INITIAL = new Needs(20, 10, 70);

    public Needs {
        hunger = clamp(hunger);
        fatigue = clamp(fatigue);
        morale = clamp(morale);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    /** 饥饿 +delta（delta 可为负）。 */
    public Needs withHunger(int delta) {
        return new Needs(hunger + delta, fatigue, morale);
    }

    /** 疲劳 +delta。 */
    public Needs withFatigue(int delta) {
        return new Needs(hunger, fatigue + delta, morale);
    }

    /** 士气 +delta。 */
    public Needs withMorale(int delta) {
        return new Needs(hunger, fatigue, morale + delta);
    }

    /** 是否处于饥饿危急（决策优先级最高）。 */
    public boolean starving() {
        return hunger >= 75;
    }

    /** 是否疲劳过度（需要休息）。 */
    public boolean exhausted() {
        return fatigue >= 70;
    }

    /** 士气低落（可能拒绝指令）。 */
    public boolean demoralized() {
        return morale <= 20;
    }
}
