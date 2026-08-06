# Bundled intermediate certificates

`mevzuat.gov.tr` and `resmigazete.gov.tr` present only their leaf certificate and
omit the issuing intermediate. Browsers paper over this by downloading the
intermediate from the certificate's AuthorityInfoAccess extension; the JDK does
not, so every request fails with:

```
PKIX path building failed: unable to find valid certification path to requested target
```

The certificates here are appended to the chain the server sends so the JDK can
build a path (see `openmevzuat.tls`). They are not trust anchors — the completed
chain is still validated against the JDK trust store, and hostname verification
is unchanged.

| File | Subject | Issuer | Expires |
| --- | --- | --- | --- |
| `geotrust-tls-rsa-ca-g1.pem` | `CN=GeoTrust TLS RSA CA G1, OU=www.digicert.com, O=DigiCert Inc, C=US` | `CN=DigiCert Global Root G2` | 2027-11-02 |

SHA-256 fingerprint of `geotrust-tls-rsa-ca-g1.pem`:

```
C0:6E:30:7F:7C:FC:1D:32:FA:72:A4:C0:33:C8:7B:90:01:9A:F2:16:F0:77:5D:64:97:8A:2E:CA:6C:8A:23:0E
```

## When the source rotates to a new issuer

The update run fails with `TLS certificate validation failed for source URL`.
Read the new issuer's `CA Issuers` URI from the leaf certificate, download it,
and convert it to PEM:

```sh
openssl s_client -connect www.mevzuat.gov.tr:443 -servername www.mevzuat.gov.tr </dev/null \
  | openssl x509 -noout -text | grep -A2 'Authority Information Access'

curl -sO http://cacerts.example/<intermediate>.crt
openssl x509 -inform DER -in <intermediate>.crt -outform PEM -out resources/certs/<name>.pem
```

Add the file to `openmevzuat.tls/bundled-certificate-resources`, record its
fingerprint above, and confirm the chain still validates against a public root —
`openmevzuat.tls-test/bundled-intermediates-chain-to-a-trusted-root` checks this.
