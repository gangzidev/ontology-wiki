package com.ontologywiki.query.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 对配置 JSON 做稳定哈希，防止评测通过后配置被修改仍继续查询。 */
public final class QueryConfigHash {
    private QueryConfigHash() { }

    public static String of(ObjectMapper mapper, Object config) {
        try {
            byte[] bytes = mapper.writeValueAsString(config).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder();
            for (byte value : digest) out.append(String.format("%02x", value));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算查询配置版本", e);
        }
    }
}
