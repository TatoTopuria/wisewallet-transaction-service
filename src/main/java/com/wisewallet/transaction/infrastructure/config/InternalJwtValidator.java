package com.wisewallet.transaction.infrastructure.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Validates the short-lived X-Service-Token signed by the Gateway.
 */
@Component
public class InternalJwtValidator {

    private static final String EXPECTED_ISSUER = "wisewallet-gateway";

    private final SecretKey signingKey;

    public InternalJwtValidator(@Value("${wisewallet.internal.jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public InternalJwtClaims validateAndExtract(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(EXPECTED_ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String userId = claims.getSubject();

            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) claims.getOrDefault("roles", List.of());

            @SuppressWarnings("unchecked")
            List<String> accountIds = (List<String>) claims.getOrDefault("accountIds", List.of());

            return new InternalJwtClaims(userId, roles, accountIds);

        } catch (ExpiredJwtException e) {
            throw new InternalJwtException("Internal service token expired", e);
        } catch (JwtException e) {
            throw new InternalJwtException("Invalid internal service token: " + e.getMessage(), e);
        }
    }

    public record InternalJwtClaims(String userId, List<String> roles, List<String> accountIds) {}

    public static class InternalJwtException extends RuntimeException {
        public InternalJwtException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
