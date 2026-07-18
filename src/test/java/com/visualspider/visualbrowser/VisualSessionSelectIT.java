package com.visualspider.visualbrowser;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 选择模式集成测试：坐标命中预期元素、选择模式不触发原页面动作、动态重渲染后不依赖陈旧 handle。
 *
 * <p>需要 Chromium 已安装。每测试独立 VisualSession/BrowserContext。坐标命中/换算纯逻辑由
 * ViewportMapper 单测覆盖；本测试验证 elementFromPoint 重新查询（不保存 ElementHandle）端到端。
 */
class VisualSessionSelectIT {
    private static final long TIMEOUT_SECONDS = 15;

    @Test
    void selectReturnsElementAtCoordinate() throws Exception {
        URL resource = getClass().getResource("/fixtures/static.html");
        assertThat(resource).isNotNull();
        try (VisualSession session = new VisualSession("s1", resource.toURI().toString())) {
            double[] center = session.control().elementCenter("#input")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int x = (int) center[0];
            int y = (int) center[1];

            boolean accepted = session.handle(new InputCommand("s1", 1, 1280, 720,
                    InputCommand.TYPE_SELECT, x, y, null, null, null, null));
            assertThat(accepted).isTrue();

            SelectionRecord sel = session.status().selection();
            assertThat(sel).isNotNull();
            assertThat(sel.tagName()).isEqualTo("INPUT");
        }
    }

    @Test
    void selectDoesNotTriggerOriginalPageAction() throws Exception {
        URL resource = getClass().getResource("/fixtures/static.html");
        assertThat(resource).isNotNull();
        try (VisualSession session = new VisualSession("s1", resource.toURI().toString())) {
            double[] btn = session.control().elementCenter("#submit-btn")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int bx = (int) btn[0];
            int by = (int) btn[1];

            // 选择模式点击提交按钮：不应触发原页面动作（#output 保持空）
            session.handle(new InputCommand("s1", 1, 1280, 720,
                    InputCommand.TYPE_SELECT, bx, by, null, null, null, null));
            assertThat(session.control().textContent("#output")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNullOrEmpty();

            // 浏览模式点击同一按钮：应触发原页面动作（#output 更新）
            session.handle(new InputCommand("s1", 2, 1280, 720,
                    InputCommand.TYPE_CLICK, bx, by, null, null, null, null));
            assertThat(session.control().textContent("#output")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).contains("已输入：");
        }
    }

    @Test
    void selectSurvivesDynamicRerenderWithoutStaleHandle() throws Exception {
        URL resource = getClass().getResource("/fixtures/dynamic.html");
        assertThat(resource).isNotNull();
        try (VisualSession session = new VisualSession("s1", resource.toURI().toString())) {
            double[] center = session.control().elementCenter("#counter")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int x = (int) center[0];
            int y = (int) center[1];

            session.handle(new InputCommand("s1", 1, 1280, 720,
                    InputCommand.TYPE_SELECT, x, y, null, null, null, null));
            assertThat(session.status().selection().tagName()).isEqualTo("H1");

            // 动态页持续重渲染，等待后再次 select 同坐标：elementFromPoint 重新查询，不依赖陈旧 handle
            Thread.sleep(400);
            session.handle(new InputCommand("s1", 2, 1280, 720,
                    InputCommand.TYPE_SELECT, x, y, null, null, null, null));
            assertThat(session.status().selection().tagName()).isEqualTo("H1");
        }
    }
}
