var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
var __generator = (this && this.__generator) || function (thisArg, body) {
    var _ = { label: 0, sent: function() { if (t[0] & 1) throw t[1]; return t[1]; }, trys: [], ops: [] }, f, y, t, g = Object.create((typeof Iterator === "function" ? Iterator : Object).prototype);
    return g.next = verb(0), g["throw"] = verb(1), g["return"] = verb(2), typeof Symbol === "function" && (g[Symbol.iterator] = function() { return this; }), g;
    function verb(n) { return function (v) { return step([n, v]); }; }
    function step(op) {
        if (f) throw new TypeError("Generator is already executing.");
        while (g && (g = 0, op[0] && (_ = 0)), _) try {
            if (f = 1, y && (t = op[0] & 2 ? y["return"] : op[0] ? y["throw"] || ((t = y["return"]) && t.call(y), 0) : y.next) && !(t = t.call(y, op[1])).done) return t;
            if (y = 0, t) op = [op[0] & 2, t.value];
            switch (op[0]) {
                case 0: case 1: t = op; break;
                case 4: _.label++; return { value: op[1], done: false };
                case 5: _.label++; y = op[1]; op = [0]; continue;
                case 7: op = _.ops.pop(); _.trys.pop(); continue;
                default:
                    if (!(t = _.trys, t = t.length > 0 && t[t.length - 1]) && (op[0] === 6 || op[0] === 2)) { _ = 0; continue; }
                    if (op[0] === 3 && (!t || (op[1] > t[0] && op[1] < t[3]))) { _.label = op[1]; break; }
                    if (op[0] === 6 && _.label < t[1]) { _.label = t[1]; t = op; break; }
                    if (t && _.label < t[2]) { _.label = t[2]; _.ops.push(op); break; }
                    if (t[2]) _.ops.pop();
                    _.trys.pop(); continue;
            }
            op = body.call(thisArg, _);
        } catch (e) { op = [6, e]; y = 0; } finally { f = t = 0; }
        if (op[0] & 5) throw op[1]; return { value: op[0] ? op[1] : void 0, done: true };
    }
};
import { K3sContainer } from "@testcontainers/k3s";
import { KubeConfig, CoreV1Api, AppsV1Api, RbacAuthorizationV1Api } from "@kubernetes/client-node";
import * as fs from "fs";
var KUBECONFIG_PATH = "/tmp/integ-test-kubeconfig.yaml";
var META_PATH = "/tmp/integ-test-meta.json";
var TEST_NAMESPACE = "integ-test";
var ARGO_NAMESPACE = "argo";
var container = null;
var kubeConfigContent = null;
export function startCluster() {
    return __awaiter(this, void 0, void 0, function () {
        var kc, coreApi, argoVersion, manifestUrl, execSync, tempFile, applyResult, crdCheck, appsApi, timeout, deployment, err_1, rbacApi;
        var _a, _b;
        return __generator(this, function (_c) {
            switch (_c.label) {
                case 0:
                    console.log("Starting K3s container...");
                    return [4 /*yield*/, new K3sContainer("rancher/k3s:v1.31.6-k3s1")
                            .withCommand(["server", "--disable=traefik"])
                            .start()];
                case 1:
                    container = _c.sent();
                    kubeConfigContent = container.getKubeConfig();
                    // Write kubeconfig to temp file for test processes
                    fs.writeFileSync(KUBECONFIG_PATH, kubeConfigContent);
                    fs.writeFileSync(META_PATH, JSON.stringify({
                        namespace: TEST_NAMESPACE,
                        argoNamespace: ARGO_NAMESPACE,
                    }));
                    console.log("K3s started, creating namespaces...");
                    kc = new KubeConfig();
                    kc.loadFromString(kubeConfigContent);
                    coreApi = kc.makeApiClient(CoreV1Api);
                    // Create namespaces
                    return [4 /*yield*/, coreApi.createNamespace({
                            body: {
                                metadata: { name: ARGO_NAMESPACE },
                            },
                        })];
                case 2:
                    // Create namespaces
                    _c.sent();
                    return [4 /*yield*/, coreApi.createNamespace({
                            body: {
                                metadata: { name: TEST_NAMESPACE },
                            },
                        })];
                case 3:
                    _c.sent();
                    console.log("Installing Argo Workflows...");
                    argoVersion = "v4.0.0";
                    manifestUrl = "https://github.com/argoproj/argo-workflows/releases/download/".concat(argoVersion, "/quick-start-minimal.yaml");
                    return [4 /*yield*/, import("child_process")];
                case 4:
                    execSync = (_c.sent()).execSync;
                    tempFile = "/tmp/argo-install-manifest.yaml";
                    execSync("curl -sL -o ".concat(tempFile, " ").concat(manifestUrl));
                    return [4 /*yield*/, container.copyFilesToContainer([{
                                source: tempFile,
                                target: "/tmp/argo-manifest.yaml",
                            }])];
                case 5:
                    _c.sent();
                    console.log("Argo ".concat(argoVersion, " manifest downloaded and copied to container"));
                    return [4 /*yield*/, container.exec(["kubectl", "apply", "--server-side", "-f", "/tmp/argo-manifest.yaml"])];
                case 6:
                    applyResult = _c.sent();
                    console.log("Manifest applied");
                    if (applyResult.exitCode !== 0) {
                        console.error("kubectl apply failed:", applyResult.output);
                        throw new Error("Failed to apply Argo manifest: ".concat(applyResult.output));
                    }
                    return [4 /*yield*/, container.exec(["kubectl", "get", "crd", "workflows.argoproj.io"])];
                case 7:
                    crdCheck = _c.sent();
                    if (crdCheck.exitCode !== 0) {
                        console.error("Workflow CRD not found:", crdCheck.output);
                        throw new Error("Workflow CRD was not installed");
                    }
                    console.log("Workflow CRD verified");
                    console.log("Waiting for Argo controller to be ready...");
                    appsApi = kc.makeApiClient(AppsV1Api);
                    timeout = Date.now() + 120000;
                    _c.label = 8;
                case 8:
                    if (!(Date.now() < timeout)) return [3 /*break*/, 14];
                    _c.label = 9;
                case 9:
                    _c.trys.push([9, 11, , 12]);
                    return [4 /*yield*/, appsApi.readNamespacedDeployment({
                            name: "workflow-controller",
                            namespace: ARGO_NAMESPACE,
                        })];
                case 10:
                    deployment = _c.sent();
                    if (((_b = (_a = deployment.status) === null || _a === void 0 ? void 0 : _a.readyReplicas) !== null && _b !== void 0 ? _b : 0) >= 1) {
                        console.log("Argo controller is ready");
                        return [3 /*break*/, 14];
                    }
                    return [3 /*break*/, 12];
                case 11:
                    err_1 = _c.sent();
                    return [3 /*break*/, 12];
                case 12: return [4 /*yield*/, new Promise(function (resolve) { return setTimeout(resolve, 2000); })];
                case 13:
                    _c.sent();
                    return [3 /*break*/, 8];
                case 14: 
                // Create ServiceAccount and ClusterRoleBinding for test workflows
                return [4 /*yield*/, coreApi.createNamespacedServiceAccount({
                        namespace: TEST_NAMESPACE,
                        body: {
                            metadata: { name: "test-runner" },
                        },
                    })];
                case 15:
                    // Create ServiceAccount and ClusterRoleBinding for test workflows
                    _c.sent();
                    rbacApi = kc.makeApiClient(RbacAuthorizationV1Api);
                    return [4 /*yield*/, rbacApi.createClusterRoleBinding({
                            body: {
                                metadata: { name: "test-runner-admin" },
                                roleRef: {
                                    apiGroup: "rbac.authorization.k8s.io",
                                    kind: "ClusterRole",
                                    name: "cluster-admin",
                                },
                                subjects: [{
                                        kind: "ServiceAccount",
                                        name: "test-runner",
                                        namespace: TEST_NAMESPACE,
                                    }],
                            },
                        })];
                case 16:
                    _c.sent();
                    console.log("Cluster setup complete");
                    return [2 /*return*/];
            }
        });
    });
}
export function stopCluster() {
    return __awaiter(this, void 0, void 0, function () {
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    if (!container) return [3 /*break*/, 2];
                    console.log("Stopping K3s container...");
                    return [4 /*yield*/, container.stop()];
                case 1:
                    _a.sent();
                    container = null;
                    _a.label = 2;
                case 2:
                    // Clean up temp files
                    if (fs.existsSync(KUBECONFIG_PATH)) {
                        fs.unlinkSync(KUBECONFIG_PATH);
                    }
                    if (fs.existsSync(META_PATH)) {
                        fs.unlinkSync(META_PATH);
                    }
                    console.log("Cluster stopped");
                    return [2 /*return*/];
            }
        });
    });
}
export function getKubeConfig() {
    if (kubeConfigContent) {
        return kubeConfigContent;
    }
    if (fs.existsSync(KUBECONFIG_PATH)) {
        return fs.readFileSync(KUBECONFIG_PATH, "utf-8");
    }
    throw new Error("Kubeconfig not available. Cluster may not be started.");
}
export function getTestNamespace() {
    var _a;
    if (fs.existsSync(META_PATH)) {
        return (_a = JSON.parse(fs.readFileSync(META_PATH, "utf-8")).namespace) !== null && _a !== void 0 ? _a : TEST_NAMESPACE;
    }
    return TEST_NAMESPACE;
}
export function getArgoNamespace() {
    var _a;
    if (fs.existsSync(META_PATH)) {
        return (_a = JSON.parse(fs.readFileSync(META_PATH, "utf-8")).argoNamespace) !== null && _a !== void 0 ? _a : ARGO_NAMESPACE;
    }
    return ARGO_NAMESPACE;
}
export function getServiceAccountName() {
    var _a;
    if (fs.existsSync(META_PATH)) {
        return (_a = JSON.parse(fs.readFileSync(META_PATH, "utf-8")).serviceAccountName) !== null && _a !== void 0 ? _a : "test-runner";
    }
    return "test-runner";
}
