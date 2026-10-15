package com.codingagent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretCipherTest {

    private SecretCipher cipher(String key) {
        return new SecretCipher(key);
    }

    @Test
    void 加解密往返() {
        SecretCipher c = cipher("unit-test-key");
        String plain = "glpat-abcdefghijklmnop";
        String enc = c.encrypt(plain);
        assertNotEquals(plain, enc, "密文不应等于明文");
        assertEquals(plain, c.decrypt(enc));
    }

    @Test
    void 同一明文两次加密结果不同() {
        SecretCipher c = cipher("unit-test-key");
        assertNotEquals(c.encrypt("same"), c.encrypt("same"),
                "IV 每次随机，密文不应可预测");
    }

    @Test
    void 密钥不同解不开() {
        String enc = cipher("key-A").encrypt("secret-value");
        assertThrows(IllegalArgumentException.class,
                () -> cipher("key-B").decrypt(enc));
    }

    @Test
    void 密文损坏时报错而非返回垃圾() {
        String enc = cipher("k").encrypt("secret-value");
        String corrupted = enc.substring(0, enc.length() - 4) + "AAAA";
        assertThrows(IllegalArgumentException.class,
                () -> cipher("k").decrypt(corrupted));
    }

    @Test
    void 空值往返为null() {
        SecretCipher c = cipher("k");
        assertNull(c.encrypt(null));
        assertNull(c.encrypt(""));
        assertNull(c.decrypt(null));
        assertNull(c.decrypt(""));
    }

    @Test
    void 缺失密钥时拒绝启动() {
        assertThrows(IllegalStateException.class, () -> cipher(""));
        assertThrows(IllegalStateException.class, () -> cipher(null));
    }

    @Test
    void 掩码不泄露明文() {
        assertEquals("", SecretCipher.mask(null));
        assertEquals("****", SecretCipher.mask("short"));
        String masked = SecretCipher.mask("glpat-1234567890abcdef");
        assertFalse(masked.contains("1234567890"), "掩码不能含中间段");
        assertTrue(masked.contains("glp"), "保留前缀便于识别");
    }
}
