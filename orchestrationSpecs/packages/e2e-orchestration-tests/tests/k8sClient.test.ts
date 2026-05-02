import {
    K8sClient,
    KubectlError,
    KubectlRunner,
    extractCrdObservation,
    MIGRATION_CRD_PLURALS,
} from "../src/k8sClient";

function fakeRunner(
    responses: Record<string, { stdout?: string; stderr?: string; exitCode?: number }>,
): KubectlRunner {
    return (args) => {
        const key = args.join(" ");
        const match =
            responses[key] ??
            Object.entries(responses).find(([k]) => key.startsWith(k))?.[1];
        if (!match) {
            return { stdout: "", stderr: `no fake response for: ${key}`, exitCode: 99 };
        }
        return {
            stdout: match.stdout ?? "",
            stderr: match.stderr ?? "",
            exitCode: match.exitCode ?? 0,
        };
    };
}

describe("K8sClient.getAll", () => {
    it("parses kubectl list output", () => {
        const client = new K8sClient({
            namespace: "ma",
            runner: fakeRunner({
                "get captureproxies -n ma -o json": {
                    stdout: JSON.stringify({
                        items: [
                            { metadata: { name: "source-proxy" }, status: { phase: "Ready" } },
                        ],
                    }),
                },
            }),
        });
        const result = client.getAll("captureproxies");
        expect(result.items).toHaveLength(1);
    });

    it("throws KubectlError on non-zero exit", () => {
        const client = new K8sClient({
            namespace: "ma",
            runner: fakeRunner({
                "get captureproxies": { exitCode: 1, stderr: "boom" },
            }),
        });
        expect(() => client.getAll("captureproxies")).toThrow(KubectlError);
    });

    it("throws KubectlError on unparseable stdout", () => {
        const client = new K8sClient({
            namespace: "ma",
            runner: fakeRunner({
                "get captureproxies": { stdout: "not json" },
            }),
        });
        expect(() => client.getAll("captureproxies")).toThrow(KubectlError);
    });
});

describe("K8sClient.getOne", () => {
    it("returns null on NotFound", () => {
        const client = new K8sClient({
            namespace: "ma",
            runner: fakeRunner({
                "get captureproxies source-proxy": {
                    exitCode: 1,
                    stderr: 'Error from server (NotFound): captureproxies.x "source-proxy" not found',
                },
            }),
        });
        expect(client.getOne("captureproxies", "source-proxy")).toBeNull();
    });

    it("returns parsed resource on success", () => {
        const client = new K8sClient({
            namespace: "ma",
            runner: fakeRunner({
                "get captureproxies source-proxy": {
                    stdout: JSON.stringify({ metadata: { name: "source-proxy" } }),
                },
            }),
        });
        expect(client.getOne("captureproxies", "source-proxy")).toEqual({
            metadata: { name: "source-proxy" },
        });
    });
});

describe("K8sClient.applyBasicAuthSecret", () => {
    it("applies an opaque secret manifest through stdin", () => {
        const calls: { args: readonly string[]; input?: string }[] = [];
        const client = new K8sClient({
            namespace: "ma",
            runner: (args, opts) => {
                calls.push({ args, input: opts?.input });
                return { stdout: "secret/source-creds configured", stderr: "", exitCode: 0 };
            },
        });

        client.applyBasicAuthSecret("source-creds", {
            username: "admin",
            password: "secret",
        });

        expect(calls).toHaveLength(1);
        expect(calls[0].args).toEqual(["apply", "-f", "-"]);
        expect(calls[0].input).toContain("kind: Secret");
        expect(calls[0].input).toContain("name: source-creds");
        expect(calls[0].input).toContain("namespace: ma");
        expect(calls[0].input).toContain("username: admin");
        expect(calls[0].input).toContain("password: secret");
    });

    it("throws KubectlError when apply fails", () => {
        const client = new K8sClient({
            namespace: "ma",
            runner: () => ({ stdout: "", stderr: "apply failed", exitCode: 1 }),
        });
        expect(() =>
            client.applyBasicAuthSecret("source-creds", {
                username: "admin",
                password: "secret",
            }),
        ).toThrow(KubectlError);
    });
});

describe("K8sClient.deleteResourceAndWait", () => {
    it("deletes a named resource with wait and ignore-not-found", () => {
        const calls: { args: readonly string[] }[] = [];
        const client = new K8sClient({
            namespace: "ma",
            runner: (args) => {
                calls.push({ args });
                return { stdout: "workflow deleted", stderr: "", exitCode: 0 };
            },
        });

        client.deleteResourceAndWait("workflows.argoproj.io", "migration-workflow");

        expect(calls[0].args).toEqual([
            "delete",
            "workflows.argoproj.io",
            "migration-workflow",
            "-n",
            "ma",
            "--ignore-not-found",
            "--wait=true",
            "--timeout=60s",
        ]);
    });

    it("throws KubectlError when delete fails", () => {
        const client = new K8sClient({
            namespace: "ma",
            runner: () => ({ stdout: "", stderr: "delete failed", exitCode: 1 }),
        });

        expect(() =>
            client.deleteResourceAndWait("workflows.argoproj.io", "migration-workflow"),
        ).toThrow(KubectlError);
    });
});

describe("extractCrdObservation", () => {
    it("maps a CaptureProxy item to a ComponentId and phase", () => {
        const item = {
            metadata: { name: "source-proxy", uid: "uid-1", generation: 3 },
            spec: { dependsOn: ["capturedtraffic:source-proxy-topic"] },
            status: { phase: "Ready", configChecksum: "status-checksum" },
        };
        const obs = extractCrdObservation("captureproxies", item);
        expect(obs).toMatchObject({
            componentId: "captureproxy:source-proxy",
            phase: "Ready",
            configChecksum: "status-checksum",
            uid: "uid-1",
            generation: 3,
            dependsOn: ["capturedtraffic:source-proxy-topic"],
        });
    });

    it("uses the config checksum annotation as a defensive fallback", () => {
        const obs = extractCrdObservation("captureproxies", {
            metadata: {
                name: "source-proxy",
                annotations: {
                    "migrations.opensearch.org/config-checksum": "annotation-checksum",
                },
            },
            status: { phase: "Ready" },
        });
        expect(obs?.configChecksum).toBe("annotation-checksum");
    });

    it("prefers status.configChecksum over the fallback annotation", () => {
        const obs = extractCrdObservation("captureproxies", {
            metadata: {
                name: "source-proxy",
                annotations: {
                    "migrations.opensearch.org/config-checksum": "annotation-checksum",
                },
            },
            status: { phase: "Ready", configChecksum: "status-checksum" },
        });
        expect(obs?.configChecksum).toBe("status-checksum");
    });

    it("returns null when metadata.name is missing", () => {
        expect(extractCrdObservation("captureproxies", { metadata: {} })).toBeNull();
    });

    it("defaults phase to Unknown if status.phase is absent", () => {
        const obs = extractCrdObservation("captureproxies", {
            metadata: { name: "x" },
        });
        expect(obs?.phase).toBe("Unknown");
    });

    it("covers every MIGRATION_CRD_PLURALS kind with a readable componentId prefix", () => {
        for (const p of MIGRATION_CRD_PLURALS) {
            const obs = extractCrdObservation(p, { metadata: { name: "foo" }, status: { phase: "Ready" } });
            expect(obs?.componentId).toMatch(/^[a-z][a-z0-9-]*:foo$/);
        }
    });
});
