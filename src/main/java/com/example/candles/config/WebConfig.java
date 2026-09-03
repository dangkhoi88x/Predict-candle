package com.example.candles.config;

import com.example.candles.api.AdminRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Everything under /static is served with "no-cache" (see application.yaml): kept by the
 * browser, but revalidated on every request so an edited file is never missed.
 *
 * Fonts are the one exception worth making. They are the only assets here that genuinely do
 * not change — a new version of a face arrives under a new filename, not as an edit — and
 * there are ten of them, so revalidating each one on every page load costs ten round trips
 * to be told nothing changed. Cache them for a year instead.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminRateLimitInterceptor adminRateLimit;

    public WebConfig(AdminRateLimitInterceptor adminRateLimit) {
        this.adminRateLimit = adminRateLimit;
    }

    /**
     * The admin and media groups get a ceiling by path, so an endpoint added later inherits it
     * without anyone remembering to. The endpoints that reach an external service keep their
     * own tighter, named limits at the call site, where a reader looks for them.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminRateLimit).addPathPatterns("/api/admin/**", "/api/media/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/fonts/**")
                .addResourceLocations("classpath:/static/fonts/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic());
    }
}
