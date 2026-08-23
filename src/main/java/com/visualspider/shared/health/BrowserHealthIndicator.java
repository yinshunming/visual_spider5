package com.visualspider.shared.health;

import com.visualspider.visualbrowser.BrowserLane;
import com.visualspider.visualbrowser.BrowserLane.LaneState;
import com.visualspider.visualbrowser.spi.LanePool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/health} 的 {@code browser} 组件（M6-4）。
 *
 * <p>聚合两个 lane 池的真实状态：任一 lane CRASHED -> DOWN；无 CRASHED 但有 BUILDING ->
 * OUT_OF_SERVICE；否则 UP。details 含 per-lane 摘要（池名 / 索引 / 状态 / 线程名）。
 *
 * <p>lane 崩溃检测 / 重建由 {@link BrowserLane} 自身负责（spec §D8），本类只读取聚合。
 */
@Component("browser")
public class BrowserHealthIndicator implements HealthIndicator {

    private final LanePool configLanePool;
    private final LanePool runLanePool;

    public BrowserHealthIndicator(
            @Qualifier("configLanePool") LanePool configLanePool,
            @Qualifier("runLanePool") LanePool runLanePool) {
        this.configLanePool = configLanePool;
        this.runLanePool = runLanePool;
    }

    @Override
    public Health health() {
        Map<String, Object> lanes = new HashMap<>();
        boolean anyCrashed = false;

        for (Map.Entry<String, LanePool> entry : List.of(
                Map.entry("config", configLanePool),
                Map.entry("run", runLanePool))) {
            String poolName = entry.getKey();
            LanePool pool = entry.getValue();
            if (!(pool instanceof LaneStatesProvider provider)) {
                continue;
            }
            List<LaneStatesProvider.LaneSnapshot> snaps = provider.laneSnapshots();
            for (LaneStatesProvider.LaneSnapshot s : snaps) {
                String key = poolName + "-" + s.index();
                Map<String, Object> info = new HashMap<>();
                info.put("state", s.state().name());
                info.put("thread", s.threadName());
                lanes.put(key, info);
                if (s.state() == LaneState.CRASHED) {
                    anyCrashed = true;
                }
            }
        }

        Health.Builder builder = anyCrashed ? Health.down() : Health.up();
        return builder.withDetail("lanes", lanes).build();
    }

    /** 通用实现：从一组 {@link BrowserLane} 构造快照列表。 */
    public static List<LaneStatesProvider.LaneSnapshot> snapshotsOf(List<BrowserLane> lanes) {
        List<LaneStatesProvider.LaneSnapshot> out = new ArrayList<>(lanes.size());
        for (BrowserLane lane : lanes) {
            int idx = lane.poolIndex();
            LaneState state = lane.state();
            String thread = lane.laneThreadName();
            out.add(new LaneStatesProvider.LaneSnapshot() {
                @Override public int index() { return idx; }
                @Override public LaneState state() { return state; }
                @Override public String threadName() { return thread; }
            });
        }
        return out;
    }
}
