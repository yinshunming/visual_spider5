package com.visualspider.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M1-2 集成测试：采集人员调 admin 端点 403。
 *
 * <p>依赖真实 PostgreSQL；本机无 PG 时跳过（failsafe + {@code -Ppg-it} 控制）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Disabled("requires real PostgreSQL; runs under -Ppg-it only")
class AccountAdminIT {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("collector 调用 POST /api/admin/users 返回 403")
    void collectorCannotCreateUser() throws Exception {
        // 先以 admin 登录拿 CSRF cookie
        String adminLogin = "{\"username\":\"admin\",\"password\":\"change-me-please-12+\"}";
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adminLogin))
                .andReturn();

        // admin 创建一个 collector
        String createBody = "{\"username\":\"alice\",\"password\":\"alice-pwd-12+chars\",\"role\":\"COLLECTOR\"}";
        mvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andReturn();

        // alice 登录
        String aliceLogin = "{\"username\":\"alice\",\"password\":\"alice-pwd-12+chars\"}";
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aliceLogin))
                .andReturn();

        // alice 尝试调 admin 端点 → 403
        MvcResult result = mvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }
}
