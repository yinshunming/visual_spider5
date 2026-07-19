package com.visualspider.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * M1-2 集成测试：admin 登录 → /api/identity/me 返回 admin。
 *
 * <p>依赖真实 PostgreSQL（{@code -Ppg-it -Dpg.it.url=...}）。
 * 本机无 PG 时由 failsafe 自动跳过；{@link Disabled} 显式标注避免 IDE 误以为可通过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Disabled("requires real PostgreSQL; runs under -Ppg-it only")
class LoginFlowIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("admin 登录后 /me 返回 admin 用户")
    void adminLoginThenMe() throws Exception {
        String body = "{\"username\":\"admin\",\"password\":\"change-me-please-12+\"}";
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        MvcResult me = mvc.perform(get("/api/identity/me"))
                .andReturn();
        JsonNode payload = objectMapper.readTree(me.getResponse().getContentAsString());
        assertThat(payload.get("role").asText()).isEqualTo("ADMIN");
    }
}
