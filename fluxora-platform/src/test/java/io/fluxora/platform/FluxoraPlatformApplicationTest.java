package io.fluxora.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证平台服务在本地基础组件不可用时，仍能加载最小运行上下文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FluxoraPlatformApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void loadsApplicationContext() {
        assertThat(applicationContext).isNotNull();
    }
}
