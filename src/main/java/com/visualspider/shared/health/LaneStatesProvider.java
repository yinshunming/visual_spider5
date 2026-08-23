package com.visualspider.shared.health;

import com.visualspider.visualbrowser.BrowserLane.LaneState;
import java.util.List;

/**
 * Per-lane 状态快照（M6-4）。
 *
 * <p>由 {@link BrowserHealthIndicator} 聚合两个 lane 池（config / run）的 per-lane
 * 状态输出到 {@code /actuator/health}。
 */
public interface LaneStatesProvider {

    /** Per-lane 状态快照。 */
    interface LaneSnapshot {
        int index();
        LaneState state();
        String threadName();
    }

    /** 返回当前池内所有 lane 的状态快照。 */
    List<LaneSnapshot> laneSnapshots();
}
