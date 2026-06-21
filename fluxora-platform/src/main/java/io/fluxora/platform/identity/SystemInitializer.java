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
