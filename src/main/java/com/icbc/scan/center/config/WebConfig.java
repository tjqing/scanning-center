package com.icbc.scan.center.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  @Override
  public void addCorsMappings(CorsRegistry r) {
    r.addMapping("/api/**")
        .allowedOrigins("http://localhost:8081")
        .allowedMethods("GET", "POST", "PUT", "DELETE")
        .allowCredentials(true);
  }

  @Bean(name = "taskExecutor")
  public Executor taskExecutor() {
    ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
    e.setCorePoolSize(2);
    e.setMaxPoolSize(2);
    e.setQueueCapacity(100);
    e.setThreadNamePrefix("scan-worker-");
    e.initialize();
    return e;
  }
}
