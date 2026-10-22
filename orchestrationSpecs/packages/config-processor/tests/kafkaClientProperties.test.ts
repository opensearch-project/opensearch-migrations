import {
    buildKafkaClientPropertiesFileContent,
    renderJavaProperties,
} from "../src/kafkaClientProperties";

function splitAtSeparator(line: string): [string, string] {
    let escaped = false;
    for (let i = 0; i < line.length; i++) {
        const ch = line[i];
        if (escaped) {
            escaped = false;
            continue;
        }
        if (ch === "\\") {
            escaped = true;
            continue;
        }
        if (ch === "=") {
            return [line.slice(0, i), line.slice(i + 1)];
        }
    }
    throw new Error(`No Java properties separator found in rendered line: ${line}`);
}

function decodeEscapedJavaPropertiesPart(input: string): string {
    let out = "";
    for (let i = 0; i < input.length; i++) {
        const ch = input[i];
        if (ch !== "\\") {
            out += ch;
            continue;
        }

        const escaped = input[++i];
        if (escaped === "n") out += "\n";
        else if (escaped === "r") out += "\r";
        else if (escaped === "t") out += "\t";
        else if (escaped === "f") out += "\f";
        else if (escaped === "u") {
            const hex = input.slice(i + 1, i + 5);
            out += String.fromCharCode(parseInt(hex, 16));
            i += 4;
        } else {
            out += escaped;
        }
    }
    return out;
}

function decodeRenderedProperties(propertiesText: string): Record<string, string> {
    return Object.fromEntries(propertiesText.trimEnd().split("\n").filter(Boolean).map(line => {
        const [key, value] = splitAtSeparator(line);
        return [
            decodeEscapedJavaPropertiesPart(key),
            decodeEscapedJavaPropertiesPart(value),
        ];
    }));
}

describe("Kafka client properties rendering", () => {
    it("renders deterministic Java properties text with escaped keys and scalar values", () => {
        const properties = {
            " leading key": " leading value",
            "compression.type": "lz4",
            "contains:separator": "value=with:separators",
            "emoji": "snowman-\u2603",
            "line\nbreak": "tab\tand\nnewline",
            "truthy": true,
            "max.request.size": 8388608,
            "#comment-like": "!value-like",
        };

        const rendered = renderJavaProperties(properties);

        expect(rendered).toBe([
            "\\ leading\\ key=\\ leading value",
            "\\#comment-like=\\!value-like",
            "compression.type=lz4",
            "contains\\:separator=value=with:separators",
            "emoji=snowman-\\u2603",
            "line\\nbreak=tab\\tand\\nnewline",
            "max.request.size=8388608",
            "truthy=true",
            "",
        ].join("\n"));
        expect(decodeRenderedProperties(rendered)).toEqual(
            Object.fromEntries(Object.entries(properties).map(([key, value]) => [key, String(value)]))
        );
    });

    it.each([
        "security.protocol",
        "sasl.mechanism",
        "sasl.jaas.config",
        "sasl.client.callback.handler.class",
        "sasl.login.callback.handler.class",
        "sasl.login.class",
    ])("protects authentication selector %s even when auth is disabled", protectedKey => {
        expect(() => buildKafkaClientPropertiesFileContent({
            role: "producer",
            clusterKey: "external",
            kafkaConfig: {
                kafkaConnection: "broker:9092",
                kafkaTopic: "traffic",
                authType: "none",
            } as any,
            userProperties: {[protectedKey]: "user-controlled"},
        })).toThrow(/workflow-owned/);
    });

    it("renders TLS trust configuration plus user tuning for SSL-only clusters", () => {
        expect(buildKafkaClientPropertiesFileContent({
            role: "consumer",
            clusterKey: "external",
            kafkaConfig: {
                kafkaConnection: "broker:9093",
                kafkaTopic: "traffic",
                authType: "ssl",
                caSecretName: "external-kafka-ca",
            } as any,
            userProperties: {"fetch.min.bytes": 1024},
        })).toBe([
            "fetch.min.bytes=1024",
            "ssl.truststore.location=/config/kafka-ca/ca.crt",
            "ssl.truststore.type=PEM",
            "",
        ].join("\n"));
    });

    it("directly renders the workflow-generated SCRAM trust configuration with no user properties", () => {
        expect(buildKafkaClientPropertiesFileContent({
            role: "producer",
            clusterKey: "managed",
            kafkaConfig: {
                kafkaConnection: "managed-kafka-bootstrap:9093",
                kafkaTopic: "traffic",
                authType: "scram-sha-512",
                caSecretName: "managed-cluster-ca-cert",
                managedByWorkflow: true,
            } as any,
        })).toBe([
            "ssl.truststore.location=/config/kafka-ca/ca.crt",
            "ssl.truststore.type=PEM",
            "",
        ].join("\n"));
    });
});
