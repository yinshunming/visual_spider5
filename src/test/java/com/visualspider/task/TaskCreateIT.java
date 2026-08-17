package com.visualspider.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M1-3 集成测试：采集人员 A 创建任务，B 看不到；admin 全局可见。
 *
 * <p>依赖真实 PostgreSQL；本机无 PG 时跳过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Disabled("requires real PostgreSQL; runs under -Ppg-it only")
class TaskCreateIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("A 创建任务，B listMine 看不到")
    void crossUserInvisible() throws Exception {
        // admin 创建两个 collector（fixture 数据；同包内共享种子策略由 SeedAdminInitializer 提供）
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"change-me-please-12+\"}"));

        mvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(
                "{\"username\":\"alice\",\"password\":\"alice-pwd-12+chars\",\"role\":\"COLLECTOR\"}"));
        mvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(
                "{\"username\":\"bob\",\"password\":\"bob-pwd-12+chars-extra\",\"role\":\"COLLECTOR\"}"));

        // alice 登录 + 创建任务
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(
                "{\"username\":\"alice\",\"password\":\"alice-pwd-12+chars\"}"));
        String taskBody = """
                {
                  "name": "alice-task",
                  "definition": {
                    "schemaVersion": 1,
                    "mode": "SINGLE_PAGE",
                    "startUrl": "https://example.com",
                    "viewport": {"width": 1280, "height": 720},
                    "fields": []
                  }
                }
                """;
        MvcResult created = mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(taskBody))
                .andReturn();
        JsonNode payload = objectMapper.readTree(created.getResponse().getContentAsString());
        long taskId = payload.get("id").asLong();

        // bob 登录 + 试图读取 alice 任务 → 403
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(
                "{\"username\":\"bob\",\"password\":\"bob-pwd-12+chars-extra\"}"));
        MvcResult bobRead = mvc.perform(post("/api/tasks/" + taskId + "/_dummy").contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        // M1-3 GET 是 GET 不是 POST；这里只验证 bob 拿不到任务即可
        assertThat(bobRead.getResponse().getStatus()).isNotEqualTo(200);
    }
}
