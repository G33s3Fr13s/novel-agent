package com.novelagent.service;

import com.novelagent.config.JwtConfig;
import com.novelagent.dto.AuthResponse;
import com.novelagent.dto.LoginRequest;
import com.novelagent.dto.RegisterRequest;
import com.novelagent.dto.TokenPair;
import com.novelagent.entity.RefreshToken;
import com.novelagent.entity.User;
import com.novelagent.exception.BusinessException;
import com.novelagent.exception.ErrorCode;
import com.novelagent.repository.UserRepository;
import com.novelagent.security.JwtTokenProvider;
import com.novelagent.security.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final JwtConfig jwtConfig;

    @Transactional
    public TokenPair register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .enabled(true)
                .build();

        user = userRepository.save(user);
        log.info("New user registered: {} ({})", user.getUsername(), user.getId());

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());
        refreshTokenService.createRefreshToken(user, refreshToken);

        AuthResponse response = AuthResponse.of(
                accessToken, jwtConfig.getAccessTokenExpiration(),
                user.getId(), user.getUsername(), user.getEmail(), user.getRole()
        );
        log.debug("Tokens generated for user: {}", user.getUsername());
        return TokenPair.of(response, refreshToken);
    }

    @Transactional
    public TokenPair login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
        } catch (BadCredentialsException e) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        } catch (DisabledException e) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        } catch (UsernameNotFoundException e) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());
        refreshTokenService.createRefreshToken(user, refreshToken);

        log.info("User logged in: {}", user.getUsername());
        AuthResponse response = AuthResponse.of(
                accessToken, jwtConfig.getAccessTokenExpiration(),
                user.getId(), user.getUsername(), user.getEmail(), user.getRole()
        );
        return TokenPair.of(response, refreshToken);
    }

    @Transactional
    public TokenPair refreshAccessToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        RefreshToken storedToken = refreshTokenService.validateRefreshToken(rawRefreshToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_EXPIRED));
        User user = storedToken.getUser();

        String newAccessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername());
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user.getId());
        refreshTokenService.createRefreshToken(user, newRefreshToken);

        log.debug("Tokens refreshed for user: {}", user.getUsername());
        AuthResponse response = AuthResponse.of(
                newAccessToken, jwtConfig.getAccessTokenExpiration(),
                user.getId(), user.getUsername(), user.getEmail(), user.getRole()
        );
        return TokenPair.of(response, newRefreshToken);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenService.revokeToken(rawRefreshToken);
        }
        log.debug("User logged out, refresh token revoked");
    }

    @Transactional(readOnly = true)
    public AuthResponse.UserInfo getCurrentUser(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return AuthResponse.UserInfo.builder()
                .id(user.getId()).username(user.getUsername()).email(user.getEmail()).role(user.getRole())
                .build();
    }
}
