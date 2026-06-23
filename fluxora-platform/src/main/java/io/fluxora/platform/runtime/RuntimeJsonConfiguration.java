package io.fluxora.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 运行时快照在 Web 与非 Web 上下文都需要一致 JSON 编解码，不能依赖 MVC 自动装配的偶然存在。 */
@Configuration(proxyBeanMethods = false)
public class RuntimeJsonConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ObjectMapper runtimeObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
