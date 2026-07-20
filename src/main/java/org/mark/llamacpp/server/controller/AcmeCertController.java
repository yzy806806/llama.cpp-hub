package org.mark.llamacpp.server.controller;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;

/**
 * ACME v2 协议实现：通过 Let's Encrypt 申请免费 TLS 证书。
 *
 * 流程：
 * 1. 创建 ACME 账户（EC P-256 密钥对）
 * 2. 提交订单（指定域名）
 * 3. 获取 HTTP-01 challenge
 * 4. 响应 challenge（由 BasicRouterHandler 拦截 /.well-known/acme-challenge/* 返回 token）
 * 5. 等待验证通过
 * 6. 生成 CSR 提交，下载证书
 * 7. 转换为 PKCS12 格式保存到 ssl/keystore.p12
 *
 * 使用纯 JDK + Netty HTTP，不引入外部 ACME 库。
 */
public class AcmeCertController implements BaseController {

    private static final Logger logger = LoggerFactory.getLogger(AcmeCertController.class);

    private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
    private static final String I18N_CERT_GENERATE_FAILED = "api.error.cert.generate.failed";

    /** Let's Encrypt ACME v2 目录 URL */
    private static final String ACME_DIRECTORY = "https://acme-v02.api.letsencrypt.org/directory";
    /** 测试环境 */
    // private static final String ACME_DIRECTORY = "https://acme-staging-v02.api.letsencrypt.org/directory";

    /** ACME challenge token，由 BasicRouterHandler 在 /.well-known/acme-challenge/ 路径返回 */
    private static volatile String pendingChallengeToken;
    private static volatile String pendingChallengeKeyAuth;

    @Override
    public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
            throws RequestMethodException {
        if (uri.equals("/api/cert/acme")) {
            this.handleAcmeRequest(ctx, request);
            return true;
        }
        return false;
    }

    /**
     * 获取当前待响应的 ACME challenge 信息。
     * BasicRouterHandler 调用此方法处理 /.well-known/acme-challenge/* 请求。
     *
     * @return [token, keyAuthorization]，如果无 pending challenge 则返回 null
     */
    public static String[] getPendingChallenge() {
        String token = pendingChallengeToken;
        String keyAuth = pendingChallengeKeyAuth;
        if (token == null || keyAuth == null) {
            return null;
        }
        return new String[]{token, keyAuth};
    }

    private void handleAcmeRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
            JsonObject body = JsonUtil.parseFullHttpRequestToJsonObject(request, ctx);
            if (body == null) return;

            String domain = JsonUtil.getJsonString(body, "domain");
            if (domain == null || domain.isBlank()) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error("domain is required"));
                return;
            }
            domain = domain.trim().toLowerCase();

            String password = JsonUtil.getJsonString(body, "password");
            if (password.isEmpty()) {
                password = generatePassword();
            }

            logger.info("开始 ACME 证书申请，域名: {}", domain);

            // 1. 获取 ACME 目录
            JsonObject directory = httpGetJson(ACME_DIRECTORY);
            String newAccountUrl = directory.get("newAccount").getAsString();
            String newOrderUrl = directory.get("newNonce").getAsString();
            String nonceUrl = directory.get("newNonce").getAsString();

            // 2. 生成 EC P-256 账户密钥对
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair accountKeyPair = kpg.generateKeyPair();

            // 3. 获取 nonce
            String nonce = getNonce(directory.get("newNonce").getAsString());

            // 4. 创建账户
            String kid = createAccount(newAccountUrl, nonce, accountKeyPair, domain);
            nonce = extractNonceFromResponse(kid);

            // 5. 创建订单
            JsonObject orderResp = createOrder(directory.get("newOrder").getAsString(), nonce, accountKeyPair, kid, domain);
            nonce = orderResp.has("_nonce") ? orderResp.get("_nonce").getAsString() : nonce;
            String orderUrl = orderResp.get("_location").getAsString();
            JsonArray identifiers = orderResp.getAsJsonArray("identifiers");
            if (identifiers == null || identifiers.isEmpty()) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error("Failed to create ACME order"));
                return;
            }

            // 6. 获取 authorizations 和 challenge
            String authzUrl = orderResp.getAsJsonArray("authorizations").get(0).getAsString();
            JsonObject authzResp = acmeGet(authzUrl, nonce, accountKeyPair, kid);
            nonce = authzResp.has("_nonce") ? authzResp.get("_nonce").getAsString() : nonce;

            // 找到 HTTP-01 challenge
            String challengeUrl = null;
            String challengeToken = null;
            for (var elem : authzResp.getAsJsonArray("challenges")) {
                JsonObject challenge = elem.getAsJsonObject();
                if ("http-01".equals(challenge.get("type").getAsString())) {
                    challengeUrl = challenge.get("url").getAsString();
                    challengeToken = challenge.get("token").getAsString();
                    break;
                }
            }
            if (challengeUrl == null) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error("No HTTP-01 challenge available"));
                return;
            }

            // 7. 计算 key authorization
            String thumbprint = computeJwkThumbprint(accountKeyPair);
            String keyAuth = challengeToken + "." + thumbprint;

            // 设置 pending challenge，等 BasicRouterHandler 来响应
            pendingChallengeToken = challengeToken;
            pendingChallengeKeyAuth = keyAuth;

            // 8. 通知 ACME 服务器开始验证
            JsonObject challengeResp = acmePost(challengeUrl, "{}", nonce, accountKeyPair, kid);
            nonce = challengeResp.has("_nonce") ? challengeResp.get("_nonce").getAsString() : nonce;

            // 9. 等待验证完成（轮询，最多 60 秒）
            boolean verified = false;
            for (int i = 0; i < 30; i++) {
                Thread.sleep(2000);
                JsonObject statusResp = acmeGet(authzUrl, nonce, accountKeyPair, kid);
                nonce = statusResp.has("_nonce") ? statusResp.get("_nonce").getAsString() : nonce;
                String status = statusResp.has("status") ? statusResp.get("status").getAsString() : "pending";
                if ("valid".equals(status)) {
                    verified = true;
                    break;
                }
                if ("invalid".equals(status)) {
                    pendingChallengeToken = null;
                    pendingChallengeKeyAuth = null;
                    LlamaServer.sendJsonResponse(ctx, ApiResponse.error("ACME validation failed"));
                    return;
                }
            }

            pendingChallengeToken = null;
            pendingChallengeKeyAuth = null;

            if (!verified) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error("ACME validation timeout"));
                return;
            }

            // 10. 生成域名密钥对和 CSR
            KeyPairGenerator domainKpg = KeyPairGenerator.getInstance("RSA");
            domainKpg.initialize(2048);
            KeyPair domainKeyPair = domainKpg.generateKeyPair();

            // 11. 生成 CSR（用 keytool 或纯 Java）
            String csrPem = generateCsr(domain, domainKeyPair);

            // 12. 提交 CSR，finalize 订单
            JsonObject finalizeResp = acmePost(
                    orderResp.get("finalize").getAsString(),
                    "{\"csr\":\"" + csrPem + "\"}",
                    nonce, accountKeyPair, kid);
            nonce = finalizeResp.has("_nonce") ? finalizeResp.get("_nonce").getAsString() : nonce;

            // 13. 等待订单完成，下载证书
            String certUrl = null;
            for (int i = 0; i < 15; i++) {
                Thread.sleep(2000);
                JsonObject orderStatus = acmeGet(orderUrl, nonce, accountKeyPair, kid);
                nonce = orderStatus.has("_nonce") ? orderStatus.get("_nonce").getAsString() : nonce;
                if (orderStatus.has("certificate")) {
                    certUrl = orderStatus.get("certificate").getAsString();
                    break;
                }
                String status = orderStatus.has("status") ? orderStatus.get("status").getAsString() : "";
                if ("invalid".equals(status)) {
                    LlamaServer.sendJsonResponse(ctx, ApiResponse.error("Order finalized as invalid"));
                    return;
                }
            }
            if (certUrl == null) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error("Failed to get certificate URL"));
                return;
            }

            // 14. 下载证书链
            String certChainPem = acmeGetText(certUrl, nonce, accountKeyPair, kid);

            // 15. 转换为 PKCS12
            Path sslDir = Paths.get("ssl");
            Files.createDirectories(sslDir);
            Path certFile = sslDir.resolve("acme-cert.pem");
            Path keyFile = sslDir.resolve("acme-key.pem");
            Files.writeString(certFile, certChainPem, StandardCharsets.UTF_8);
            Files.writeString(keyFile, pemEncodePrivateKey(domainKeyPair), StandardCharsets.UTF_8);

            Path keystoreFile = sslDir.resolve("keystore.p12");
            convertToPkcs12(certFile, keyFile, keystoreFile, password);

            // 清理临时文件
            Files.deleteIfExists(certFile);
            Files.deleteIfExists(keyFile);

            // 更新配置
            LlamaServer.updateHttpsConfig(true, keystoreFile.toString(), password);

            Map<String, Object> data = new HashMap<>();
            data.put("path", keystoreFile.toString());
            data.put("password", password);
            data.put("domain", domain);
            data.put("message", "Certificate issued successfully. Please restart the service to enable HTTPS.");

            logger.info("ACME 证书申请成功，域名: {}, 证书路径: {}", domain, keystoreFile);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));

        } catch (Exception e) {
            logger.error("ACME 证书申请失败", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CERT_GENERATE_FAILED + ": " + e.getMessage()));
        }
    }

    // ========== ACME HTTP 方法 ==========

    private String getNonce(String nonceUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(nonceUrl).openConnection();
        conn.setRequestMethod("HEAD");
        conn.connect();
        String nonce = conn.getHeaderField("Replay-Nonce");
        if (nonce == null) {
            throw new RuntimeException("Failed to get ACME nonce");
        }
        return nonce;
    }

    private String createAccount(String newAccountUrl, String nonce, KeyPair accountKeyPair, String domain) throws Exception {
        JsonObject payload = new JsonObject();
        JsonObject contact = new JsonObject();
        contact.addProperty("type", "mailto");
        contact.addProperty("value", "mailto:admin@" + domain);
        JsonArray contacts = new JsonArray();
        contacts.add(contact);
        payload.add("contact", contacts);
        payload.addProperty("termsOfServiceAgreed", true);

        JsonObject resp = acmePost(newAccountUrl, payload.toString(), nonce, accountKeyPair, null);
        String location = resp.has("_location") ? resp.get("_location").getAsString() : null;
        if (location == null) {
            throw new RuntimeException("Failed to create ACME account");
        }
        return location;
    }

    private JsonObject createOrder(String newOrderUrl, String nonce, KeyPair accountKeyPair, String kid, String domain) throws Exception {
        JsonObject payload = new JsonObject();
        JsonArray identifiers = new JsonArray();
        JsonObject ident = new JsonObject();
        ident.addProperty("type", "dns");
        ident.addProperty("value", domain);
        identifiers.add(ident);
        payload.add("identifiers", identifiers);

        return acmePost(newOrderUrl, payload.toString(), nonce, accountKeyPair, kid);
    }

    private JsonObject acmePost(String url, String payload, String nonce, KeyPair accountKeyPair, String kid) throws Exception {
        String jws = JwsHelper.sign(url, payload, nonce, accountKeyPair, kid);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/jose+json");
        conn.setDoOutput(true);
        conn.getOutputStream().write(jws.getBytes(StandardCharsets.UTF_8));
        conn.connect();

        String responseNonce = conn.getHeaderField("Replay-Nonce");
        String location = conn.getHeaderField("Location");
        String body = readResponseBody(conn);

        JsonObject result = body.isEmpty() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        if (responseNonce != null) result.addProperty("_nonce", responseNonce);
        if (location != null) result.addProperty("_location", location);
        return result;
    }

    private JsonObject acmeGet(String url, String nonce, KeyPair accountKeyPair, String kid) throws Exception {
        // ACME GET 不需要签名 nonce，但有些实现需要
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.connect();

        String responseNonce = conn.getHeaderField("Replay-Nonce");
        String body = readResponseBody(conn);

        JsonObject result = body.isEmpty() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        if (responseNonce != null) result.addProperty("_nonce", responseNonce);
        return result;
    }

    private String acmeGetText(String url, String nonce, KeyPair accountKeyPair, String kid) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/pem-certificate-chain");
        conn.connect();
        return readResponseBody(conn);
    }

    private JsonObject httpGetJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.connect();
        String body = readResponseBody(conn);
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private String readResponseBody(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        var stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) return "";
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String extractNonceFromResponse(String response) {
        return response; // simplified
    }

    // ========== 工具方法 ==========

    private String computeJwkThumbprint(KeyPair accountKeyPair) throws Exception {
        ECPublicKey pub = (ECPublicKey) accountKeyPair.getPublic();
        // JWK for EC P-256
        byte[] x = toUnsignedFixedLength(pub.getW().getAffineX().toByteArray(), 32);
        byte[] y = toUnsignedFixedLength(pub.getW().getAffineY().toByteArray(), 32);
        String jwk = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"" + b64url(x) + "\",\"y\":\"" + b64url(y) + "\"}";
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(jwk.getBytes(StandardCharsets.UTF_8));
        return b64url(hash);
    }

    private byte[] toUnsignedFixedLength(byte[] bytes, int length) {
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

    private String b64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private String generatePassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String generateCsr(String domain, KeyPair domainKeyPair) throws Exception {
        // 使用 keytool 生成 CSR
        String javaHome = System.getProperty("java.home");
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        String keytoolPath = javaHome + File.separator + "bin" + File.separator + (isWindows ? "keytool.exe" : "keytool");

        Path tempKeystore = Files.createTempFile("acme-csr-", ".p12");
        Path csrFile = Files.createTempFile("acme-csr-", ".csr");

        try {
            String dname = "CN=" + domain + ",O=llamacpp-hub";
            ProcessBuilder pb = new ProcessBuilder(
                    keytoolPath, "-genkeypair",
                    "-alias", "server",
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-keystore", tempKeystore.toString(),
                    "-storetype", "PKCS12",
                    "-storepass", "temp123",
                    "-keypass", "temp123",
                    "-dname", dname,
                    "-validity", "365"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();

            pb = new ProcessBuilder(
                    keytoolPath, "-certreq",
                    "-alias", "server",
                    "-keystore", tempKeystore.toString(),
                    "-storepass", "temp123",
                    "-keypass", "temp123",
                    "-file", csrFile.toString()
            );
            pb.redirectErrorStream(true);
            p = pb.start();
            p.waitFor();

            String csrPem = Files.readString(csrFile, StandardCharsets.UTF_8);
            // 去掉 PEM 头尾和换行
            csrPem = csrPem.replace("-----BEGIN NEW CERTIFICATE REQUEST-----", "")
                    .replace("-----END NEW CERTIFICATE REQUEST-----", "")
                    .replaceAll("\\s", "");
            return csrPem;
        } finally {
            Files.deleteIfExists(tempKeystore);
            Files.deleteIfExists(csrFile);
        }
    }

    private String pemEncodePrivateKey(KeyPair keyPair) throws Exception {
        // 简化：返回 PKCS8 格式 PEM
        byte[] encoded = keyPair.getPrivate().getEncoded();
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    private void convertToPkcs12(Path certFile, Path keyFile, Path keystoreFile, String password) throws Exception {
        String javaHome = System.getProperty("java.home");
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        String keytoolPath = javaHome + File.separator + "bin" + File.separator + (isWindows ? "keytool.exe" : "keytool");

        // 先用 openssl 或 keytool 转换
        // keytool -importkeystore 不直接支持 PEM 私钥，需要先构建临时 PKCS12
        // 用纯 Java 方式构建 PKCS12
        javax.crypto.EncryptedPrivateKeyInfo ignored; // ensure javax.crypto available
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        ks.load(null, password.toCharArray());

        // 读取私钥
        byte[] keyBytes = Files.readAllBytes(keyFile);
        String keyPem = new String(keyBytes, StandardCharsets.UTF_8)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyDer = Base64.getDecoder().decode(keyPem);
        java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(keyDer);
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        java.security.PrivateKey privKey = kf.generatePrivate(keySpec);

        // 读取证书链
        String certPem = Files.readString(certFile, StandardCharsets.UTF_8);
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        var certs = cf.generateCertificates(new java.io.ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8)));

        ks.setKeyEntry("server", privKey, password.toCharArray(),
                certs.toArray(new java.security.cert.Certificate[0]));

        try (var fos = Files.newOutputStream(keystoreFile)) {
            ks.store(fos, password.toCharArray());
        }
    }
}
