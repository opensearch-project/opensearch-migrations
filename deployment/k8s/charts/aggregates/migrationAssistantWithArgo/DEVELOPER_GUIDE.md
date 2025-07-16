# Developer Guide
Guidance for developing components of the Migration Assistant helm chart

### Helm install and uninstall phase usage
```mermaid
flowchart TD
    subgraph "Helm Install"
        A1["Perform helm install"]
        A2a["pre-install [Weight -5]\n Setup Service Account and Cluster Role for installer"]
        A2b["pre-install [Weight -4]\n Setup Cluster Role Binding for installer"]
        A2c["pre-install [Weight -1]\n Install configmaps and PVCs needed by external or chart resources (Fluent Bit Configmap, Logs PVC)"]
        A2d["pre-install [Weight 0]\n Install job (external charts)"]
        A3["Install resources defined specifically in this chart and not annotated with an install hook\ne.g., Migration Console Stateful Set, Cluster Roles"]
        A4["post-install hook\ne.g., run init jobs, send notifications"]
        A5["End of install"]

        A1 --> A2a --> A2b --> A2c --> A2d --> A3 --> A4 --> A5
    end
````

### Debugging Fluent Bit logs
Since Fluent Bit will also send its own pod logs to the configured destination in K8s, it can be a bit tricky to have Fluent Bit log to stdout/stderr for quick debugging as this will create a destructive loop of reading and writing its own logs by the Fluent Bit pod. A workaround for this follows where the logs for the Fluent Bit pods are ignored and a `stdout` OUTPUT is added. 

1. The existing INPUT section is modified to exclude Fluent Bit container logs
```yaml
          [INPUT]
              ...
              Exclude_Path     /var/log/containers/fluent-bit*
              ...
```
2. Add a `stdout` OUTPUT option
```yaml
          [OUTPUT]
              Name stdout
              Match fluentbit-*
              Format json_lines
```
3. View the outgoing logs
```shell
kubectl -n ma logs fluent-bit-<pod_id>
```