package com.scan.center.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final OperatorInterceptor operatorInterceptor;

  public WebConfig(OperatorInterceptor operatorInterceptor) {
    this.operatorInterceptor = operatorInterceptor;
  }

  @Override
  public void addCorsMappings(CorsRegistry r) {
    r.addMapping("/api/**")
        .allowedOrigins("http://localhost:8081")
        .allowedMethods("GET", "POST", "PUT", "DELETE")
        .allowedHeaders("*")
        .allowCredentials(true);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(operatorInterceptor).addPathPatterns("/api/**");
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
