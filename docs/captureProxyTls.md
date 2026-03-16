# TLS Certificates for the Capture Proxy

The Capture Proxy can terminate TLS so that clients connect over HTTPS. On Kubernetes
deployments, certificate provisioning is handled automatically by
[cert-manager](https://cert-manager.io/) and integrated into the migration workflow.

## How it works

When you configure `tls` in your proxy config, the migration workflow will:

1. Create a cert-manager `Certificate` resource referencing your chosen issuer
2. Wait for the certificate to be issued and the TLS secret to be created
3. Mount the secret into the proxy pod at `/etc/proxy-tls/`
4. Start the proxy with `--sslCertChainFile` and `--sslKeyFile` pointing at the mounted PEM files

The proxy reads PEM files directly via Netty — no OpenSearch security plugin is involved.

## Configuration

Add a `tls` block to your proxy config:

```yaml
traffic:
  proxies:
    capture-proxy:
      source: "source"
      proxyConfig:
        listenPort: 9201
        tls:
          mode: "certManager"
          issuerRef:
            name: "migrations-ca"       # or "aws-pca-issuer" for AWS PCA
            kind: "ClusterIssuer"
            group: "cert-manager.io"    # or "awspca.cert-manager.io" for PCA
          dnsNames:
            - "capture-proxy.ma.svc.cluster.local"
            - "capture-proxy"
          duration: "2160h"             # optional, default 90 days
          renewBefore: "360h"           # optional, default 15 days
```

### TLS modes

| Mode | `tls.mode` | Description |
|---|---|---|
| cert-manager | `certManager` | Workflow creates a Certificate resource and waits for it to be issued |
| Existing secret | `existingSecret` | Workflow mounts a pre-existing K8s TLS secret (no cert-manager needed) |
| No TLS | *(omit `tls`)* | Proxy runs without TLS |

For `existingSecret` mode:

```yaml
        tls:
          mode: "existingSecret"
          secretName: "my-proxy-tls-cert"
```

The secret must contain `tls.crt` and `tls.key` keys in PEM format.

## Issuers

The proxy doesn't care which issuer signs the certificate — it just reads PEM files.
The issuer is configured in the `issuerRef` field and must exist on the cluster before
the workflow runs.

### Built-in CA issuer (default, all environments)

The helm chart automatically creates a local CA chain:

- `migrations-selfsigned-bootstrap` — a self-signed ClusterIssuer used only to create the root CA
- `migrations-root-ca` — a root CA Certificate stored in `migrations-root-ca-secret`
- `migrations-ca` — a CA ClusterIssuer that signs proxy certs using the root CA

This is created automatically during `helm install`/`helm upgrade` when cert-manager is
enabled (the default). No configuration is needed.

To use it, set `issuerRef.name: "migrations-ca"` in your proxy config.

**Trusting the CA:** To connect to the proxy without `--insecure`, clients need the CA
certificate:

```bash
kubectl -n ma get secret migrations-root-ca-secret \
  -o jsonpath='{.data.ca\.crt}' | base64 -d > migrations-ca.crt

# Then use it with curl:
curl --cacert migrations-ca.crt https://capture-proxy:9201/
```

### AWS Private CA issuer (EKS)

For production deployments on EKS, you can use
[AWS Private Certificate Authority](https://docs.aws.amazon.com/privateca/latest/userguide/PcaWelcome.html)
to sign proxy certificates with a real CA.

**Prerequisites:**

1. An active ACM PCA (root or subordinate)
2. The `aws-privateca-issuer` controller installed on the cluster
3. A Pod Identity Association for the `aws-pca-issuer` service account
4. An `AWSPCAClusterIssuer` resource pointing at your PCA ARN

**Using the bootstrap script:**

```bash
./aws-bootstrap.sh \
  --tls-mode pca-existing \
  --pca-arn "arn:aws:acm-pca:us-east-2:123456789:certificate-authority/abc-123" \
  ...
```

This handles steps 2–4 automatically (installs the controller, creates the Pod Identity
Association, and configures the issuer via helm).

**Manual setup:**

```bash
# Install the controller
helm install aws-pca-issuer \
  --repo https://cert-manager.github.io/aws-privateca-issuer \
  -n ma aws-privateca-issuer \
  --set serviceAccount.name=aws-pca-issuer

# Create Pod Identity Association
aws eks create-pod-identity-association \
  --cluster-name <cluster> --namespace ma \
  --service-account aws-pca-issuer --role-arn <role-with-acm-pca-permissions>

# Restart the controller to pick up credentials
kubectl -n ma rollout restart deployment -l app.kubernetes.io/name=aws-privateca-issuer

# Create the issuer
kubectl apply -f - <<EOF
apiVersion: awspca.cert-manager.io/v1beta1
kind: AWSPCAClusterIssuer
metadata:
  name: aws-pca-issuer
spec:
  arn: arn:aws:acm-pca:us-east-2:123456789:certificate-authority/abc-123
  region: us-east-2
EOF
```

Then set `issuerRef.name: "aws-pca-issuer"` and `issuerRef.group: "awspca.cert-manager.io"`
in your proxy config.

**Known issue:** cert-manager 1.17 requires CertificateRequests to be approved before
external issuers process them. The built-in approver does not auto-approve for the
`awspca.cert-manager.io` group. You may need to install the
[cert-manager-approver-policy](https://cert-manager.io/docs/policy/approval/approver-policy/)
controller and create a policy that allows the PCA issuer. See the
[plan document](../orchestrationSpecs/PLAN_proxy_tls_certificate_management.md) for details.

## Non-K8s deployments

For non-K8s deployments (Docker, bare metal), the proxy supports loading TLS directly
from PEM files via CLI flags:

```
--sslCertChainFile /path/to/cert.pem
--sslKeyFile /path/to/key.pem
--sslTrustCertFile /path/to/ca.pem    # optional, for mutual TLS
```

The legacy `--sslConfigFile` flag (OpenSearch security plugin YAML format) is still
supported for backward compatibility but is not recommended for new deployments.

## Sample configurations

- [`proxyWithSelfSignedTls.wf.yaml`](../orchestrationSpecs/packages/config-processor/scripts/samples/proxyWithSelfSignedTls.wf.yaml) — local CA issuer (minikube, non-AWS)
- [`proxyWithPcaTls.wf.yaml`](../orchestrationSpecs/packages/config-processor/scripts/samples/proxyWithPcaTls.wf.yaml) — AWS PCA issuer (EKS)
