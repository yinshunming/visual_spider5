package com.visualspider.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.domain.ActorRole;
import com.visualspider.identity.domain.UserAccount;
import com.visualspider.identity.domain.UserStatus;
import com.visualspider.identity.domain.exceptions.NotAuthenticatedException;
import com.visualspider.identity.spi.AppUserRepository;
import com.visualspider.shared.security.ActorAuthentication;
import com.visualspider.shared.security.ActorPrincipal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * IdentityAccessImpl 单元测试。
 *
 * <p>覆盖：未认证抛异常；admin 通过；owner 通过；其它 collector 拒绝；自访问通过；
 * disabled 账号拒绝。
 */
@ExtendWith(MockitoExtension.class)
class IdentityAccessImplTest {

    @Mock
    private AppUserRepository userRepository;

    private IdentityAccessImpl access;

    @BeforeEach
    void setUp() {
        access = new IdentityAccessImpl(userRepository);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("未认证 currentActor 抛 NotAuthenticatedException")
    void currentActor_unauthenticated_throws() {
        assertThatThrownBy(access::currentActor).isInstanceOf(NotAuthenticatedException.class);
    }

    @Test
    @DisplayName("admin 可访问任意 task owner")
    void adminCanAccessAnyTask() {
        setPrincipal(1L, "admin", new ActorRole.Admin(), UserStatus.ACTIVE);
        assertThat(access.canAccessTask(99L, new ActorId(1L))).isTrue();
    }

    @Test
    @DisplayName("owner 可访问自己的 task")
    void ownerCanAccessOwnTask() {
        setPrincipal(2L, "alice", new ActorRole.Collector(), UserStatus.ACTIVE);
        assertThat(access.canAccessTask(2L, new ActorId(2L))).isTrue();
    }

    @Test
    @DisplayName("其它 collector 拒绝访问别人的 task")
    void otherCollectorCannotAccessTask() {
        setPrincipal(2L, "alice", new ActorRole.Collector(), UserStatus.ACTIVE);
        assertThat(access.canAccessTask(3L, new ActorId(2L))).isFalse();
    }

    @Test
    @DisplayName("未认证 canAccessTask 拒绝")
    void unauthenticatedCannotAccessTask() {
        assertThat(access.canAccessTask(1L, new ActorId(99L))).isFalse();
    }

    @Test
    @DisplayName("disabled 账号拒绝访问 task")
    void disabledCannotAccessTask() {
        setPrincipal(2L, "alice", new ActorRole.Collector(), UserStatus.DISABLED);
        assertThat(access.canAccessTask(2L, new ActorId(2L))).isFalse();
    }

    @Test
    @DisplayName("admin 可访问任意 user")
    void adminCanAccessAnyUser() {
        setPrincipal(1L, "admin", new ActorRole.Admin(), UserStatus.ACTIVE);
        assertThat(access.canAccessUser(99L, new ActorId(1L))).isTrue();
    }

    @Test
    @DisplayName("自己可访问自己 user 资源")
    void selfCanAccessSelf() {
        setPrincipal(2L, "alice", new ActorRole.Collector(), UserStatus.ACTIVE);
        assertThat(access.canAccessUser(2L, new ActorId(2L))).isTrue();
    }

    @Test
    @DisplayName("其它 collector 拒绝访问别人 user")
    void otherCollectorCannotAccessUser() {
        setPrincipal(2L, "alice", new ActorRole.Collector(), UserStatus.ACTIVE);
        assertThat(access.canAccessUser(3L, new ActorId(2L))).isFalse();
    }

    @Test
    @DisplayName("canRunAnyTask：admin 通过；collector 拒绝")
    void canRunAnyTaskRoleCheck() {
        setPrincipal(1L, "admin", new ActorRole.Admin(), UserStatus.ACTIVE);
        assertThat(access.canRunAnyTask()).isTrue();

        setPrincipal(2L, "alice", new ActorRole.Collector(), UserStatus.ACTIVE);
        assertThat(access.canRunAnyTask()).isFalse();
    }

    @Test
    @DisplayName("isAdmin 未认证返回 false")
    void isAdminUnauthenticatedFalse() {
        assertThat(access.isAdmin()).isFalse();
    }

    private void setPrincipal(long actorId, String username, ActorRole role, UserStatus status) {
        ActorPrincipal principal = new ActorPrincipal(new ActorId(actorId), username, role);
        SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(principal));
        // lenient：canRunAnyTask 不读 repository
        org.mockito.Mockito.lenient().when(userRepository.findById(actorId)).thenReturn(Optional.of(
                new UserAccount(new ActorId(actorId), username, role, status)));
    }
}
