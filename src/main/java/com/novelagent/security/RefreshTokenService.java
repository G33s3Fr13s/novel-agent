package com.novelagent.security;

import com.novelagent.config.JwtConfig;
import com.novelagent.entity.RefreshToken;
import com.novelagent.entity.User;
import com.novelagent.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtConfig jwtConfig;

    @Transactional
    public RefreshToken createRefreshToken(User user, String rawToken) {
        refreshTokenRepository.revokeAllByUserId(user.getId());
        RefreshToken rt = RefreshToken.builder()
                .user(user)
                .tokenHash(sha256(rawToken))
                .expiresAt(Instant.now().plusMillis(jwtConfig.getRefreshTokenExpiration()))
                .revoked(false)
                .build();
        return refreshTokenRepository.save(rt);
    }

    @Transactional(readOnly = true)
    public Optional<RefreshToken> validateRefreshToken(String rawToken) {
        if (!jwtTokenProvider.validateToken(rawToken) || !jwtTokenProvider.isRefreshToken(rawToken)) {
            return Optional.empty();
        }
        String hash = sha256(rawToken);
        Optional<RefreshToken> rtOpt = refreshTokenRepository.findByTokenHash(hash);
        if (rtOpt.isEmpty()) return Optional.empty();
        RefreshToken rt = rtOpt.get();
        if (rt.isRevoked()) {
            log.warn("Refresh token has been revoked: {}", rt.getId());
            return Optional.empty();
        }
        if (rt.getExpiresAt().isBefore(Instant.now())) {
            log.debug("Refresh token expired at: {}", rt.getExpiresAt());
            return Optional.empty();
        }
        return Optional.of(rt);
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        refreshTokenRepository.revokeAllByUserId(userId);
    }

    @Transactional
    public void revokeToken(String rawToken) {
        String hash = sha256(rawToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(rt -> {
            rt.setRevoked(true);
            refreshTokenRepository.save(rt);
        });
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
