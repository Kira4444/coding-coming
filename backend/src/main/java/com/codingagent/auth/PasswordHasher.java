package com.codingagent.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 口令散列。
 *
 * <p>SHA-256 + 全局盐只是 MVP 的兜底——表里绝不能躺明文。
 * 真正做登录时应换成 bcrypt / argon2（算力开销是特性，不是缺陷）。
 */
public final class PasswordHasher {

    private static final String SALT = "coding-agent";

    private PasswordHasher() {
    }

    public static String hash(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(SALT.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("口令散列失败", e);
        }
    }

    /** 定时比较，避免用字符串相等暴露口令前缀 */
    public static boolean matches(String raw, String expectedHash) {
        if (raw == null || expectedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(raw).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }

    /** 生成不透明 token */
    public static String newToken() {
        byte[] buf = new byte[32];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
