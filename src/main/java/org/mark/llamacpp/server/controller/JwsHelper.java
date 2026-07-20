package org.mark.llamacpp.server.controller;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * ACME JWS (JSON Web Signature) 签名工具。
 * 使用 ES256 (EC P-256 + SHA-256) 签名算法。
 */
public class JwsHelper {

    /**
     * 构造并签名 ACME JWS 请求。
     *
     * @param url       请求目标 URL
     * @param payload   请求体（JSON 字符串），GET 请求传空字符串
     * @param nonce     ACME nonce
     * @param accountKeyPair ACME 账户密钥对
     * @param kid       账户 URL（首次注册时为 null，用 jwk 替代）
     * @return JWS JSON 字符串
     */
    public static String sign(String url, String payload, String nonce, KeyPair accountKeyPair, String kid) throws Exception {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String payloadB64 = b64url(payloadBytes);

        JsonObject protectedHeader = new JsonObject();
        protectedHeader.addProperty("alg", "ES256");
        protectedHeader.addProperty("nonce", nonce);
        protectedHeader.addProperty("url", url);

        if (kid != null) {
            protectedHeader.addProperty("kid", kid);
        } else {
            protectedHeader.add("jwk", buildJwk(accountKeyPair));
        }

        String protectedB64 = b64url(protectedHeader.toString().getBytes(StandardCharsets.UTF_8));

        // 签名: Base64URL(protected) + "." + Base64URL(payload)
        String signingInput = protectedB64 + "." + payloadB64;
        byte[] signingBytes = signingInput.getBytes(StandardCharsets.UTF_8);

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(accountKeyPair.getPrivate());
        sig.update(signingBytes);
        byte[] derSignature = sig.sign();

        // DER 转 raw r||s (ACME 要求 raw 格式)
        byte[] rawSignature = derToRawEcdsa(derSignature);
        String signatureB64 = b64url(rawSignature);

        JsonObject jws = new JsonObject();
        jws.addProperty("protected", protectedB64);
        jws.addProperty("payload", payloadB64);
        jws.addProperty("signature", signatureB64);

        return jws.toString();
    }

    private static JsonObject buildJwk(KeyPair keyPair) {
        ECPublicKey pub = (ECPublicKey) keyPair.getPublic();
        byte[] x = toUnsignedFixedLength(pub.getW().getAffineX().toByteArray(), 32);
        byte[] y = toUnsignedFixedLength(pub.getW().getAffineY().toByteArray(), 32);

        JsonObject jwk = new JsonObject();
        jwk.addProperty("crv", "P-256");
        jwk.addProperty("kty", "EC");
        jwk.addProperty("x", b64url(x));
        jwk.addProperty("y", b64url(y));
        return jwk;
    }

    /**
     * DER 编码的 ECDSA 签名转换为 raw r||s 格式（64 字节，P-256）。
     */
    private static byte[] derToRawEcdsa(byte[] der) {
        // DER 格式: 0x30 <len> 0x02 <rlen> <r> 0x02 <slen> <s>
        int offset = 0;
        if (der[offset] != 0x30) throw new IllegalArgumentException("Invalid DER signature");
        offset++; // skip 0x30
        offset++; // skip total length
        if (der[offset] != 0x02) throw new IllegalArgumentException("Invalid DER signature");
        offset++; // skip 0x02
        int rLen = der[offset] & 0xFF;
        offset++;
        byte[] r = new byte[rLen];
        System.arraycopy(der, offset, r, 0, rLen);
        offset += rLen;
        if (der[offset] != 0x02) throw new IllegalArgumentException("Invalid DER signature");
        offset++; // skip 0x02
        int sLen = der[offset] & 0xFF;
        offset++;
        byte[] s = new byte[sLen];
        System.arraycopy(der, offset, s, 0, sLen);

        // 去除前导零，补齐到 32 字节
        byte[] r32 = toUnsignedFixedLength(stripLeadingZero(r), 32);
        byte[] s32 = toUnsignedFixedLength(stripLeadingZero(s), 32);

        byte[] raw = new byte[64];
        System.arraycopy(r32, 0, raw, 0, 32);
        System.arraycopy(s32, 0, raw, 32, 32);
        return raw;
    }

    private static byte[] stripLeadingZero(byte[] bytes) {
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] result = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, result, 0, result.length);
            return result;
        }
        return bytes;
    }

    private static byte[] toUnsignedFixedLength(byte[] bytes, int length) {
        if (bytes.length == length) return bytes;
        if (bytes.length == length + 1 && bytes[0] == 0) {
            byte[] result = new byte[length];
            System.arraycopy(bytes, 1, result, 0, length);
            return result;
        }
        if (bytes.length < length) {
            byte[] result = new byte[length];
            System.arraycopy(bytes, 0, result, length - bytes.length, bytes.length);
            return result;
        }
        return bytes;
    }

    private static String b64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
