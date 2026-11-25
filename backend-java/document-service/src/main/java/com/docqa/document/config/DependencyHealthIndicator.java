package com.docqa.document.config;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * readiness 对下游依赖的短超时真实探测。
 *
 * <p>与 Python 数据面的 {@code /health/live}（恒活）和 {@code /health/ready}（探真实依赖、
 * down 即 503）同口径：liveness 分组不引用本 indicator，只有 readiness 组引用它；任一依赖
 * 不可达时返回 DOWN，由编排器摘除实例，而不是像旧的恒 200 {@code /health} 那样继续收流量。
 *
 * <p>探测在调用时（而非启动时）执行，并用独立 daemon 线程 + 超时包裹：下游 TCP 半开连接
 * 不能拖住 health endpoint，探针建连也不能把服务启动卡死。失败只回显异常类型，不打 URL /
 * 凭据 / 堆栈（§4.3 结构化日志脱敏）。
 */
public final class DependencyHealthIndicator implements HealthIndicator, AutoCloseable {

    /** 真实依赖探测：抛异常或超时即判 DOWN。 */
    @FunctionalInterface
    public interface Probe {
        void verify() throws Exception;
    }

    /** 与 Python {@code /health/ready} 的 {@code wait_for(timeout=0.5)} 对齐。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(500);

    private final String dependency;
    private final boolean enabled;
    private final long timeoutMs;
    private final Probe probe;
    private final AutoCloseable resource;
    private final ExecutorService executor;

    public DependencyHealthIndicator(
            String dependency, boolean enabled, Duration timeout, Probe probe, AutoCloseable resource) {
        this.dependency = dependency;
        this.enabled = enabled;
        this.timeoutMs = Math.max(1L, timeout.toMillis());
        this.probe = probe;
        this.resource = resource;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "health-" + dependency);
            thread.setDaemon(true);
            return thread;
        });
    }

    public DependencyHealthIndicator(String dependency, boolean enabled, Probe probe) {
        this(dependency, enabled, DEFAULT_TIMEOUT, probe, null);
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.up()
                    .withDetail("dependency", dependency)
                    .withDetail("status", "disabled")
                    .build();
        }
        Future<Void> future = executor.submit(() -> {
            probe.verify();
            return null;
        });
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return Health.up().withDetail("dependency", dependency).build();
        } catch (TimeoutException ex) {
            future.cancel(true);
            return Health.down()
                    .withDetail("dependency", dependency)
                    .withDetail("reason", "timeout")
                    .build();
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            future.cancel(true);
            return Health.down()
                    .withDetail("dependency", dependency)
                    .withDetail("reason", "unreachable")
                    .withDetail("errorType", rootCauseType(ex))
                    .build();
        }
    }

    /** 只回显最内层异常的简单类名，绝不把 message（常含地址 / 桶名 / 凭据）带进响应。 */
    private static String rootCauseType(Throwable error) {
        Throwable current = error;
        for (int depth = 0;
                depth < 8 && current.getCause() != null && current.getCause() != current;
                depth += 1) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    @Override
    public void close() {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // 关停失败不该阻断容器销毁；与 RocketMqAdminOffsetReader.close() 同语义。
            }
        }
        executor.shutdownNow();
    }
}
