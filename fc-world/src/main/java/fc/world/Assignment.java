package fc.world;

/**
 * 主体的当前指派状态（Assignment）。
 *
 * <p>船员 / 居民 / 使者共用同一套状态机，只是所在地点与典型状态不同。</p>
 */
public enum Assignment {
    /** 空闲（无任务，或在等待）。 */
    IDLE,
    /** 工作（在岗产出）。 */
    WORKING,
    /** 休息（恢复疲劳）。 */
    RESTING,
    /** 在途（随所在舰船航行）。 */
    TRAVELING
}
