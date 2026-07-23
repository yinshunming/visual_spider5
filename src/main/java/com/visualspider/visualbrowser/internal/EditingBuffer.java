package com.visualspider.visualbrowser.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.shared.time.Clock;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.spi.TaskCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 会话内编辑缓冲（M2-4 #20）：
 *
 * <ul>
 *   <li>session 打开时加载任务快照到 buffer；</li>
 *   <li>编辑（更新 current）→ 取消上一个防抖任务并 scheduleSave(5s)；</li>
 *   <li>saveDraft 时若乐观锁冲突 → 重读 task → 用户当前编辑优先合并 → 重试 1 次；</li>
 *   <li>二次冲突抛 {@link EditBufferConflictException}，由 advice 映射为 409；</li>
 *   <li>session 关闭前 flushSync()：写库成功 → 删除 unsaved 备份；DB 失败 → 写入
 *       {@code logs/unsaved-buffer-{sessionId}.json}；</li>
 *   <li>buffer 状态变化不触发 {@code status} 更新；{@link TaskCatalog#saveDraft}
 *       成功后由 controller 调 {@code TaskReadiness} 决定 READY/DRAFT。</li>
 * </ul>
 *
 * <p>每个 session 实例由 {@link #getOrCreate(String, ActorId)} 创建并销毁。
 */
@Component
public class EditingBuffer {

    private static final Logger LOG = LoggerFactory.getLogger(EditingBuffer.class);

    private final TaskCatalog taskCatalog;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Duration debounce;
    private final Path logsDirectory;

    public EditingBuffer(TaskCatalog taskCatalog, Clock clock,
                         ScheduledExecutorService scheduler,
                         @Value("${visualbrowser.edit-buffer.debounce-seconds:5}") long debounceSeconds,
                         @Value("${visualbrowser.edit-buffer.logs-dir:logs}") String logsDir) {
        this.taskCatalog = taskCatalog;
        this.clock = clock;
        this.scheduler = scheduler;
        this.debounce = Duration.ofSeconds(debounceSeconds);
        this.logsDirectory = Paths.get(logsDir);
    }

    private final java.util.concurrent.ConcurrentMap<String, SessionState> states = new java.util.concurrent.ConcurrentHashMap<>();

    /** 取或创建 session 内编辑缓冲；首次访问会读 task 并缓存 baseVersion。 */
    public synchronized Buffer getOrCreate(String sessionId, long taskId, ActorId actor) {
        SessionState state = states.get(sessionId);
        if (state != null) {
            return state.buffer;
        }
        TaskDraft draft = taskCatalog.read(taskId, actor);
        Buffer buffer = new Buffer(sessionId, taskId, actor, draft.version(),
                new AtomicReference<>(draft.definition()));
        SessionState created = new SessionState(buffer);
        states.put(sessionId, created);
        return created.buffer;
    }

    public Optional<Buffer> find(String sessionId) {
        SessionState state = states.get(sessionId);
        return state == null ? Optional.empty() : Optional.of(state.buffer);
    }

    public void drop(String sessionId) {
        SessionState state = states.remove(sessionId);
        if (state != null) {
            ScheduledFuture<?> pending = state.scheduledSave.getAndSet(null);
            if (pending != null) {
                pending.cancel(false);
            }
        }
    }

    private synchronized void persist(String sessionId, Buffer buffer, ActorId actor) {
        TaskDefinition current = buffer.current.get();
        long expected = buffer.baseVersion;
        try {
            taskCatalog.saveDraft(buffer.taskId, current, expected, actor);
            buffer.baseVersion = expected + 1; // 乐观锁成功 → 自增
            LOG.info("autosave ok: sessionId={} taskId={} version={}", sessionId, buffer.taskId, buffer.baseVersion);
            // 成功 → 删除 unsaved 残留
            deleteUnsaved(sessionId);
        } catch (com.visualspider.task.domain.exceptions.StaleTaskVersionException ex) {
            // 1 次重试：重新读远端，与 current 合并（用户编辑优先）
            try {
                TaskDraft latest = taskCatalog.read(buffer.taskId, actor);
                // 合并策略：buffer.current 直接覆盖 server 的字段（用户优先）
                // 但需要尊重 taskId 不变与 viewport 等基础校验
                TaskDefinition merged = current; // 用户编辑优先，直接覆盖
                long secondExpected = latest.version();
                taskCatalog.saveDraft(buffer.taskId, merged, secondExpected, actor);
                buffer.baseVersion = secondExpected + 1;
                LOG.info("autosave retry ok: sessionId={} taskId={} version={}", sessionId, buffer.taskId, buffer.baseVersion);
                deleteUnsaved(sessionId);
            } catch (RuntimeException second) {
                LOG.warn("autosave conflict: sessionId={}", sessionId);
                // 双冲突 → 落 unsaved 文件
                writeUnsaved(sessionId, current);
                throw new EditBufferConflictException(sessionId);
            }
        } catch (RuntimeException ex) {
            LOG.warn("autosave DB failure: sessionId={}", sessionId, ex);
            // DB 不可用 → 落 unsaved 文件
            writeUnsaved(sessionId, current);
            throw ex;
        }
    }

    /** 触发 5 秒防抖保存（已存在调度则取消）。 */
    public void scheduleSave(String sessionId, ActorId actor) {
        SessionState state = states.get(sessionId);
        if (state == null) {
            return;
        }
        Buffer buffer = state.buffer;
        ScheduledFuture<?> pending = state.scheduledSave.get();
        if (pending != null && !pending.isDone()) {
            pending.cancel(false);
        }
        ScheduledFuture<?> next = scheduler.schedule(() -> {
            try {
                persist(sessionId, buffer, actor);
            } catch (RuntimeException ex) {
                LOG.warn("auto save failed: sessionId={} {}", sessionId, ex.getMessage());
            }
        }, debounce.toSeconds(), TimeUnit.SECONDS);
        state.scheduledSave.set(next);
    }

    /** 关闭前同步保存一次；DB 不可用时落到 logs/。 */
    public void flushSync(String sessionId, ActorId actor) {
        SessionState state = states.remove(sessionId);
        if (state == null) {
            return;
        }
        ScheduledFuture<?> pending = state.scheduledSave.getAndSet(null);
        if (pending != null && !pending.isDone()) {
            pending.cancel(false);
        }
        persist(sessionId, state.buffer, actor);
    }

    /** 立即覆盖当前编辑并触发一次防抖保存（用于显式"立即保存"动作）。 */
    public void update(String sessionId, ActorId actor, TaskDefinition next) {
        Buffer buffer = getOrCreate(sessionId, lookupTaskId(sessionId, actor), actor);
        buffer.current.set(next);
        scheduleSave(sessionId, actor);
    }

    private long lookupTaskId(String sessionId, ActorId actor) {
        // 仅供 update 调用；buffer 已存在时不会进入此分支。
        SessionState state = states.get(sessionId);
        return state == null ? -1L : state.buffer.taskId;
    }

    private void writeUnsaved(String sessionId, TaskDefinition def) {
        try {
            Files.createDirectories(logsDirectory);
            Path file = logsDirectory.resolve("unsaved-buffer-" + safeName(sessionId) + ".json");
            String json = "{ \"sessionId\": \"" + sessionId + "\","
                    + " \"taskId\": " + -1 + ","
                    + " \"schemaVersion\": " + def.schemaVersion() + " }";
            Files.writeString(file, json);
        } catch (IOException ex) {
            LOG.warn("failed to write unsaved buffer: sessionId={}", sessionId, ex);
        }
    }

    private void deleteUnsaved(String sessionId) {
        try {
            Files.deleteIfExists(logsDirectory.resolve("unsaved-buffer-" + safeName(sessionId) + ".json"));
        } catch (IOException ignored) {
        }
    }

    private static String safeName(String s) {
        return s.replaceAll("[^a-zA-Z0-9-]", "_");
    }

    /** 公开 buffer 状态，扩展时保留 setter by convention。 */
    public static final class Buffer {
        public final String sessionId;
        public final long taskId;
        public final ActorId owner;
        public volatile long baseVersion;
        public final AtomicReference<TaskDefinition> current;
        public final AtomicReference<TaskDefinition> baseDefinition;
        public final AtomicLong lastChangeAt;

        Buffer(String sessionId, long taskId, ActorId owner, long baseVersion,
               AtomicReference<TaskDefinition> current) {
            this.sessionId = sessionId;
            this.taskId = taskId;
            this.owner = owner;
            this.baseVersion = baseVersion;
            this.current = current;
            this.baseDefinition = new AtomicReference<>(current.get());
            this.lastChangeAt = new AtomicLong(System.currentTimeMillis());
        }
    }

    /** 内部 session 状态：缓冲 + 防抖 future。 */
    private static final class SessionState {
        final Buffer buffer;
        final AtomicReference<ScheduledFuture<?>> scheduledSave = new AtomicReference<>();

        SessionState(Buffer buffer) {
            this.buffer = buffer;
        }
    }

    /** 编辑缓冲与最新远端版本再次冲突的业务异常。 */
    public static final class EditBufferConflictException extends RuntimeException {
        public EditBufferConflictException(String sessionId) {
            super("编辑缓冲与最新版本冲突 sessionId=" + sessionId);
        }
    }
}
