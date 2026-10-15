package com.codingagent.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/**
 * 从请求里解析当前身份。
 *
 * <p>优先级：{@code Authorization: Bearer <token>} → {@code X-User-Id}。
 * 后者是登录上线前留的调试口子，接 OIDC 后应移除——留着是因为前端
 * 还在渐进式切换，且部分脚本可能仍用旧头。
 */
@Component
public class CurrentUser {

    private final AuthService authService;

    public CurrentUser(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 解析当前用户 id。
     *
     * @throws UnauthorizedException 无法识别身份时
     */
    public Long requireUserId(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7).trim();
            return authService.resolve(token)
                    .map(AuthService.AuthSession::userId)
                    .orElseThrow(UnauthorizedException::new);
        }

        String legacy = request.getHeaders().getFirst("X-User-Id");
        if (legacy != null && !legacy.isBlank()) {
            try {
                long id = Long.parseLong(legacy.trim());
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // 落到下面统一抛 401
            }
        }
        throw new UnauthorizedException();
    }

    public Long optionalUserId(ServerHttpRequest request) {
        try {
            return requireUserId(request);
        } catch (UnauthorizedException e) {
            return null;
        }
    }

    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException() {
            super("未登录或登录已过期");
        }
    }
}
