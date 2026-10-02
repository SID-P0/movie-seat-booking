package com.seatbooking.api.filter;

import com.seatbooking.api.repository.UserRepository;
import com.seatbooking.common.entity.UserEntity;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication filter that extracts identity from the Bearer token.
 * Identity is ALWAYS derived from the token — never from the request body.
 *
 * The token is SHA-256 hashed and looked up in the users table.
 * Admin endpoints use a separate pre-shared admin token.
 */
@Component
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class AuthTokenFilter implements Filter {

    private final UserRepository userRepository;

    @Value("${app.admin-token}")
    private String adminToken;

    public static final String USER_ATTR = "authenticatedUser";
    /** Request attribute key to flag admin requests */
    public static final String ADMIN_ATTR = "isAdmin";

    private final ConcurrentHashMap<String, UserEntity> userCache = new ConcurrentHashMap<>();

    /** Paths that don't require authentication */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/health/live",
            "/health/ready",
            "/actuator/prometheus",
            "/actuator/health",
            "/v3/api-docs",
            "/swagger-ui",
            "/test/burst"
    );

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;
        String path = httpReq.getRequestURI();
        String method = httpReq.getMethod();

        // Public endpoints — no auth needed
        if (PUBLIC_PATHS.stream().anyMatch(path::startsWith) || 
           (method.equals("GET") && (path.equals("/shows") || path.matches("^/shows/[a-f0-9\\-]+$") || path.matches("^/shows/[a-f0-9\\-]+/stream$") || path.startsWith("/sse"))) ||
           (method.equals("POST") && path.equals("/users/register"))) {
            chain.doFilter(request, response);
            return;
        }

        // Extract Bearer token
        String authHeader = httpReq.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            httpResp.setStatus(401);
            httpResp.setContentType("application/json");
            httpResp.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Missing or invalid Authorization header\"}");
            return;
        }

        String token = authHeader.substring(7).trim();

        // Check admin token first
        if (token.equals(adminToken)) {
            httpReq.setAttribute(ADMIN_ATTR, true);
            chain.doFilter(request, response);
            return;
        }

        // Hash token and look up user
        String tokenHash = hashToken(token);
        
        UserEntity user = userCache.computeIfAbsent(tokenHash, k -> userRepository.findByTokenHash(k).orElse(null));

        if (user == null) {
            httpResp.setStatus(401);
            httpResp.setContentType("application/json");
            httpResp.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Invalid token\"}");
            return;
        }

        // Set the authenticated user on the request — this is the ONLY source of identity
        httpReq.setAttribute(USER_ATTR, user);
        chain.doFilter(request, response);
    }

    public static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
