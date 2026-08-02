package com.visualspider.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TaskSnapshot;
import com.visualspider.task.domain.TaskStatus;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.domain.exceptions.TaskNotFoundException;
import com.visualspider.task.spi.TaskCatalog;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * JdbcTaskSnapshotFactory 单元测试：替换 M1-3 占位实现（M3 spec §D4）。
 *
 * <p>覆盖：快照含完整 definition + version；非 owner/admin 拒绝；任务不存在抛错；
 * 后续修改 task 不影响已生成快照。
 */
@ExtendWith(MockitoExtension.class)
class JdbcTaskSnapshotFactoryTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Mock
    private TaskCatalog catalog;

    @Mock
    private IdentityAccess identityAccess;

    @Test
    @DisplayName("snapshot 含完整 TaskDefinition + version")
    void snapshotContainsFullDefinitionAndVersion() {
        TaskDefinition def = singlePage();
        TaskDraft draft = new TaskDraft(7L, 2L, "demo",
                new TaskMode.SinglePage(), TaskStatus.READY, 1, 5L, def, OffsetDateTime.now());
        when(catalog.read(7L, new ActorId(2L))).thenReturn(draft);
        when(identityAccess.canAccessTask(2L, new ActorId(2L))).thenReturn(true);

        JdbcTaskSnapshotFactory factory = new JdbcTaskSnapshotFactory(catalog, identityAccess, mapper);
        TaskSnapshot snap = factory.snapshot(7L, new ActorId(2L));

        assertThat(snap.taskId()).isEqualTo(7L);
        assertThat(snap.ownerId()).isEqualTo(2L);
        assertThat(snap.name()).isEqualTo("demo");
        assertThat(snap.mode()).isInstanceOf(TaskMode.SinglePage.class);
        assertThat(snap.schemaVersion()).isEqualTo(1);
        assertThat(snap.version()).isEqualTo(5L);
        assertThat(snap.definition()).isEqualTo(def);
        assertThat(snap.definition().fields()).hasSize(1);
        assertThat(snap.definition().fields().get(0).name()).isEqualTo("title");
    }

    @Test
    @DisplayName("非 owner / 非 admin 调用 → AccessDeniedException")
    void nonOwnerDenied() {
        TaskDraft draft = new TaskDraft(7L, 99L, "demo",
                new TaskMode.SinglePage(), TaskStatus.READY, 1, 5L,
                singlePage(), OffsetDateTime.now());
        when(catalog.read(7L, new ActorId(2L))).thenReturn(draft);
        when(identityAccess.canAccessTask(99L, new ActorId(2L))).thenReturn(false);

        JdbcTaskSnapshotFactory factory = new JdbcTaskSnapshotFactory(catalog, identityAccess, mapper);
        assertThatThrownBy(() -> factory.snapshot(7L, new ActorId(2L)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("admin 可访问任意任务（canAccessTask=true）")
    void adminCanAccessAnyTask() {
        TaskDraft draft = new TaskDraft(7L, 99L, "demo",
                new TaskMode.SinglePage(), TaskStatus.READY, 1, 5L,
                singlePage(), OffsetDateTime.now());
        when(catalog.read(7L, new ActorId(1L))).thenReturn(draft);
        when(identityAccess.canAccessTask(99L, new ActorId(1L))).thenReturn(true);

        JdbcTaskSnapshotFactory factory = new JdbcTaskSnapshotFactory(catalog, identityAccess, mapper);
        TaskSnapshot snap = factory.snapshot(7L, new ActorId(1L));
        assertThat(snap.taskId()).isEqualTo(7L);
        assertThat(snap.ownerId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("任务不存在 → TaskNotFoundException")
    void missingTaskThrows() {
        when(catalog.read(7L, new ActorId(2L))).thenThrow(new TaskNotFoundException(7L));

        JdbcTaskSnapshotFactory factory = new JdbcTaskSnapshotFactory(catalog, identityAccess, mapper);
        assertThatThrownBy(() -> factory.snapshot(7L, new ActorId(2L)))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    @DisplayName("快照不可变：definition 不可被外部 mutate 改变 snapshot 状态")
    void snapshotIsImmutableReference() {
        // record 不可变 → 直接复用 draft.definition() 引用即可；此处断言返回同一引用，
        // 因为 M3 spec 要求 TaskSnapshotFactory 不深拷贝（record 字段皆不可变）。
        TaskDefinition def = singlePage();
        TaskDraft draft = new TaskDraft(7L, 2L, "demo",
                new TaskMode.SinglePage(), TaskStatus.READY, 1, 5L, def, OffsetDateTime.now());
        when(catalog.read(7L, new ActorId(2L))).thenReturn(draft);
        when(identityAccess.canAccessTask(2L, new ActorId(2L))).thenReturn(true);

        JdbcTaskSnapshotFactory factory = new JdbcTaskSnapshotFactory(catalog, identityAccess, mapper);
        TaskSnapshot snap = factory.snapshot(7L, new ActorId(2L));
        assertThat(snap.definition()).isSameAs(def);
    }

    private static TaskDefinition singlePage() {
        return new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, null,
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1", null,
                        SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true)));
    }
}