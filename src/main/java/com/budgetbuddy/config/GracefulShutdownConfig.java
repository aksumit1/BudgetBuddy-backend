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
    public ServletWebServerFactory servletContainer(GracefulShutdown gracefulShutdown) {
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
        public void customize(Connector connector) {
            this.connector = connector;
        }

        @Override
        public void onApplicationEvent(ContextClosedEvent event) {
            if (this.connector == null) {
                log.debug("Connector is null, skipping graceful shutdown");
                return;
            }
            
            log.info("Starting graceful shutdown...");
            this.connector.pause();
            Executor executor = this.connector.getProtocolHandler().getExecutor();
            
            if (executor instanceof ThreadPoolExecutor) {
                shutdownThreadPool((ThreadPoolExecutor) executor);
            } else {
                log.warn("Executor is not a ThreadPoolExecutor, cannot perform graceful shutdown");
            }
        }

        /**
         * Shutdown thread pool gracefully
         */
        private void shutdownThreadPool(ThreadPoolExecutor threadPoolExecutor) {
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
    }
}
