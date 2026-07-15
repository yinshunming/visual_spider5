package com.visualspider.spike.m0;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1 前置检查：确认所选 Playwright 版本提供 {@code Page.screencast()}，为 M0-2 帧传输铺路。
 * 纯反射检查，不启动 Chromium；若该方法不存在，测试失败并提示升级版本。
 */
class ScreencastAvailabilityTest {

    @Test
    void pageScreencastApiIsPresent() {
        Method screencast = null;
        for (Method m : Page.class.getMethods()) {
            if ("screencast".equals(m.getName())) {
                screencast = m;
                break;
            }
        }
        assertThat(screencast)
                .as("Playwright %s 必须提供 Page.screencast()（M0-2 帧传输依赖）",
                        Page.class.getPackage().getImplementationVersion())
                .isNotNull();
    }
}
