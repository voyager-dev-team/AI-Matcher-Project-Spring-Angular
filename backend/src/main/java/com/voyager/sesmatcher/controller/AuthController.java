package com.voyager.sesmatcher.controller;

import com.voyager.sesmatcher.service.AuditLogService;
import com.voyager.sesmatcher.service.GmailService;
import com.voyager.sesmatcher.service.OAuthSessionTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final OAuthSessionTokenService oauthSessionTokenService;
    private final GmailService gmailService;
    private final AuditLogService auditLogService;

    /**
     * Returns the currently logged-in sales rep's profile (email, name, picture, authenticated status)
     * strictly from Google OAuth2 Authentication / Principal and session.
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        OAuthSessionTokenService.UserOAuthProfile profile = oauthSessionTokenService.getCurrentUser(session);

        Map<String, Object> response = new HashMap<>();
        response.put("authenticated", profile.isAuthenticated());
        response.put("email", profile.getEmail());
        response.put("name", profile.getName());
        response.put("picture", profile.getPicture());
        response.put("gmailConnected", profile.isAuthenticated() && gmailService.isConnected());
        response.put("loginUrl", "http://localhost:8080/oauth2/authorization/google");
        return ResponseEntity.ok(response);
    }

    /**
     * Logs out the current sales rep and clears their OAuth2 tokens from session and memory.
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        OAuthSessionTokenService.UserOAuthProfile profile = oauthSessionTokenService.getCurrentUser(session);
        if (profile != null && profile.isAuthenticated()) {
            auditLogService.recordLogForUser(
                    profile.getEmail(),
                    profile.getName(),
                    "ログアウト",
                    "Google OAuth2 セッションからログアウトしました"
            );
        }
        oauthSessionTokenService.logout(session);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "LOGGED_OUT");
        response.put("authenticated", false);
        return ResponseEntity.ok(response);
    }
}
