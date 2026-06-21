package io.fluxora.platform.identity;

import io.fluxora.platform.identity.entity.Role;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.identity.mapper.IdentityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SystemInitializer {

    private static final Logger log = LoggerFactory.getLogger(SystemInitializer.class);

    private final IdentityMapper identityMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${fluxora.init.admin.username:admin}")
    private String adminUsername;

    @Value("${fluxora.init.admin.password:admin123}")
    private String adminPassword;

    public SystemInitializer(IdentityMapper identityMapper, PasswordEncoder passwordEncoder) {
        this.identityMapper = identityMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 应用启动后幂等初始化平台管理员。
     * 如果管理员已存在则跳过，不会重置已有密码或覆盖角色分配。
     * 密码仅通过 BCrypt 哈希存储，不会打印到日志或返回给接口。
     * 管理员用户名和初始密码可通过环境变量 INIT_ADMIN_USERNAME / INIT_ADMIN_PASSWORD 覆盖。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initializeAdminUser() {
        if (identityMapper.existsByUsername(adminUsername)) {
            log.info("平台管理员 {} 已存在，跳过初始化", adminUsername);
            return;
        }

        log.info("开始初始化平台管理员 {}", adminUsername);

        UserAccount admin = new UserAccount();
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setDisplayName("平台管理员");
        admin.setScopeType("PLATFORM");
        admin.setEnabled(true);
        identityMapper.insertUser(admin);

        Role adminRole = identityMapper.findRoleByCode("PLATFORM_ADMIN")
                .orElseThrow(() -> new IllegalStateException("PLATFORM_ADMIN 角色未在 Flyway 迁移中创建"));
        identityMapper.insertUserRole(admin.getId(), adminRole.getId());

        log.info("平台管理员 {} 初始化完成", adminUsername);
    }
}
