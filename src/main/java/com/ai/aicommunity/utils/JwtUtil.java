package com.ai.aicommunity.utils;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Date;

public class JwtUtil {

    private static final String SECRET = "ai-community-secret-key-ai-community-secret-key";

    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes());

    public static String createToken(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 86400000))
                .signWith(KEY)
                .compact();
    }

    public static Long getUserId(String token) {
        return Long.valueOf(Jwts.parser()
                .verifyWith(KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject());
    }
}
