package com.luke.capability.access;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the capability gatekeeper onto the forms routes. The forms feature is
 * guarded by the "FORMS" capability: GET needs read, mutations need read-write.
 *
 * <p>A second {@link WebMvcConfigurer} alongside the CORS one — Spring composes
 * them. New capabilities register their own guarded path prefixes here.
 */
@Configuration
public class AccessWebConfig implements WebMvcConfigurer {

    private final CapabilityAccessService access;

    public AccessWebConfig(CapabilityAccessService access) {
        this.access = access;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CapabilityAccessInterceptor(access).forCapability("FORMS"))
                .addPathPatterns("/api/form-definitions/**", "/api/form-instances/**");
    }
}
