package com.visualspider.shared.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SeedAdminValidator 单元测试。
 *
 * <p>覆盖：缺失 username、缺失 password、password 长度不足（11 字符）触发启动失败；
 * 合法配置不抛异常。空白 username/密码同样视为缺失。
 */
class SeedAdminValidatorTest {

    @Test
    @DisplayName("缺失 username 时启动失败")
    void rejectsMissingUsername() {
        assertThatThrownBy(() -> new SeedAdminValidator(new SeedAdminProperties(null, "longenoughpassword")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed.admin.username");
    }

    @Test
    @DisplayName("缺失 password 时启动失败")
    void rejectsMissingPassword() {
        assertThatThrownBy(() -> new SeedAdminValidator(new SeedAdminProperties("admin", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed.admin.raw-password");
    }

    @Test
    @DisplayName("空白 username 视为缺失")
    void rejectsBlankUsername() {
        assertThatThrownBy(() -> new SeedAdminValidator(new SeedAdminProperties("   ", "longenoughpassword")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed.admin.username");
    }

    @Test
    @DisplayName("password 长度不足 12 字符时启动失败")
    void rejectsShortPassword() {
        // 11 chars: 1,1,c,h,a,r,s,l,o,n,g
        assertThatThrownBy(() -> new SeedAdminValidator(new SeedAdminProperties("admin", "11charslong")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed.admin.raw-password");
    }

    @Test
    @DisplayName("合法配置不抛异常")
    void acceptsValidConfig() {
        assertThatCode(() -> new SeedAdminValidator(new SeedAdminProperties("admin", "longenoughpassword")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("password 含空白 trim 后长度 ≥ 12 仍合法")
    void acceptsPasswordWithSpacesWhenTrimmed() {
        assertThatCode(() -> new SeedAdminValidator(
                new SeedAdminProperties("admin", "  longenoughpassword  ")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("password trim 后不足 12 仍拒绝")
    void rejectsPasswordTrimmedTooShort() {
        assertThatThrownBy(() -> new SeedAdminValidator(
                new SeedAdminProperties("admin", "           "))) // 11 个空白
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed.admin.raw-password");
    }
}
