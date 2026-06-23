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

    @Test
    void shouldRebuildTenantIsolatedModelDomainInV10() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // V10 后租户隔离的 6 张模型表全部存在
        String[] newTables = {
                "provider_channel_model", "tenant_model", "tenant_model_price",
                "tenant_model_candidate_mapping", "model_route", "route_target"
        };
        for (String table : newTables) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ? AND table_schema = 'public'",
                    Integer.class, table);
            assertThat(count).as("V10 应保留 %s 表", table).isEqualTo(1);
        }
        // V10 已删除全局模型目录两张表
        String[] removed = {"platform_model", "platform_model_price"};
        for (String table : removed) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ? AND table_schema = 'public'",
                    Integer.class, table);
            assertThat(count).as("V10 应已移除 %s 表", table).isEqualTo(0);
        }
        // provider_channel_model 不再保留 platform_model_id 列
        Integer pmIdCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'provider_channel_model' AND column_name = 'platform_model_id'",
                Integer.class);
        assertThat(pmIdCount).as("provider_channel_model 不应再保留 platform_model_id 列").isEqualTo(0);
        // tenant_model 不再保留 platform_model_id 列
        Integer tmPmIdCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'tenant_model' AND column_name = 'platform_model_id'",
                Integer.class);
        assertThat(tmPmIdCount).as("tenant_model 不应再保留 platform_model_id 列").isEqualTo(0);
        // tenant_model 必须存在 model_code（V10 引入的租户内唯一编码）
        Integer modelCodeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'tenant_model' AND column_name = 'model_code'",
                Integer.class);
        assertThat(modelCodeCount).as("tenant_model 应包含 model_code").isEqualTo(1);
        // route_target 引用映射 ID 而不是 channel-model ID
        Integer mappingFkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'route_target' AND column_name = 'tenant_model_candidate_mapping_id'",
                Integer.class);
        assertThat(mappingFkCount).as("route_target 必须引用 tenant_model_candidate_mapping_id").isEqualTo(1);
    }

    @Test
    void shouldReplaceModelPermissionSetInV10() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // V8 引入的旧权限必须全部删除
        Integer oldCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM permission WHERE code IN ("
                        + "'MODEL_CATALOG_READ','MODEL_CATALOG_MANAGE','MODEL_PLATFORM_MANAGE','MODEL_CROSS_TENANT_MANAGE')",
                Integer.class);
        assertThat(oldCount).as("V10 应已移除 4 个旧 MODEL_* 权限").isEqualTo(0);
        // V10 引入 4 个 TENANT_MODEL_* 权限
        Integer newCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM permission WHERE code IN ("
                        + "'TENANT_MODEL_READ','TENANT_MODEL_MANAGE',"
                        + "'TENANT_MODEL_CROSS_TENANT_MANAGE','TENANT_MODEL_PUBLIC_READ')",
                Integer.class);
        assertThat(newCount).as("V10 应注入 4 条 TENANT_MODEL_* 权限").isEqualTo(4);
        // 普通成员只拥有公开目录读取权限
        Integer memberRead = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role r JOIN role_permission rp ON r.id=rp.role_id "
                        + "JOIN permission p ON rp.permission_id=p.id "
                        + "WHERE r.code='TENANT_MEMBER' AND p.code='TENANT_MODEL_PUBLIC_READ'",
                Integer.class);
        assertThat(memberRead).as("TENANT_MEMBER 应可读公开模型目录").isEqualTo(1);
        Integer memberManage = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role r JOIN role_permission rp ON r.id=rp.role_id "
                        + "JOIN permission p ON rp.permission_id=p.id "
                        + "WHERE r.code='TENANT_MEMBER' AND p.code='TENANT_MODEL_MANAGE'",
                Integer.class);
        assertThat(memberManage).as("TENANT_MEMBER 不应拥有 TENANT_MODEL_MANAGE 权限").isEqualTo(0);
    }
}
