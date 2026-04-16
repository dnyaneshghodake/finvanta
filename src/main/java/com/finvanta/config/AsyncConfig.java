package com.finvanta.config;

import com.finvanta.util.TenantContext;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * CBS Async Executor Configuration per Finacle ASYNC_ENGINE / Temenos ASYNC.
 *
 * <p>Wires an executor used by {@code @Async} methods (audit post-commit,
 * notification fan-out, EOD sub-tasks) with a {@link TaskDecorator} that
 * copies the calling thread's CBS context into the worker thread:
 *
 * <ul>
 *   <li>{@link TenantContext} -- the multi-tenant isolation boundary. Without
 *       this, any async DB call would see {@code TENANT_NOT_SET} and silently
 *       leak rows across tenants if the Hibernate {@code @Filter} were ever
 *       bypassed. Defence-in-depth per RBI IT Governance Direction 2023 §8.3.</li>
 *   <li>{@link SecurityContextHolder} -- so {@code SecurityUtil.getCurrentUsername()}
 *       inside an async audit event still resolves to the maker / checker who
 *       initiated the action, not the anonymous worker thread.</li>
 *   <li>{@link MDC} -- propagates structured-logging fields
 *       (correlationId, tenantId, branchCode) into async log lines so audit
 *       trails remain traceable across the thread hop.</li>
 * </ul>
 *
 * <p>Without this decorator, an {@code @Async} audit emit can land without
 * correct tenant / user attribution, breaking the immutable audit chain
 * requirement per RBI IT Framework §8.4.
 *
 * <p><b>Not wired yet:</b> {@code FinvantaApplication} carries
 * {@code @EnableAsync} but no explicit executor; this class provides the
 * missing executor so future {@code @Async} methods inherit tenant-aware
 * propagation automatically.
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /** Thread-pool sizing per Temenos ASYNC.TASK baseline for mid-volume CBS workloads. */
    private static final int CORE_POOL_SIZE = 4;
    private static final int MAX_POOL_SIZE = 16;
    private static final int QUEUE_CAPACITY = 500;

    @Override
    @Bean(name = "cbsAsyncExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("cbs-async-");
        executor.setTaskDecorator(cbsContextTaskDecorator());
        // Per Finacle ASYNC_ENGINE: audit / EOD sub-tasks must not be silently
        // dropped -- CallerRuns back-pressures the submitting thread instead,
        // which preserves the audit chain under burst load.
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Decorator that captures CBS context (tenant, security, MDC) on the submitting
     * thread and re-installs it on the worker thread for the duration of the task.
     *
     * <p><b>Save / restore, not blind clear:</b> the finally block must
     * RESTORE the caller thread's pre-task context, not unconditionally
     * clear it. The executor's rejection handler is
     * {@link java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy},
     * which runs the decorated Runnable on the submitting thread when the
     * pool + queue are saturated. On a worker thread "restore" and "clear"
     * are equivalent (worker starts with empty context), but on the caller
     * thread a blind clear would wipe the caller's own {@link TenantContext},
     * {@link SecurityContextHolder}, and {@link MDC} -- any subsequent DB
     * call on that request thread would fail with {@code TENANT_NOT_SET}
     * and lose audit attribution. Snapshot first, restore in finally.
     */
    @Bean
    public TaskDecorator cbsContextTaskDecorator() {
        return runnable -> {
            String tenantId = TenantContext.isSet()
                    ? TenantContext.getCurrentTenant()
                    : null;
            SecurityContext security = SecurityContextHolder.getContext();
            Map<String, String> mdc = MDC.getCopyOfContextMap();

            return () -> {
                // Snapshot the EXECUTING thread's context so CallerRunsPolicy
                // (which invokes us on the submitting thread under saturation)
                // does not wipe the caller's active tenant / user / MDC.
                String previousTenant = TenantContext.isSet()
                        ? TenantContext.getCurrentTenant()
                        : null;
                SecurityContext previousSecurity = SecurityContextHolder.getContext();
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                try {
                    if (tenantId != null) {
                        TenantContext.setCurrentTenant(tenantId);
                    }
                    if (security != null) {
                        SecurityContextHolder.setContext(security);
                    }
                    if (mdc != null) {
                        MDC.setContextMap(mdc);
                    }
                    runnable.run();
                } finally {
                    // Restore — not clear — so CallerRunsPolicy doesn't
                    // destroy the submitting thread's own context.
                    if (previousMdc != null) {
                        MDC.setContextMap(previousMdc);
                    } else {
                        MDC.clear();
                    }
                    SecurityContextHolder.setContext(previousSecurity);
                    if (previousTenant != null) {
                        TenantContext.setCurrentTenant(previousTenant);
                    } else {
                        TenantContext.clear();
                    }
                }
            };
        };
    }
}
