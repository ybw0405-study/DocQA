package com.docqa.user.controller;

import com.docqa.common.api.ApiResponse;
import com.docqa.user.dto.ChangePasswordRequest;
import com.docqa.user.dto.CurrentUserData;
import com.docqa.user.dto.LoginRequest;
import com.docqa.user.dto.LoginResponse;
import com.docqa.user.dto.RegisterRequest;
import com.docqa.user.service.AccessTokenAuthenticator;
import com.docqa.user.service.AuthService;
import com.docqa.user.service.IssuedSession;
import com.docqa.user.service.IssuedToken;
import com.docqa.user.service.JwtIdentity;
import com.docqa.user.web.ClientIpResolver;
import com.docqa.user.web.RefreshCookieFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final AuthService authService;
    private final AccessTokenAuthenticator accessTokenAuthenticator;
    private final RefreshCookieFactory refreshCookieFactory;
    private final ClientIpResolver clientIpResolver;

    public AuthController(
            AuthService authService,
            AccessTokenAuthenticator accessTokenAuthenticator,
            RefreshCookieFactory refreshCookieFactory,
            ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.accessTokenAuthenticator = accessTokenAuthenticator;
        this.refreshCookieFactory = refreshCookieFactory;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        return sessionResponse(authService.login(request, clientIpResolver.resolve(httpRequest)), HttpStatus.OK);
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<LoginResponse>> register(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        IssuedSession session = authService.register(request, idempotencyKey, clientIpResolver.resolve(httpRequest));
        return sessionResponse(session, HttpStatus.CREATED);
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(HttpServletRequest httpRequest) {
        IssuedSession session = authService.refresh(refreshCookieFactory.read(httpRequest));
        return sessionResponse(session, HttpStatus.OK);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest httpRequest) {
        authService.logout(authorization, refreshCookieFactory.read(httpRequest));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.clear().toString())
                .build();
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest) {
        JwtIdentity identity = accessTokenAuthenticator.authenticate(authorization);
        authService.changePassword(identity, request, idempotencyKey, httpRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserData> currentUser(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        JwtIdentity identity = accessTokenAuthenticator.authenticate(authorization);
        return ApiResponse.ok(authService.currentUser(identity));
    }

    private ResponseEntity<ApiResponse<LoginResponse>> sessionResponse(IssuedSession session, HttpStatus status) {
        IssuedToken access = session.access();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (session.issuesRefreshToken()) {
            builder.header(HttpHeaders.SET_COOKIE, refreshCookieFactory.issue(session.refreshToken()).toString());
        }
        return builder.body(ApiResponse.ok(
                new LoginResponse(access.accessToken(), access.tokenType(), access.expiresIn())));
    }
}
