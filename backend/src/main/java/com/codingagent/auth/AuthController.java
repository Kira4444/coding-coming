package com.codingagent.auth;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;

/** 登录接口。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUser currentUser;

    public AuthController(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token, UserView user, Instant loginAt) {
    }

    public record UserView(Long id, String username, String displayName, String role) {
    }

    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }

    @PostMapping("/login")
    public Mono<LoginResponse> login(@jakarta.validation.Valid @RequestBody LoginRequest req) {
        return authService.login(req.username(), req.password())
                .map(result -> {
                    AuthService.AuthSession session = result.session();
                    UserView view = authService.findUser(session.userId())
                            .map(u -> new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole()))
                            .orElseGet(() -> new UserView(session.userId(), session.username(), null, session.role()));
                    // 口令与散列绝不进响应体
                    return Mono.just(new LoginResponse(result.token(), view, session.loginAt()));
                })
                .orElseGet(() -> Mono.error(new IllegalArgumentException("用户名或口令错误")));
    }

    /**
     * 登出。
     *
     * <p>token 是无状态的，客户端丢弃即可；服务端移除是为了让「改密后强制下线」
     * 这类需求有落点。
     */
    @PostMapping("/logout")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public Mono<Void> logout(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            authService.logout(auth.substring(7).trim());
        }
        return Mono.empty();
    }

    @GetMapping("/me")
    public Mono<UserView> me(ServerHttpRequest request) {
        Long userId = currentUser.requireUserId(request);
        return authService.findUser(userId)
                .map(u -> Mono.just(new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole())))
                .orElseGet(() -> Mono.error(new CurrentUser.UnauthorizedException()));
    }

    @PostMapping("/password")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public Mono<Void> changePassword(ServerHttpRequest request,
                                     @jakarta.validation.Valid @RequestBody ChangePasswordRequest req) {
        Long userId = currentUser.requireUserId(request);
        if (req.newPassword().length() < 8) {
            return Mono.error(new IllegalArgumentException("新口令至少 8 位"));
        }
        boolean ok = authService.changePassword(userId, req.oldPassword(), req.newPassword());
        if (!ok) {
            return Mono.error(new IllegalArgumentException("原口令不正确"));
        }
        return Mono.empty();
    }
}
