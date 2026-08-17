package com.visualspider.identity;

import com.visualspider.identity.domain.ActorRole;
import com.visualspider.identity.domain.UserStatus;
import com.visualspider.identity.spi.AppUserRepository;
import com.visualspider.shared.config.SeedAdminProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Arrays;

/**
 * 启动时把 {@code seed.admin.*} 写入 {@code app_user} 表。
 *
 * <p>M1-1 已校验 seed 配置存在与最小长度；本类负责：
 * <ol>
 *   <li>查询是否已存在同名 username；存在则跳过（log WARN）</li>
 *   <li>不存在则 BCrypt(cost=12) 哈希明文密码后 INSERT</li>
 *   <li>哈希后立即 {@code Arrays.fill('\0')} 清零 char[]</li>
 * </ol>
 *
 * <p>仅在 {@code it} 或 {@code prod} profile 激活时执行；测试 / dev 默认跳过避免无 DB 时阻塞。
 * M1-2 IT 显式启用 {@code @ActiveProfiles("it")} 触发。
 *
 * <p>不抛异常覆盖已有账号：避免运维误重启时覆盖密码。
 */
@Configuration
@Profile({"it", "prod", "dev"})
public class SeedAdminInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(SeedAdminInitializer.class);

    @Bean
    public ApplicationRunner seedAdminRunner(
            SeedAdminProperties properties,
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        return args -> {
            String username = properties.username();
            char[] rawPassword = properties.rawPassword().toCharArray();
            try {
                if (userRepository.findByUsername(username).isPresent()) {
                    LOG.warn("seed admin already present: username={}; skip insert", username);
                    return;
                }
                String hash = passwordEncoder.encode(new String(rawPassword));
                try {
                    long id = userRepository.insert(username, hash, new ActorRole.Admin(), UserStatus.ACTIVE);
                    LOG.info("seed admin inserted: id={} username={}", id, username);
                } catch (DuplicateKeyException e) {
                    // 并发场景下另一个节点已写入；以 WARN 记录
                    LOG.warn("seed admin already inserted by another node: username={}", username);
                }
            } finally {
                Arrays.fill(rawPassword, '\0');
            }
        };
    }
}
