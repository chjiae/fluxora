package io.fluxora.platform.migration;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class FlywayMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("fluxora")
            .withUsername("fluxora")
            .withPassword("fluxora");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldCreateSixCoreTables() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String[] tables = {"tenant", "user_account", "role", "permission", "user_role", "role_permission"};
        for (String table : tables) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ? AND table_schema = 'public'",
                    Integer.class, table);
            assertThat(count).as("表 %s 应存在", table).isEqualTo(1);
        }
    }

    @Test
    void shouldHaveTenantCodeUniqueIndex() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'uk_tenant_tenant_code'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void shouldHaveLogicalDeleteColumnOnTenant() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // V4 迁移已将 tenant.is_deleted BOOLEAN 改造为 tenant.deleted_at TIMESTAMPTZ
        Integer deletedAtCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'tenant' AND column_name = 'deleted_at'",
                Integer.class);
        assertThat(deletedAtCount).as("tenant 表应包含 deleted_at 软删除字段").isEqualTo(1);

        Integer isDeletedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'tenant' AND column_name = 'is_deleted'",
                Integer.class);
        assertThat(isDeletedCount).as("V4 后 tenant.is_deleted 应已被移除").isEqualTo(0);
    }

    @Test
    void shouldHaveLogicalDeleteColumnOnUserAccount() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'user_account' AND column_name = 'deleted_at'",
                Integer.class);
        assertThat(count).as("user_account 表应包含 deleted_at 软删除字段").isEqualTo(1);
    }

    @Test
    void shouldSeedMemberPermissions() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // V4 引入 7 个成员管理细粒度权限
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM permission WHERE code IN ("
                        + "'MEMBER_READ','MEMBER_CREATE','MEMBER_UPDATE',"
                        + "'MEMBER_ENABLE','MEMBER_DISABLE','MEMBER_DELETE','MEMBER_PASSWORD_RESET')",
                Integer.class);
        assertThat(count).as("应注入 7 条 MEMBER_* 权限").isEqualTo(7);
    }

    @Test
    void shouldSeedStaticPermissionsAndRoles() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer permCount = jdbc.queryForObject("SELECT COUNT(*) FROM permission", Integer.class);
        Integer roleCount = jdbc.queryForObject("SELECT COUNT(*) FROM role", Integer.class);
        Integer rpCount = jdbc.queryForObject("SELECT COUNT(*) FROM role_permission", Integer.class);
        assertThat(permCount).isGreaterThanOrEqualTo(3);
        assertThat(roleCount).isGreaterThanOrEqualTo(3);
        assertThat(rpCount).isGreaterThanOrEqualTo(3);
    }
}
