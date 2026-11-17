package com.voyager.sesmatcher.config;

import com.voyager.sesmatcher.service.AuditLogService;
import com.voyager.sesmatcher.service.OAuthSessionTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final OAuthSessionTokenService oauthSessionTokenService;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final AuditLogService auditLogService;

    @Value("${ses.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/**",
                                "/api/matches/**",
                                "/api/audit-logs/**",
                                "/oauth2/**",
                                "/login/**",
                                "/h2-console/**",
                                "/error"
                        ).permitAll()
                        .anyRequest().permitAll()
                )
                .oauth2Login(oauth2 -> oauth2
                        .successHandler((request, response, authentication) -> {
                            if (authentication instanceof OAuth2AuthenticationToken oauthToken) {
                                String email = oauthToken.getPrincipal().getAttribute("email");
                                String name = oauthToken.getPrincipal().getAttribute("name");

                                if (!oauthSessionTokenService.isAllowedDomain(email)) {
                                    log.warn("⛔ Rejected OAuth2 login from non-corporate domain: {}", email);
                                    authorizedClientService.removeAuthorizedClient(
                                            oauthToken.getAuthorizedClientRegistrationId(),
                                            oauthToken.getName()
                                    );
                                    oauthSessionTokenService.logout(request.getSession(false));
                                    auditLogService.recordLogForUser(
                                            email,
                                            name,
                                            "ログイン拒否（ドメイン制限）",
                                            "@voyager-inc.com 以外のアカウントからのログインを拒否しました"
                                    );
                                    response.sendRedirect(frontendUrl + "?authError=domain_restricted");
                                    return;
                                }

                                OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
                                        oauthToken.getAuthorizedClientRegistrationId(),
                                        oauthToken.getName()
                                );
                                OAuthSessionTokenService.UserOAuthProfile profile = oauthSessionTokenService.registerOAuth2Login(
                                        oauthToken,
                                        client,
                                        request.getSession(true)
                                );
                                auditLogService.recordLogForUser(
                                        profile.getEmail(),
                                        profile.getName(),
                                        "ログイン",
                                        "Google OAuth2 認証によりログインしました"
                                );
                            }
                            response.sendRedirect(frontendUrl);
                        })
                        .failureHandler((request, response, exception) -> {
                            log.error("Google OAuth2 Login failed: {}", exception.getMessage());
                            response.sendRedirect(frontendUrl + "?authError=" + exception.getClass().getSimpleName());
                        })
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:4200", "http://127.0.0.1:4200"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
