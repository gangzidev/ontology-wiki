package com.ontologywiki.ontology.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 数据源密码只以 AES-GCM 密文落库，接口不会返回密文。 */
@Service
public class CredentialService {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public CredentialService(@Value("${ontology.integration.credential-key:change-this-credential-key-in-production}") String rawKey) {
        try {
            this.key = MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化凭证加密", e);
        }
    }

    public String encrypt(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (Exception e) {
            throw new IllegalStateException("凭证加密失败", e);
        }
    }

    public String decrypt(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(value.indexOf(':') + 1));
            byte[] iv = new byte[12]; byte[] encrypted = new byte[all.length - 12];
            System.arraycopy(all, 0, iv, 0, iv.length); System.arraycopy(all, iv.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("凭证解密失败，请检查 ontology.integration.credential-key", e);
        }
    }
}
