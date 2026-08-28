package com.example.candles.config;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CloudinaryConfig {

    @Bean
    public Cloudinary cloudinary(MediaProperties properties) {
        MediaProperties.Cloudinary cloudinary = properties.cloudinary();
        return new Cloudinary(ObjectUtils.asMap(
                "cloud_name", cloudinary.cloudName(),
                "api_key", cloudinary.apiKey(),
                "api_secret", cloudinary.apiSecret(),
                "secure", true));
    }
}
