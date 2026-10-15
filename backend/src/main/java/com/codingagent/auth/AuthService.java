package com.codingagent.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.codingagent.storage.entity.User;
import com.codingagent.storage.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录与令牌。
 *
 * <p><b>令牌只存在内存里</b>：服务重启即全部失效，用户需重新登录。
 * MVP 够用且实现最简；接 OIDC 时整个类换成标准实现即可，控制器层不用动。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** token -> 会话主体 */
    private static final Map<String, AuthSession> TOKENS = new ConcurrentHashMap<>();

    private final UserMapper userMapper;

    public AuthService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public record AuthSession(Long userId, String username, String role, Instant loginAt) {
    }

    /** 登录结果：令牌必须与会话一起返回，客户端只拿到 token，服务端不反查。 */
    public record LoginResult(String token, AuthSession session) {
    }

    /** 登录失败统一返回「用户名或口令错误」，不区分哪个错——区分会帮助撞库 */
    public Optional<LoginResult> login(String username, String password) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username)
                .last("LIMIT 1"));
        if (user == null) {
            log.info("登录失败：用户不存在 [{}]", username);
            return Optional.empty();
        }
        if (!PasswordHasher.matches(password, user.getPasswordHash())) {
            log.info("登录失败：口令错误 [{}]", username);
            return Optional.empty();
        }

        String token = PasswordHasher.newToken();
        AuthSession session = new AuthSession(user.getId(), user.getUsername(), user.getRole(), Instant.now());
        TOKENS.put(token, session);
        log.info("登录成功 [{}] userId={}", username, user.getId());
        return Optional.of(new LoginResult(token, session));
    }

    public Optional<AuthSession> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(TOKENS.get(token));
    }

    public void logout(String token) {
        if (token != null) {
            TOKENS.remove(token);
        }
    }

    /** 修改口令。改完后该用户所有已签发令牌全部作废，防止旧会话残留。 */
    public boolean changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return false;
        }
        if (!PasswordHasher.matches(oldPassword, user.getPasswordHash())) {
            return false;
        }
        user.setPasswordHash(PasswordHasher.hash(newPassword));
        userMapper.updateById(user);

        TOKENS.entrySet().removeIf(e -> e.getValue().userId().equals(userId));
        log.info("用户 [{}] 已改密，其全部令牌作废", user.getUsername());
        return true;
    }

    public Optional<User> findUser(Long userId) {
        return Optional.ofNullable(userMapper.selectById(userId));
    }
}
