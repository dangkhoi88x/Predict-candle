package com.example.candles.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
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

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/fonts/**")
                .addResourceLocations("classpath:/static/fonts/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic());
    }
}
