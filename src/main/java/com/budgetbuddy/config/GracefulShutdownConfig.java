package com.budgetbuddy.config;

import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.HashSet;

/**
 * Graceful Shutdown Configuration
 * Ensures clean shutdown of the application without dropping requests
 *
 * Features:
 * - Graceful thread pool shutdown
 * - Configurable timeout
 * - Proper resource cleanup
 */
@Configuration
public class GracefulShutdownConfig {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownConfig.class);
    private static final int TIMEOUT_SECONDS = 30;

    @Bean
    public ServletWebServerFactory servletContainer(final GracefulShutdown gracefulShutdown) {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.addConnectorCustomizers(gracefulShutdown);
        return factory;
    }

    @Bean
    public GracefulShutdown gracefulShutdown() {
        return new GracefulShutdown();
    }

    /**
     * Graceful shutdown handler
     */
    public static class GracefulShutdown implements TomcatConnectorCustomizer, ApplicationListener<ContextClosedEvent> {
        private volatile Connector connector;

        @Override
        public void customize(final Connector connector) {
            this.connector = connector;
        }

        @Override
        public void onApplicationEvent(final ContextClosedEvent event) {
            if (this.connector == null) {
                log.debug("Connector is null, skipping graceful shutdown");
                return;
            }

            log.info("Starting graceful shutdown...");
            this.connector.pause();
            Executor executor = this.connector.getProtocolHandler().getExecutor();

            // JDK 25: Enhanced pattern matching
            if (executor instanceof ThreadPoolExecutor threadPoolExecutor) {
                shutdownThreadPool(threadPoolExecutor);
            } else {
                log.warn("Executor is not a ThreadPoolExecutor, cannot perform graceful shutdown");
            }

            // Wait for AWS SDK threads to terminate
            waitForAwsSdkThreads();
        }

        /**
         * Shutdown thread pool gracefully
         */
        private void shutdownThreadPool(final ThreadPoolExecutor threadPoolExecutor) {
            try {
                log.info("Shutting down thread pool gracefully...");
                threadPoolExecutor.shutdown();

                if (!threadPoolExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("Thread pool did not shut down gracefully within {} seconds. Proceeding with forceful shutdown",
                            TIMEOUT_SECONDS);
                    threadPoolExecutor.shutdownNow();

                    if (!threadPoolExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        log.error("Thread pool did not terminate after forceful shutdown");
                    } else {
                        log.info("Thread pool terminated after forceful shutdown");
                    }
                } else {
                    log.info("Thread pool shut down gracefully");
                }
            } catch (InterruptedException ex) {
                log.error("Interrupted during graceful shutdown", ex);
                threadPoolExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Wait for AWS SDK internal threads to terminate
         * AWS SDK v2 creates threads like "sdk-ScheduledExecutor-0-*" and "idle-connection-reaper"
         * These should be cleaned up when clients are closed, but we give them a moment
         */
        private void waitForAwsSdkThreads() {
            try {
                log.info("Waiting for AWS SDK threads to terminate...");
                Thread.sleep(2000); // Give AWS SDK threads 2 seconds to clean up

                // Check for remaining AWS SDK threads
                Set<Thread> awsThreads = findAwsSdkThreads();
                if (!awsThreads.isEmpty()) {
                    log.warn("Found {} AWS SDK threads still running after shutdown. They should terminate automatically.",
                            awsThreads.size());
                    for (Thread thread : awsThreads) {
                        log.debug("AWS SDK thread still running: {} (state: {})", thread.getName(), thread.getState());
                    }
                } else {
                    log.info("All AWS SDK threads terminated successfully");
                }
            } catch (InterruptedException ex) {
                log.warn("Interrupted while waiting for AWS SDK threads", ex);
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Find AWS SDK-related threads that might still be running
         */
        private Set<Thread> findAwsSdkThreads() {
            Set<Thread> awsThreads = new HashSet<>();
            ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
            while (rootGroup.getParent() != null) {
                rootGroup = rootGroup.getParent();
            }

            Thread[] threads = new Thread[rootGroup.activeCount() * 2];
            int count = rootGroup.enumerate(threads, true);

            for (int i = 0; i < count; i++) {
                Thread thread = threads[i];
                if (thread != null && isAwsSdkThread(thread)) {
                    awsThreads.add(thread);
                }
            }

            return awsThreads;
        }

        /**
         * Check if a thread is an AWS SDK internal thread
         */
        private boolean isAwsSdkThread(Thread thread) {
            String name = thread.getName();
            return name != null && (
                    name.startsWith("sdk-") ||
                    name.startsWith("idle-connection-reaper") ||
                    name.contains("aws-sdk") ||
                    name.contains("ApacheHttpClient")
            );
        }
    }
}
