package com.visualspider.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.task.domain.ReadinessReport;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TaskStatus;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.domain.exceptions.StaleTaskVersionException;
import com.visualspider.task.domain.exceptions.TaskNotFoundException;
import com.visualspider.task.spi.TaskReadiness;
import com.visualspider.task.spi.TaskRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * TaskCatalogImpl 单元测试：CRUD + 所有权 + 乐观锁 + readiness。
 */
@ExtendWith(MockitoExtension.class)
class TaskCatalogImplTest {

    @Mock
    private TaskRepository repository;

    @Mock
    private IdentityAccess identityAccess;

    @Mock
    private TaskReadiness readiness;

    private TaskCatalogImpl catalog;

    @BeforeEach
    void setUp() {
        // M5 spec §D3：reader/writer 经 TaskSchemaUpgrader 兜底升级 V2 -> V3。
        catalog = new TaskCatalogImpl(repository, identityAccess, readiness,
                new TaskSchemaUpgrader(null, null));
        lenient().when(readiness.validate(any())).thenReturn(ReadinessReport.success());
    }

    @Test
    @DisplayName("createDraft 写入 repository 并返回 id")
    void createDraft_success() {
        TaskDefinition def = singlePage();
        when(repository.insert(eq(2L), eq("my-task"), any())).thenReturn(100L);

        long id = catalog.createDraft(def, "my-task", new ActorId(2L));
        assertThat(id).isEqualTo(100L);
    }

    @Test
    @DisplayName("createDraft 任务名为空抛 IllegalArgumentException")
    void createDraft_blankName() {
        assertThatThrownBy(() -> catalog.createDraft(singlePage(), "", new ActorId(2L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.createDraft(singlePage(), null, new ActorId(2L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("listMine：admin 列出全部；collector 列出自己")
    void listMine_roleBased() {
        when(identityAccess.canRunAnyTask()).thenReturn(true);
        when(repository.listByOwner(null)).thenReturn(List.of());
        catalog.listMine(new ActorId(1L));
        org.mockito.Mockito.verify(repository).listByOwner(null);

        when(identityAccess.canRunAnyTask()).thenReturn(false);
        when(repository.listByOwner(2L)).thenReturn(List.of());
        catalog.listMine(new ActorId(2L));
        org.mockito.Mockito.verify(repository).listByOwner(2L);
    }

    @Test
    @DisplayName("read 不存在抛 TaskNotFoundException")
    void read_notFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> catalog.read(99L, new ActorId(2L)))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    @DisplayName("read 别人任务抛 AccessDeniedException")
    void read_otherOwner_denied() {
        TaskDraft draft = newTaskDraft(1L, 99L);
        when(repository.findById(1L)).thenReturn(Optional.of(draft));
        when(identityAccess.canAccessTask(99L, new ActorId(2L))).thenReturn(false);

        assertThatThrownBy(() -> catalog.read(1L, new ActorId(2L)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("read 自己任务成功")
    void read_own_success() {
        TaskDraft draft = newTaskDraft(1L, 2L);
        when(repository.findById(1L)).thenReturn(Optional.of(draft));
        when(identityAccess.canAccessTask(2L, new ActorId(2L))).thenReturn(true);

        TaskDraft result = catalog.read(1L, new ActorId(2L));
        assertThat(result.id()).isEqualTo(1L);
    }

    @Test
    @DisplayName("saveDraft 版本不匹配抛 StaleTaskVersionException")
    void saveDraft_versionMismatch() {
        TaskDraft existing = newTaskDraft(1L, 2L, 5L);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(identityAccess.canAccessTask(2L, new ActorId(2L))).thenReturn(true);

        assertThatThrownBy(() -> catalog.saveDraft(1L, singlePage(), 4L, new ActorId(2L)))
                .isInstanceOf(StaleTaskVersionException.class);
    }

    @Test
    @DisplayName("saveDraft 版本匹配 → updateDraft + markReady 成功")
    void saveDraft_success() {
        TaskDraft existing = newTaskDraft(1L, 2L, 0L);
        TaskDraft updated = newTaskDraft(1L, 2L, 1L);
        TaskDraft ready = newTaskDraft(1L, 2L, 2L);
        when(repository.findById(1L)).thenReturn(Optional.of(existing), Optional.of(updated), Optional.of(ready));
        when(identityAccess.canAccessTask(2L, new ActorId(2L))).thenReturn(true);
        when(repository.updateDraft(eq(1L), any(), eq(0L))).thenReturn(true);
        when(repository.markReady(eq(1L), eq(1L))).thenReturn(true);

        TaskDraft result = catalog.saveDraft(1L, singlePage(), 0L, new ActorId(2L));
        // markReady 再 bump 一次 → 0 → 1 → 2
        assertThat(result.version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("saveDraft UPDATE 0 行（并发）→ StaleTaskVersionException")
    void saveDraft_concurrent_update_returnsZeroRows() {
        TaskDraft existing = newTaskDraft(1L, 2L, 0L);
        TaskDraft concurrent = newTaskDraft(1L, 2L, 3L);
        when(repository.findById(1L)).thenReturn(Optional.of(existing), Optional.of(concurrent));
        when(identityAccess.canAccessTask(2L, new ActorId(2L))).thenReturn(true);
        when(repository.updateDraft(eq(1L), any(), eq(0L))).thenReturn(false);

        assertThatThrownBy(() -> catalog.saveDraft(1L, singlePage(), 0L, new ActorId(2L)))
                .isInstanceOf(StaleTaskVersionException.class);
    }

    @Test
    @DisplayName("delete 别人任务抛 AccessDeniedException")
    void delete_otherOwner_denied() {
        TaskDraft existing = newTaskDraft(1L, 99L);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(identityAccess.canAccessTask(99L, new ActorId(2L))).thenReturn(false);

        assertThatThrownBy(() -> catalog.delete(1L, new ActorId(2L)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("delete collector 删自己任务成功")
    void delete_selfAsCollector() {
        TaskDraft existing = newTaskDraft(1L, 2L);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(identityAccess.canAccessTask(2L, new ActorId(2L))).thenReturn(true);
        when(identityAccess.canRunAnyTask()).thenReturn(false);
        when(repository.deleteById(1L, 2L)).thenReturn(true);

        catalog.delete(1L, new ActorId(2L));
        org.mockito.Mockito.verify(repository).deleteById(1L, 2L);
    }

    @Test
    @DisplayName("delete admin 删任意任务")
    void delete_admin() {
        TaskDraft existing = newTaskDraft(1L, 99L);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(identityAccess.canAccessTask(99L, new ActorId(1L))).thenReturn(true);
        when(identityAccess.canRunAnyTask()).thenReturn(true);
        when(repository.deleteById(eq(1L), anyLong())).thenReturn(true);

        catalog.delete(1L, new ActorId(1L));
        org.mockito.Mockito.verify(repository).deleteById(eq(1L), anyLong());
    }

    private static TaskDefinition singlePage() {
        return new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, null, List.of());
    }

    private static TaskDraft newTaskDraft(long id, long ownerId) {
        return newTaskDraft(id, ownerId, 0L);
    }

    private static TaskDraft newTaskDraft(long id, long ownerId, long version) {
        return new TaskDraft(id, ownerId, "name", new TaskMode.SinglePage(),
                TaskStatus.DRAFT, 1, version, singlePage(), OffsetDateTime.now());
    }
}
