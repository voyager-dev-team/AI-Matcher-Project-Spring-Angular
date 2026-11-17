package com.voyager.sesmatcher.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Gmail API client instances are dynamically created per logged-in user
 * using their OAuth2 Access Token / Refresh Token in OAuthSessionTokenService.
 */
@Configuration
@Slf4j
public class GmailConfig {
}
