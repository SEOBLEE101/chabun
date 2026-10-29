# Ephemeral TLS test fixture

`ephemeral-tls.mjs` generates a new RSA key and self-signed certificate using Node
crypto for each test process. There is no bundled private key, OpenSSL dependency,
external network call or system-trust change. The fixed certificate template is
test-only, with SANs `localhost` and `127.0.0.1` and a 2020–2120 validity window.
Certificate signature and SANs are checked before use. TLS tests still require
normal certificate/hostname verification and explicitly trust only that process's
generated certificate.

PEM files for the HTTPS entry-point test live in an OS temporary directory, with
owner-only modes where supported; they are removed when the test process exits.
Do not copy these files into a package or trust them on a device. Runtime relay
credentials remain outside the repository. `PUBLIC-TEST-ONLY-cert.pem` is a
historical public certificate; no current test relies on it or a matching key.
