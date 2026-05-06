# HTTPS Self-Signed Certificate Setup Guide

This guide explains how to generate a self-signed HTTPS certificate for llama.cpp-hub and enable HTTPS.

## Why Self-Signed

- LAN environments have no public domain name
- Need encrypted communication during development/testing
- Free, no third-party CA required

## Prerequisites

- JDK installed (includes `keytool`)
- Verify keytool is available:
  ```bash
  keytool -version
  ```

## Generate Self-Signed Certificate

### 1. Enter the ssl directory

```bash
cd ssl
```

### 2. Generate the keystore

```bash
keytool -genkeypair -alias llama-cpp-hub \
  -keyalg EC \
  -keysize 256 \
  -keystore keystore.p12 \
  -storetype PKCS12 \
  -storepass changeit \
  -validity 3650 \
  -dname "CN=llama-cpp-hub, O=MyOrg, C=CN" \
  -ext "SAN=DNS:localhost,IP:127.0.0.1,IP:0.0.0.0"
```

Parameter reference:

| Parameter | Description |
|-----------|-------------|
| `-alias llama-cpp-hub` | Certificate alias |
| `-keyalg EC` | Elliptic curve algorithm (lighter, recommended) |
| `-keysize 256` | Key size (EC 256-bit ≈ RSA 3072-bit) |
| `-keystore keystore.p12` | Output keystore filename |
| `-storetype PKCS12` | Keystore format (PKCS12 is the standard) |
| `-storepass changeit` | Keystore password (change to your own) |
| `-validity 3650` | Certificate validity in days (3650 = 10 years) |
| `-dname` | Certificate owner information |
| `-ext SAN=...` | Subject Alternative Names: `localhost`, `127.0.0.1`, `0.0.0.0` |

### 3. Export the CA certificate (for client trust)

```bash
keytool -exportcert -alias llama-cpp-hub \
  -keystore keystore.p12 \
  -storepass changeit \
  -rfc \
  -file ca.crt
```

This creates `ca.crt` in the `ssl` directory. Distribute this file to all client devices.

### 4. If accessing via LAN IP

The default SAN only covers `127.0.0.1` and `0.0.0.0`. If LAN devices access via a specific IP (e.g. `192.168.1.100`), generate a certificate with that IP:

```bash
keytool -genkeypair -alias llama-cpp-hub \
  -keyalg EC \
  -keysize 256 \
  -keystore keystore.p12 \
  -storetype PKCS12 \
  -storepass changeit \
  -validity 3650 \
  -dname "CN=llama-cpp-hub, O=MyOrg, C=CN" \
  -ext "SAN=DNS:localhost,IP:127.0.0.1,IP:192.168.1.100,IP:0.0.0.0"
```

Then redistribute the newly generated `ca.crt` to clients.

## Enable HTTPS

### 1. Edit configuration

Edit `config/application.json`, add or modify the `https` section:

```json
{
  "https": {
    "enabled": true,
    "certPath": "ssl/keystore.p12",
    "keyPath": "ssl/keystore.p12",
    "password": "changeit"
  }
}
```

| Field | Description | Default |
|-------|-------------|---------|
| `enabled` | Enable HTTPS | `false` |
| `certPath` | Keystore file path | `ssl/keystore.p12` |
| `keyPath` | Keystore file path (same as certPath for PKCS12) | `ssl/keystore.p12` |
| `password` | Keystore password | `changeit` |

### 2. Start the service

Restart llama.cpp-hub and check the startup logs:

- **HTTPS success**: `HTTPS证书加载成功: ssl/keystore.p12`, URL shows `https://`
- **HTTPS failure**: `HTTPS证书加载失败: ...`, falls back to HTTP

> **Client cert trust is optional.** If setting it up feels like too much work, just click "Continue" / "Proceed" when the browser warns about an unsafe connection.
>
> Yes, you lose server identity verification (cannot prevent MITM attacks), but in **LAN/home/NAT environments** the MITM risk is negligible. TLS encryption still works — traffic is fully encrypted over the wire, protecting against Wi-Fi sniffing, ISP snooping, and other passive eavesdropping. The encryption quality is identical to properly validated HTTPS.
>
> If seeing the "Not Secure" warning bothers you, follow the steps below to configure client trust.

## Configure Client Certificate Trust

Self-signed certificates are not trusted by OS/browsers by default. Import `ca.crt` into the trust store.

### Windows - Chrome / Edge / Firefox

1. Double-click `ca.crt`
2. Click "Install Certificate"
3. Select "Local Machine", Next
4. Select "Place all certificates in the following store", click "Browse"
5. Select "Trusted Root Certification Authorities"
6. Finish

> Administrator privileges required. Restart browser after installation.

### Windows - Command Line (Admin)

```powershell
certutil -addstore -f "ROOT" ssl\ca.crt
```

### macOS - Keychain Access

1. Double-click `ca.crt`
2. Double-click the certificate in Keychain
3. Expand "Trust", set "When using this certificate" to "Always Trust"
4. Close the window, enter password to confirm

### Linux

```bash
sudo cp ca.crt /usr/local/share/ca-certificates/llama-cpp-hub.crt
sudo update-ca-certificates
```

> **Note:** Firefox and Chrome do not use the system CA store. Firefox requires manual import (Settings → Privacy & Security → Certificates → View Certificates → Import). Chrome uses the NSS database and needs an extra step:
>
> ```bash
> # Install certutil if not already present
> sudo apt install libnss3-tools
>
> # Import into the current user's NSS database (used by Chrome)
> certutil -d sql:$HOME/.pki/nssdb -A -t "C,," -n "llama-cpp-hub" -i ca.crt
> ```
>
> After importing, **fully kill** Chrome (`killall chrome`) before restarting. Closing the window is not enough — Chrome processes linger in the background and the cert cache won't refresh.

### Java Client

Import `ca.crt` into JDK's truststore:

```bash
keytool -import -trustcacerts \
  -file ca.crt \
  -keystore "$JAVA_HOME/lib/security/cacerts" \
  -alias llama-cpp-hub
```

Default truststore password is `changeit`.

### curl

```bash
curl --cacert ssl/ca.crt https://localhost:8080/api/models
```

### Python requests

```python
import requests

# Option 1: specify CA file
response = requests.get(
    "https://localhost:8080/api/models",
    verify="ssl/ca.crt"
)

# Option 2: import ca.crt into system trust store, then use directly
response = requests.get("https://localhost:8080/api/models")
```

## SSL Directory Files

| File | Description |
|------|-------------|
| `keystore.p12` | PKCS12 keystore containing the server cert and private key |
| `ca.crt` | PEM-format CA certificate, distributed to clients for trust |

## FAQ

### 1. I forgot the certificate password

Just regenerate: run the `keytool -genkeypair` command from step 2 with a new password.

### 2. How to change the certificate password

```bash
keytool -storepasswd -keystore keystore.p12 -storepass oldpass -new newpass
```

### 3. Certificate expired

Regenerate with a longer `-validity` value.

### 4. HTTP and HTTPS simultaneously

The current version listens on both ports (OpenAI port and Anthropic port) when HTTPS is enabled. Set `enabled` to `true` in `application.json` to use HTTPS exclusively.

### 5. Browser still shows "Not Secure"

- Confirm `ca.crt` has been imported into **Trusted Root Certification Authorities**
- Windows: make sure you used Administrator privileges
- macOS: confirm "Always Trust" is set in Keychain
- **Linux (Chrome):** `update-ca-certificates` has **no effect** on Chrome. Chrome uses the NSS database:
  1. `sudo apt install libnss3-tools`
  2. `certutil -d sql:$HOME/.pki/nssdb -A -t "C,," -n "llama-cpp-hub" -i ca.crt`
  3. `killall chrome` (full process kill, closing the window is not enough)
  4. Reopen Chrome
- **Linux (Firefox):** `update-ca-certificates` has no effect. Manual import: Settings → Privacy & Security → Certificates → View Certificates → Import → select `ca.crt` → check "Trust this CA to identify websites"
- Restart the browser (Firefox: restart is enough; Chrome: must `killall chrome`)

### 6. Programmatic clients refuse to connect

Self-signed certs show a browser warning that you can click through. But many HTTP clients **flat-out refuse** with no bypass offered.

Common behavior:

| Client | Behavior |
|--------|----------|
| Java `HttpURLConnection` / `HttpClient` | Throws `SSLHandshakeException` |
| Python `requests` (default) | Throws `SSLError` |
| curl (without `-k`) | Returns `SSL certificate problem` |
| Node.js `fetch` / `https` module | Throws `CERT_UNTRUSTED` / `DEPTH_ZERO_SELF_SIGNED_CERT` |
| Go `http.Client` (default) | Returns `x509: certificate is valid for ...` |
| .NET `HttpClient` (validation enabled) | Throws `AuthenticationException` |

This is not a bug — the client is correctly enforcing certificate chain validation. Three solutions:

**Option A: Trust the certificate** (recommended, one-time setup)
Import `ca.crt` into the client's trust store (see Java Client / Linux / Windows sections above). Best for long-term use.

**Option B: Skip validation** (quick dev/test)
- curl: add `-k` or `--insecure`
- Python: `requests.get(url, verify=False)`
- Java: custom `TrustManager` that trusts all certs
- Node.js: `process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0'`

**Option C: Ditch self-signed, use a real certificate**
If the above sounds like too much hassle, or you want truly zero-warning operation, skip HTTPS self-signing and use HTTP + reverse proxy (nginx / Caddy):
1. Keep HTTPS disabled in this project
2. Let nginx reverse-proxy to the local HTTP port (8080)
3. Use Let's Encrypt / acme.sh to obtain a free, trusted certificate with auto-renewal

This gives you proper domain binding and zero certificate errors on any client. For LAN environments, you can pair it with a wildcard DNS service like `nip.io` — no domain ownership needed for a Let's Encrypt cert.

### 7. Using RSA instead of EC

If you prefer RSA over elliptic curve:

```bash
keytool -genkeypair -alias llama-cpp-hub \
  -keyalg RSA \
  -keysize 2048 \
  -keystore keystore.p12 \
  -storetype PKCS12 \
  -storepass changeit \
  -validity 3650 \
  -dname "CN=llama-cpp-hub, O=MyOrg, C=CN" \
  -ext "SAN=DNS:localhost,IP:127.0.0.1,IP:0.0.0.0"
```
