import {DNS_NAME_PATTERN, OVERALL_MIGRATION_CONFIG, zodSchemaToJsonSchema} from "../src";

function findExternalRefSchema(root: any, purpose: string): any | undefined {
    const stack = [root];
    while (stack.length) {
        const node = stack.pop();
        if (!node || typeof node !== "object") {
            continue;
        }
        if (node["x-external-ref"]?.purpose === purpose) {
            return node;
        }
        for (const value of Object.values(node)) {
            if (value && typeof value === "object") {
                stack.push(value);
            }
        }
    }
    return undefined;
}

describe("workflow schema UI hints", () => {
    const schema = zodSchemaToJsonSchema(OVERALL_MIGRATION_CONFIG);

    it("exports top-level collection add hints", () => {
        expect(schema.properties.sourceClusters["x-ui-hint"]).toMatchObject({
            kind: "record",
            addLabel: "source cluster",
        });
        expect(schema.properties.targetClusters["x-ui-hint"]).toMatchObject({
            kind: "record",
            addLabel: "target cluster",
        });
        expect(schema.properties.kafkaClusterConfiguration["x-ui-hint"]).toMatchObject({
            kind: "record",
            addLabel: "Kafka cluster",
            keyFormat: "k8s-name",
        });
        expect(schema.properties.snapshotMigrationConfigs["x-ui-hint"]).toMatchObject({
            kind: "array",
            addLabel: "snapshot migration",
        });
    });

    it("exports scalar and reference edit hints for nested fields", () => {
        expect(schema.properties.sourceClusters.additionalProperties.properties.endpoint["x-ui-hint"]).toMatchObject({
            kind: "text",
            format: "optional-http-endpoint",
        });
        expect(schema.properties.targetClusters.additionalProperties.properties.endpoint["x-ui-hint"]).toMatchObject({
            kind: "text",
            format: "http-endpoint",
        });
        expect(schema.properties.sourceClusters.additionalProperties.properties.version["x-ui-hint"]).toMatchObject({
            kind: "text",
            format: "cluster-version",
        });
        expect(schema.properties.traffic.properties.proxies.additionalProperties.properties.source["x-ui-hint"]).toMatchObject({
            kind: "reference",
            sourcePath: ["sourceClusters"],
        });
        expect(schema.properties.traffic.properties.replayers.additionalProperties.properties.toTarget["x-ui-hint"]).toMatchObject({
            kind: "reference",
            sourcePath: ["targetClusters"],
        });
    });

    it("exports external reference hints for HTTP basic auth secrets", () => {
        const authConfig = schema.properties.sourceClusters.additionalProperties.properties.authConfig;
        const basicSecret = authConfig.anyOf
            .find((branch: any) => branch.properties?.basic)
            .properties.basic.properties.secretName;

        expect(basicSecret["x-external-ref"]).toMatchObject({
            kind: "kubernetesResource",
            purpose: "http-basic-auth",
            displayName: "HTTP Basic Auth Secret",
            matchProfiles: ["http-basic-auth-secret"],
            selection: {target: "scalarName"},
            k8s: {
                resourceTypes: [{group: "", version: "v1", kind: "Secret", namespaced: true}],
                match: {
                    acceptedSecretTypes: ["kubernetes.io/basic-auth", "Opaque"],
                    requiredKeys: ["username", "password"],
                },
            },
            create: {
                label: "HTTP Basic Auth Secret",
                apply: {target: "scalarName", nameField: "secretName"},
            },
        });
    });

    it("exports cert-manager issuer references as Kubernetes object refs", () => {
        const issuerRef = findExternalRefSchema(schema, "cert-manager-issuer");

        expect(issuerRef?.["x-external-ref"]).toMatchObject({
            kind: "kubernetesResource",
            purpose: "cert-manager-issuer",
            selection: {target: "objectRef"},
            k8s: {
                resourceTypes: [
                    {group: "cert-manager.io", version: "v1", kind: "Issuer", namespaced: true},
                    {group: "cert-manager.io", version: "v1", kind: "ClusterIssuer", namespaced: false},
                    {group: "awspca.cert-manager.io", version: "v1beta1", kind: "AWSPCAClusterIssuer", namespaced: false},
                ],
            },
        });
    });

    it("exports scalar item hints for DNS name arrays", () => {
        const proxyConfig = schema.properties.traffic.properties.proxies.additionalProperties.properties.proxyConfig;
        const certManagerTls = proxyConfig.properties.tls.oneOf
            .find((branch: any) => branch.properties?.mode?.enum?.includes("certManager"));
        const dnsNameItem = certManagerTls.properties.dnsNames.items;

        expect(certManagerTls.properties.dnsNames["x-ui-hint"]).toMatchObject({
            kind: "array",
            addLabel: "DNS name",
        });
        expect(dnsNameItem).toMatchObject({
            type: "string",
            pattern: DNS_NAME_PATTERN,
            maxLength: 253,
            "x-ui-hint": {
                kind: "text",
                pattern: DNS_NAME_PATTERN,
                message: expect.stringContaining("Use a DNS name"),
            },
        });
    });

    it("exports expert field hints from authored schema descriptions", () => {
        const proxyConfig = schema.properties.traffic.properties.proxies.additionalProperties.properties.proxyConfig;

        expect(proxyConfig.properties.serviceType["x-expert"]).toBe(true);
        expect(proxyConfig.properties.podReplicas["x-expert"]).toBeUndefined();
    });

    it("exports effective defaults for fields resolved outside the raw schema", () => {
        const kafkaConfig = schema.properties.kafkaClusterConfiguration.additionalProperties;
        const autoCreate = kafkaConfig.anyOf
            .find((branch: any) => branch.properties?.autoCreate)
            .properties.autoCreate;

        expect(autoCreate.properties.auth["x-effective-default"]).toMatchObject({
            label: "scram-sha-512",
            value: {type: "scram-sha-512"},
            source: "workflow policy",
        });
    });
});
