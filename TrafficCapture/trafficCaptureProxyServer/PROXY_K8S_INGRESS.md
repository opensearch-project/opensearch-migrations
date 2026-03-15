# Technical Design Document: High-Performance Elastic Proxy

## 1. Executive Summary

This document outlines the architecture for a high-throughput proxy service deployed on Amazon EKS. The solution is designed to handle massive payloads (10MB–100MB+) by utilizing a non-buffering, L4-direct routing strategy. By bypassing traditional Kubernetes networking bottlenecks () and leveraging AWS-native performance features (NLB IP Mode + Prefix Delegation), the system achieves near-wire-speed performance while remaining portable for open-source environments.

---

## 2. Architecture Overview

The design follows a **Direct-to-Pod** pattern. External traffic enters via an AWS Network Load Balancer (NLB) and is routed directly to individual Proxy Pod IPs, completely bypassing the node's internal NAT table.

### Key Components:

* **Entry Point:** AWS Network Load Balancer (NLB) in **IP Mode**.
* **Discovery:** Managed AWS Load Balancer Controller syncing Pod IPs to Target Groups.
* **Compute:** Proxy nodes deployed as a **Deployment/ReplicaSet** (scaling horizontally).
* **Networking:** Amazon VPC CNI with **Prefix Delegation** enabled for high density and fast startup.
* **Firewall:** Cilium (in Chaining Mode) for eBPF-based identity isolation and observability.

---

## 3. Core Design Choices & Rationale

### 3.1 NLB Routing via "IP Mode"

* **The Choice:** Utilization of `service.beta.kubernetes.io/aws-load-balancer-nlb-target-type: "ip"`.
* **Rationale:** Standard "Instance Mode" requires a "double hop." Traffic hits the node, performs NAT, and is then forwarded to a pod. For 100MB Elasticsearch payloads, this "NAT tax" consumes excessive CPU and increases latency. **IP Mode** delivers packets directly to the Pod's ENI.

### 3.2 Managed Prefix Delegation

* **The Choice:** Automatic allocation of `/28` blocks via EKS Auto Mode.
* **Rationale:** Standard ENIs are limited to a small number of secondary IPs. Prefix Delegation assigns blocks of 16 IPs to the node.
* **Benefit:** High pod density (110+ pods/node) and "warm" IP allocation are achieved. Pods transition to a "Ready" state quickly as there is no requirement to wait for the synchronous attachment of new ENIs.

### 3.3 Deployment vs. StatefulSet

* **The Choice:** Deployment with a stateless proxy.
* **Rationale:** Since the proxy consumes policies dynamically at runtime, stable network identity (`proxy-0`) is unnecessary. A Deployment allows for faster parallel scaling and more aggressive `rollingUpdate` strategies (e.g., `maxSurge: 25%`).

---

## 4. Alternatives Considered

### 4.1 Envoy / Istio

* **Why Rejected:** Envoy is a powerful L7 proxy but is highly opinionated regarding buffering. Many filters require the full request body before processing. For 100MB streams, this creates massive memory pressure and artificial "Time to First Byte" delays.
* **Use Case:** Considered only if advanced L7 features (header-based retries or mTLS termination) are required that the custom proxy cannot handle.

### 4.2 Branch ENIs (Security Groups for Pods)

* **Why Rejected:** Branch ENIs require **15–30 seconds** to attach via the AWS API. In a scaling event for an Elasticsearch proxy, this delay is unacceptable.
* **Decision:** Standard IPs are used with **Cilium Network Policies**. Identical isolation is achieved via eBPF-level filtering without startup latency or AWS API throttling risks.

---

## 5. Critical "Gotchas" & Constraints

### 5.1 The Blast Radius

Even with NLB IP Mode, remains active on the nodes. If other workloads on the same node saturate the **Conntrack table**, proxy connections may be dropped.

* **Mitigation:** Cilium is utilized for network policy enforcement to minimize reliance on legacy rules.

### 5.2 350-Second Idle Timeout

AWS NLBs have a hard 350-second idle timeout. If a request takes too long to process without sending a packet, the connection is dropped.

* **Requirement:** The custom proxy **must** implement TCP Keep-alives to maintain the connection during long-running 100MB transfers.

---

## 6. Open Source Portability

To ensure the solution remains friendly for non-AWS users:

* The application code remains agnostic of the underlying infrastructure.
* On-prem users may swap the NLB for **MetalLB** or **Cilium L2 Announcements**.
* Security rules are defined in standard **Kubernetes NetworkPolicy** objects.

---

## 7. EKS Auto Mode Networking Supplement

With EKS Auto Mode enabled with the `elasticLoadBalancing: { enabled: true }` 
feature, we already get most of what we want by simply enabling these
annotations on the Proxy service.

```
apiVersion: v1
kind: Service
metadata:
  name: proxy-service
  annotations:
    # Explicitly telling Auto Mode to use NLB
    # Note: Auto Mode often uses different annotation prefixes or defaults
    service.beta.kubernetes.org/aws-load-balancer-type: "external"
    service.beta.kubernetes.org/aws-load-balancer-nlb-target-type: "ip"
spec:
  type: LoadBalancer
  selector:
    app: my-proxy
  ports:
    - port: 9200
```

### 7.1 Managed VPC CNI & Prefix Delegation

In Auto Mode, AWS manages the VPC CNI entirely. There is no requirement (or capability) to manually patch the `aws-node` DaemonSet.

* **Out-of-the-Box Behavior:** Auto Mode enables **Prefix Delegation** by default. It attempts to allocate `/28` blocks (16 IPs) to each node to support high pod density (110 pods/node).
* **Warm Pooling:** AWS manages the `WARM_IP_TARGET` and `WARM_PREFIX_TARGET` logic internally to ensure that when the proxy scales up, IPs are ready before the pod is scheduled.
* **The "Zero-NAT" Path:** When combined with `service.beta.kubernetes.org/aws-load-balancer-nlb-target-type: "ip"`, the control plane maps pod IPs directly to the NLB Target Group.

### 7.2 What Cannot Be Changed (The Constraints)

Because AWS manages the data plane, certain "power user" knobs are restricted:

* **No Custom CNI:** A standalone Cilium or Calico installation cannot be swapped for the managed VPC CNI (though Cilium can still run in **Chaining Mode** for network policies).
* **No Manual ENI Warming:** Manual overrides for the number of ENIs or Prefixes held in reserve are not permitted.
* **No Custom AMIs:** Nodes run on AWS-managed **Bottlerocket** images. Custom kernel modules or OS-level proxy optimizations (e.g., tweaks) cannot be injected via an AMI.

### 7.3 Infrastructure Off-Ramps

A modular design is maintained to allow an "exit" from managed Auto Mode if specialized tuning becomes necessary.

| Feature | Auto Mode State | Off-Ramp Path | Impact of Transition |
| --- | --- | --- | --- |
| **Load Balancer** | Managed by Control Plane | Install **AWS LB Controller** via Helm; use `loadBalancerClass`. | Allows for custom Target Group attributes and advanced health check tuning. |
| **CNI / IPAM** | Managed VPC CNI | Move to **Karpenter** or Managed Node Groups; install standalone CNI. | Required for full Cilium CNI replacement (non-chaining) or custom kernel tuning. |
| **Compute** | Managed Node Pools | Transition to **Karpenter** nodes. | Provides control over custom AMIs and user-data for low-level OS optimizations. |

### 7.4 Cilium Integration Scenarios

* **With Auto Mode (Current):** Cilium is deployed in **Chaining Mode**. AWS handles IPAM/Routing, while Cilium provides eBPF-based security and Hubble observability. This offers a balance of managed stability and advanced visibility.
* **Without Auto Mode (Off-Ramp):** Cilium is deployed as the **Primary CNI**. This enables full replacement and high-performance BGP/L2 announcements. This path is recommended only if managed networking becomes a measurable bottleneck for 100MB streams.

### 7.5 Critical Risks & "Gotchas"

| Risk | Impact on 100MB Proxy | Mitigation Strategy |
| --- | --- | --- |
| **Subnet Fragmentation** | If a subnet lacks a contiguous `/28` block, Auto Mode falls back to **Secondary IP mode**, capping density to ~10–15 pods/node. | Monitor VPC Subnets for fragmentation. Use **Subnet Reservations** for large blocks. |
| **Node Rotation (21 Days)** | Auto Mode forces node rotation every 21 days for patching. | Ensure the proxy handles `SIGTERM` gracefully to allow 100MB streams to drain. |
| **"Admin Tier" Denials** | A known bug can occasionally assign ENIs to the *default* SG rather than the *cluster* SG, dropping traffic. | Implement robust **Retries** and use **Cilium Hubble** for immediate detection. |
| **API Throttling** | Rapid scaling triggers many AWS ENI API calls managed by the control plane. | Set Deployment `maxSurge` conservatively (e.g., 10–20%) to avoid "bursting" the API. |

---

## 8. Implementation Patch (CDK)

To support this architecture, the **EKS Pod Identity Agent** is included as a managed add-on:

```typescript
// Add this to your EKSInfra class
new CfnAddon(this, 'PodIdentityAgent', {
    clusterName: props.clusterName,
    addonName: 'eks-pod-identity-agent',
    addonVersion: 'v1.3.0-eksbuild.1', // Use the latest for 1.32
});

```
