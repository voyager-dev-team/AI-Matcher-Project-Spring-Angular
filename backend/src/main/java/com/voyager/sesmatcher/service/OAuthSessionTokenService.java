package com.voyager.sesmatcher.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import jakarta.servlet.http.HttpSession;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class OAuthSessionTokenService {

    public static final String SESSION_ATTR_USER = "SES_OAUTH_USER";

    private final OAuth2AuthorizedClientService authorizedClientService;

    @Value("${spring.security.oauth2.client.registration.google.client-id:}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret:}")
    private String clientSecret;

    @Value("${ses.google.application-name:SES-AI-Matcher}")
    private String applicationName;

    @Value("${ses.security.allowed-domain:@voyager-inc.com}")
    private String allowedDomain;

    // In-memory cache of logged-in user sessions (no DB required)
    private final Map<String, UserOAuthProfile> tokenCacheByEmail = new ConcurrentHashMap<>();
    private final AtomicReference<UserOAuthProfile> activeUserRef = new AtomicReference<>(null);

    /**
     * Validates that the user's email belongs to the allowed corporate domain (@voyager-inc.com).
     */
    public boolean isAllowedDomain(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String suffix = (allowedDomain != null && !allowedDomain.isBlank())
                ? allowedDomain.trim().toLowerCase()
                : "@voyager-inc.com";
        if (!suffix.startsWith("@")) {
            suffix = "@" + suffix;
        }
        return email.trim().toLowerCase().endsWith(suffix);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserOAuthProfile {
        private boolean authenticated;
        private String email;
        private String name;
        private String picture;

        @JsonIgnore
        private String accessToken;

        @JsonIgnore
        private String refreshToken;

        @JsonIgnore
        private Long expiresAtEpochMillis;
    }

    @Autowired
    public OAuthSessionTokenService(
            @Autowired(required = false) OAuth2AuthorizedClientService authorizedClientService
    ) {
        this.authorizedClientService = authorizedClientService;
    }

    /**
     * Extracts and stores the OAuth2 user profile and tokens from Authentication / Principal upon Google OAuth2 login.
     */
    public UserOAuthProfile registerOAuth2Login(
            OAuth2AuthenticationToken authToken,
            OAuth2AuthorizedClient authorizedClient,
            HttpSession session
    ) {
        OAuth2User oauth2User = authToken.getPrincipal();
        String email = oauth2User.getAttribute("email");
        String name = oauth2User.getAttribute("name");
        String picture = oauth2User.getAttribute("picture");

        String accessToken = null;
        String refreshToken = null;
        Long expiresAt = null;

        if (authorizedClient != null && authorizedClient.getAccessToken() != null) {
            accessToken = authorizedClient.getAccessToken().getTokenValue();
            Instant exp = authorizedClient.getAccessToken().getExpiresAt();
            if (exp != null) {
                expiresAt = exp.toEpochMilli();
            }
            if (authorizedClient.getRefreshToken() != null) {
                refreshToken = authorizedClient.getRefreshToken().getTokenValue();
            }
        }

        UserOAuthProfile profile = UserOAuthProfile.builder()
                .authenticated(true)
                .email(email)
                .name(name != null ? name : email)
                .picture(picture)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresAtEpochMillis(expiresAt)
                .build();

        saveProfileToSessionAndMemory(profile, session);
        log.info("🔑 Registered Google OAuth2 login for sales rep: {} ({})", profile.getName(), profile.getEmail());
        return profile;
    }

    public void saveProfileToSessionAndMemory(UserOAuthProfile profile, HttpSession session) {
        if (profile == null) return;
        if (session != null) {
            session.setAttribute(SESSION_ATTR_USER, profile);
        }
        if (profile.getEmail() != null) {
            tokenCacheByEmail.put(profile.getEmail(), profile);
        }
        activeUserRef.set(profile);
    }

    /**
     * Resolves the currently logged-in user profile strictly from Authentication / Principal or HttpSession.
     */
    public UserOAuthProfile getCurrentUser(HttpSession session) {
        // 1. Check Spring SecurityContextHolder (Authentication / Principal)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof OAuth2AuthenticationToken oauthToken && oauthToken.isAuthenticated()) {
            String email = oauthToken.getPrincipal().getAttribute("email");
            if (!isAllowedDomain(email)) {
                logout(session);
                return UserOAuthProfile.builder().authenticated(false).build();
            }
            OAuth2AuthorizedClient client = null;
            if (authorizedClientService != null) {
                client = authorizedClientService.loadAuthorizedClient(
                        oauthToken.getAuthorizedClientRegistrationId(),
                        oauthToken.getName()
                );
            }
            return registerOAuth2Login(oauthToken, client, session);
        }

        // 2. Check HttpSession
        if (session != null) {
            Object attr = session.getAttribute(SESSION_ATTR_USER);
            if (attr instanceof UserOAuthProfile profile && profile.isAuthenticated()) {
                if (!isAllowedDomain(profile.getEmail())) {
                    logout(session);
                    return UserOAuthProfile.builder().authenticated(false).build();
                }
                activeUserRef.set(profile);
                return profile;
            }
        }

        return UserOAuthProfile.builder()
                .authenticated(false)
                .build();
    }

    /**
     * Clears the user session and in-memory token cache on logout.
     */
    public void logout(HttpSession session) {
        UserOAuthProfile current = activeUserRef.getAndSet(null);
        if (current != null && current.getEmail() != null) {
            tokenCacheByEmail.remove(current.getEmail());
        }
        if (session != null) {
            session.removeAttribute(SESSION_ATTR_USER);
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
            }
        }
        SecurityContextHolder.clearContext();
        log.info("🚪 User logged out and OAuth session cleared.");
    }

    /**
     * Resolves the email address of the currently logged-in sales rep from session / principal.
     */
    public String getCurrentSalesEmail() {
        UserOAuthProfile user = resolveActiveProfileFromRequestOrMemory();
        if (user != null && user.isAuthenticated() && user.getEmail() != null && !user.getEmail().isBlank()) {
            return user.getEmail();
        }
        return null;
    }

    /**
     * Resolves the display name of the currently logged-in sales rep from session / principal.
     */
    public String getCurrentSalesName() {
        UserOAuthProfile user = resolveActiveProfileFromRequestOrMemory();
        if (user != null && user.isAuthenticated() && user.getName() != null && !user.getName().isBlank()) {
            return user.getName();
        }
        return null;
    }

    /**
     * Builds the Gmail API client strictly using the currently logged-in user's OAuth2 Access Token / Refresh Token.
     */
    public Gmail resolveGmailClient() {
        UserOAuthProfile user = resolveActiveProfileFromRequestOrMemory();
        if (user != null && user.isAuthenticated() && user.getAccessToken() != null) {
            try {
                NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
                GsonFactory jsonFactory = GsonFactory.getDefaultInstance();

                Credential.Builder credBuilder = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
                        .setTransport(transport)
                        .setJsonFactory(jsonFactory)
                        .setTokenServerUrl(new GenericUrl("https://oauth2.googleapis.com/token"));

                if (clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank()) {
                    credBuilder.setClientAuthentication(new ClientParametersAuthentication(clientId, clientSecret));
                }

                Credential credential = credBuilder.build();
                credential.setAccessToken(user.getAccessToken());
                if (user.getRefreshToken() != null && !user.getRefreshToken().isBlank()) {
                    credential.setRefreshToken(user.getRefreshToken());
                }
                if (user.getExpiresAtEpochMillis() != null) {
                    credential.setExpirationTimeMilliseconds(user.getExpiresAtEpochMillis());
                }

                return new Gmail.Builder(transport, jsonFactory, credential)
                        .setApplicationName(applicationName)
                        .build();
            } catch (Exception e) {
                log.error("Failed to build Gmail client from OAuth2 access token: {}", e.getMessage(), e);
            }
        }
        return null;
    }

    private UserOAuthProfile resolveActiveProfileFromRequestOrMemory() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof OAuth2AuthenticationToken oauthToken && oauthToken.isAuthenticated()) {
                ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                HttpSession session = (attrs != null && attrs.getRequest() != null) ? attrs.getRequest().getSession(false) : null;
                OAuth2AuthorizedClient client = authorizedClientService != null
                        ? authorizedClientService.loadAuthorizedClient(oauthToken.getAuthorizedClientRegistrationId(), oauthToken.getName())
                        : null;
                return registerOAuth2Login(oauthToken, client, session);
            }

            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null && attrs.getRequest() != null) {
                HttpSession session = attrs.getRequest().getSession(false);
                if (session != null) {
                    Object attr = session.getAttribute(SESSION_ATTR_USER);
                    if (attr instanceof UserOAuthProfile profile && profile.isAuthenticated()) {
                        return profile;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return activeUserRef.get();
    }
}
