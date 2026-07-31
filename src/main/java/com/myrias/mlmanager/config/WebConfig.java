package com.myrias.mlmanager.config;

import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Beans transversais: encoder de senha (BCrypt) e CORS pro frontend Vue.
 * Espelha o add_middleware(CORSMiddleware, ...) do FastAPI.
 */
@Configuration
@EnableAsync        // refresh de snapshot em background (PerfRefreshRunner)
@EnableScheduling   // auditoria noturna de qualidade (QualityJobs)
public class WebConfig implements WebMvcConfigurer {

    /** Atalhos de desenvolvimento: Vite local e o mesmo Vite aberto por IP da LAN
     *  (testar no celular). Com allowCredentials(true) eles não têm o que fazer
     *  no deploy público, então saem quando app.demo-mode está ligado. */
    private static final String[] DEV_ORIGIN_PATTERNS = {
            "http://localhost:5173", "http://192.168.*.*:5173", "http://10.*.*.*:5173"};

    private final String frontendUrl;
    private final boolean demoMode;

    public WebConfig(@Value("${app.frontend-url}") String frontendUrl,
                     @Value("${app.demo-mode:false}") boolean demoMode) {
        this.frontendUrl = frontendUrl;
        this.demoMode = demoMode;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = demoMode
                ? new String[] {frontendUrl}
                : Stream.concat(Stream.of(frontendUrl), Stream.of(DEV_ORIGIN_PATTERNS))
                        .toArray(String[]::new);
        registry.addMapping("/api/**")
                // Patterns (não allowedOrigins) p/ aceitar o Vite por IP da LAN, ex.: http://192.168.3.130:5173
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization", "Cookie")
                .allowCredentials(true);
    }
}
