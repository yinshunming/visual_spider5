package com.visualspider.run.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.run.spi.Page;
import com.visualspider.run.spi.RunCoordinator;
import com.visualspider.run.spi.RunDetail;
import com.visualspider.run.spi.RunFilter;
import com.visualspider.run.spi.RunProgress;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.RunSummary;
import com.visualspider.task.domain.ReadinessReport;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.TaskSnapshot;
import com.visualspider.task.spi.TaskCatalog;
import com.visualspider.task.spi.TaskReadiness;
import com.visualspider.task.spi.TaskSnapshotFactory;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;

/**
 * {@link RunCoordinator} 默认实现（M3-2 #24 / spec §D2 / ADR-0006）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>{@link #start(long, ActorId)}：in-JVM 锁串行 count-check + insert，
 *       防止两并发请求同时通过 W+R 检查</li>
 *   <li>非 owner / 非 admin 任务读取抛 {@link AccessDeniedException}</li>
 *   <li>非 READY 任务 → {@link TaskNotReadyException}</li>
 *   <li>每用户 (W+R)≥1 → {@link UserRunLimitException}</li>
 *   <li>终态 run cancel → {@link RunNotCancellableException}</li>
 *   <li>非 owner / 非 admin run 读取 → {@link RunNotFoundException}（不回显存在性）</li>
 * </ul>
 */
public class RunCoordinatorImpl implements RunCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(RunCoordinatorImpl.class);

    private final RunRepository repository;
    private final TaskCatalog taskCatalog;
    private final TaskReadiness taskReadiness;
    private final TaskSnapshotFactory snapshotFactory;
    private final IdentityAccess identityAccess;
    /** start 创建的串行锁；cancel / get / list / progress 不持锁。 */
    private final ReentrantLock startLock = new ReentrantLock();

    private final DispatchSignaler signaler;

    public RunCoordinatorImpl(RunRepository repository,
                              TaskCatalog taskCatalog,
                              TaskReadiness taskReadiness,
                              TaskSnapshotFactory snapshotFactory,
                              IdentityAccess identityAccess,
                              DispatchSignaler signaler) {
        this.repository = repository;
        this.taskCatalog = taskCatalog;
        this.taskReadiness = taskReadiness;
        this.snapshotFactory = snapshotFactory;
        this.identityAccess = identityAccess;
        this.signaler = signaler;
    }

    /** 测试构造：signaler 为 no-op（便于不接派发器的纯单元测试）。 */
    public RunCoordinatorImpl(RunRepository repository,
                              TaskCatalog taskCatalog,
                              TaskReadiness taskReadiness,
                              TaskSnapshotFactory snapshotFactory,
                              IdentityAccess identityAccess) {
        this(repository, taskCatalog, taskReadiness, snapshotFactory, identityAccess, NOOP_SIGNALER);
    }

    @Override
    public RunSummary start(long taskId, ActorId actor) {
        startLock.lock();
        try {
            // 1) 读 draft（TaskCatalog.read 内部做 owner 校验；不存在抛 TaskNotFoundException；
            //    非 owner / 非 admin 抛 AccessDeniedException）。
            TaskDraft draft = taskCatalog.read(taskId, actor);

            // 2) 任务状态校验：必须是 READY
            if (draft.status() != com.visualspider.task.domain.TaskStatus.READY) {
                throw new TaskNotReadyException(taskId,
                        "任务当前 status=" + draft.status().name() + "（要求 READY）");
            }

            // 3) 双重 owner / admin 校验（TaskCatalog.read 已做；此处防御性二次确认）
            if (!identityAccess.canAccessTask(draft.ownerId(), actor)) {
                throw new AccessDeniedException("无权访问该任务");
            }

            // 4) 运行前校验（spec §D2）
            ReadinessReport report = taskReadiness.validateForRun(taskId, actor);
            if (!report.ready()) {
                String firstMsg = report.errors().isEmpty() ? "" : report.errors().get(0).message();
                throw new TaskNotReadyException(taskId, "校验未通过: " + firstMsg);
            }

            // 5) 同事务 count-check（in-JVM 锁已串行）
            //    run owner = task owner（即使 admin 代启动，W+R 也归 task owner）
            int active = repository.countActiveByOwner(draft.ownerId());
            if (active >= 1) {
                throw new UserRunLimitException(draft.ownerId());
            }

            // 6) 生成快照 + insert WAITING（锁内，确保两个并发请求不会都通过 count-check）
            TaskSnapshot snap = snapshotFactory.snapshot(taskId, actor);
            long runId = repository.insertWaiting(taskId, draft.ownerId(), snap);

            // 7) 通知派发器（事件源 #1：WAITING 创建）
            signaler.scheduleDispatch();

            LOG.info("run start: runId={} taskId={} runOwner={} actor={}",
                    runId, taskId, draft.ownerId(), actor.value());
            RunRepository.RunRecord rec = repository.findById(runId).orElseThrow();
            return toSummary(rec);
        } finally {
            startLock.unlock();
        }
    }

    @Override
    public void cancel(long runId, ActorId actor) {
        RunRepository.RunRecord rec = repository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));
        // cancel 语义：admin 全局可取消；非 admin 必须 owner；非 owner 拒 RunNotOwnerException
        // （与 get 的 RunNotFoundException 不同，cancel 是变更意图，不应被静默吃掉）
        if (!identityAccess.isAdmin() && rec.ownerId() != actor.value()) {
            throw new RunNotOwnerException(runId);
        }
        RunState state = rec.status();
        if (state == RunState.SUCCESS || state == RunState.FAILED
                || state == RunState.CANCELLED || state == RunState.INTERRUPTED) {
            throw new RunNotCancellableException(runId, state);
        }
        if (state == RunState.WAITING) {
            // 排队中取消：直接翻 CANCELLED + finished_at=now（spec §D10 迁移图 WAITING -> CANCELLED）
            int rows = repository.markCancelledIfWaiting(runId);
            if (rows == 0) {
                throw new RunNotFoundException(runId);
            }
            LOG.info("run cancel: runId={} actor={} (was WAITING)", runId, actor.value());
            return;
        }
        boolean ok = repository.markCancelRequested(runId);
        if (!ok) {
            throw new RunNotFoundException(runId);
        }
        LOG.info("run cancel: runId={} actor={} (RUNNING; flag set)", runId, actor.value());
    }

    @Override
    public RunDetail get(long runId, ActorId actor) {
        RunRepository.RunRecord rec = repository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));
        if (!canRead(rec, actor)) {
            throw new RunNotFoundException(runId);
        }
        return repository.loadDetail(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));
    }

    @Override
    public Page<RunSummary> list(ActorId actor, RunFilter filter) {
        Long ownerId = identityAccess.isAdmin() ? null : actor.value();
        return repository.pageByOwner(ownerId, filter);
    }

    @Override
    public RunProgress progress(long runId, ActorId actor) {
        RunRepository.RunRecord rec = repository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));
        if (!canRead(rec, actor)) {
            throw new RunNotFoundException(runId);
        }
        return repository.loadProgress(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));
    }

    /** 读权限：admin 全局；其它要求 owner 匹配（不回显存在性，不暴露给非 owner）。 */
    private boolean canRead(RunRepository.RunRecord rec, ActorId actor) {
        if (identityAccess.isAdmin()) {
            return true;
        }
        return rec.ownerId() == actor.value();
    }

    private RunSummary toSummary(RunRepository.RunRecord r) {
        return new RunSummary(r.runId(), r.taskId(), r.ownerId(), r.status(),
                r.stopReason(), r.cancelRequested(), r.pageCount(),
                r.recordCountFinal(), r.failCount(),
                r.createdAt(), r.startedAt(), r.finishedAt());
    }

    /**
     * 派发信号接口：{@link RunCoordinator} 创建 WAITING / 终态后通知 {@code RunDispatcher}
     * 触发派发。本接口解耦 coordinator 与 dispatcher 的依赖方向（coordinator 不直接 import
     * dispatcher 内部类）；测试可注入 no-op 实现。
     */
    public interface DispatchSignaler {
        void scheduleDispatch();
    }

    private static final DispatchSignaler NOOP_SIGNALER = () -> { };
}