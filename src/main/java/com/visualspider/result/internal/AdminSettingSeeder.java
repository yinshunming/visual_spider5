package com.visualspider.result.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 系统设置幂等 seed（M6-6 / docs/specs/m6.md §D14）。
 *
 * <p>启动时写 {@code retention.days = 30} 行（缺则写，已存在则不动）。
 * 修正 V1 migration 注释与实际偏差（注释曾称 M1 seed 一行,实际未 seed）。
 *
 * <p>仅在有 DB 的 profile 下运行（与 SeedAdminInitializer 模式一致），
 * 避免 test profile（无 DB）下阻塞 context 加载。
 */
@Configuration
@Profile({"it", "prod", "dev"})
public class AdminSettingSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(AdminSettingSeeder.class);

    @Bean
    public ApplicationRunner adminSettingSeederRunner(SystemSettingRepository repository) {
        return args -> {
            if (repository.findInt(RetentionCleanupTask.SETTING_KEY).isEmpty()) {
                repository.upsert(RetentionCleanupTask.SETTING_KEY,
                        String.valueOf(RetentionCleanupTask.DEFAULT_RETENTION_DAYS));
                LOG.info("admin setting seeded: {}={}",
                        RetentionCleanupTask.SETTING_KEY,
                        RetentionCleanupTask.DEFAULT_RETENTION_DAYS);
            }
        };
    }
}
