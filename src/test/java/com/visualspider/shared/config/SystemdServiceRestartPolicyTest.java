package com.visualspider.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * systemd unit 文件部署契约测试。
 *
 * <p>M8-2（issue #61）保证 {@code scripts/linux/visual-spider.service} 在 {@code [Service]} 段含
 * {@code RestartPreventExitStatus=137}，使 OOM-killed（exit 137）不触发 {@code Restart=on-failure} 重启，
 * 与 {@code docs/deploy/linux.md §15} + {@code docs/deploy/release-notes-v0.1.0.md §4} 声明一致。
 *
 * <p>本测试在仓库根目录运行（maven 默认），路径相对项目根。
 */
class SystemdServiceRestartPolicyTest {

    private static final Path SERVICE_FILE = Path.of("scripts", "linux", "visual-spider.service");

    /** 抓取 [Service] 段（[Install] 之前的所有行） */
    private static final Pattern SERVICE_SECTION = Pattern.compile(
            "\\[Service\\](.*?)\\[Install\\]",
            Pattern.DOTALL);

    /** 抓取 RestartPreventExitStatus=137 行（允许前置空格与行尾注释） */
    private static final Pattern OOM_PREVENT_LINE = Pattern.compile(
            "^\\s*RestartPreventExitStatus\\s*=\\s*137\\s*(?:#.*)?$",
            Pattern.MULTILINE);

    @Test
    @DisplayName("visual-spider.service 在 [Service] 段含 RestartPreventExitStatus=137")
    void oomExclusionIsInServiceSection() throws IOException {
        assertThat(Files.exists(SERVICE_FILE))
                .as("service file location: %s", SERVICE_FILE.toAbsolutePath())
                .isTrue();

        String content = Files.readString(SERVICE_FILE);

        var sectionMatcher = SERVICE_SECTION.matcher(content);
        assertThat(sectionMatcher.find())
                .as("must contain a [Service] section before [Install]")
                .isTrue();
        String serviceSection = sectionMatcher.group(1);

        assertThat(OOM_PREVENT_LINE.matcher(serviceSection).find())
                .as("[Service] section must contain 'RestartPreventExitStatus=137' (M8-2 / #61)")
                .isTrue();
    }

    @Test
    @DisplayName("Restart=on-failure 仍存在（OOM 排除但其他失败仍触发重启）")
    void restartOnFailureStillPresent() throws IOException {
        String content = Files.readString(SERVICE_FILE);

        var sectionMatcher = SERVICE_SECTION.matcher(content);
        assertThat(sectionMatcher.find()).isTrue();
        String serviceSection = sectionMatcher.group(1);

        assertThat(serviceSection)
                .as("[Service] must still declare Restart=on-failure for non-OOM failures")
                .contains("Restart=on-failure");
    }
}