package io.fluxora.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Fluxora 控制面的应用入口。
 *
 * <p>当前仅负责提供可观测的应用骨架；数据持久化和 Redis 运行时能力将在
 * 对应业务模块落地时启用。</p>
 */
@SpringBootApplication
public class FluxoraPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(FluxoraPlatformApplication.class, args);
    }
}
