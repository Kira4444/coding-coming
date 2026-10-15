package com.codingagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭据加解密。
 *
 * <p>AES-256-GCM。密钥来自环境变量 {@code CODING_AGENT_SECRET_KEY}，
 * 不写进代码、不写进配置文件——凭据入库的唯一前提是密钥与数据分开保管。
 *
 * <p>密钥缺失时<b>不静默降级成明文</b>：直接抛异常。宁可发布功能不可用，
 * 也不能把 deploy token 明文落库。
 *
 * <p>密文格式：base64( IV(12B) || ciphertext || tag(16B) )。
 * IV 每次随机，同一段明文两次加密结果不同。
 */
@Component
public class SecretCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;

    public SecretCipher(@Value("${coding-agent.secret-key:}") String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "未配置 CODING_AGENT_SECRET_KEY。凭据加密需要它——"
                            + "缺失时拒绝启动，而不是把凭据明文存进数据库。"
                            + "生成方式: openssl rand -base64 32");
        }
        this.key = deriveKey(secretKey.trim());
    }

    /**
     * 从任意长度的口令派生 256 位密钥。
     *
     * <p>用 SHA-256 而不是硬性要求 32 字节 base64：用户配 `openssl rand -base64 32`
     * 和随手写一句口令都得能用，代价是口令强度直接决定密钥强度。
     */
    private static SecretKey deriveKey(String secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("密钥派生失败", e);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("凭据加密失败", e);
        }
    }

    /**
     * @return 解密后的明文；入参为空时返回 null
     * @throws IllegalArgumentException 密文损坏或密钥不匹配
     */
    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            if (raw.length <= IV_LENGTH) {
                throw new IllegalArgumentException("凭据密文长度异常");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] ct = new byte[raw.length - IV_LENGTH];
            System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
            System.arraycopy(raw, IV_LENGTH, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // GCM tag 校验失败意味着密文被改过或密钥换了
            throw new IllegalArgumentException(
                    "凭据解密失败：密文损坏，或 CODING_AGENT_SECRET_KEY 与加密时不是同一个");
        }
    }

    /** 展示用掩码，绝不回显明文 */
    public static String mask(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        if (plaintext.length() <= 8) {
            return "****";
        }
        return plaintext.substring(0, 3) + "****" + plaintext.substring(plaintext.length() - 3);
    }
}
