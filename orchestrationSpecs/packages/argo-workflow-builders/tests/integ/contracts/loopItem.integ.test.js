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
import { submitAndWait } from "../infra/workflowRunner";
import { getTestNamespace } from "../infra/argoCluster";
describe("Loop and Item Contract Tests", function () {
    test("withItems iterates over array - item is string", function () { return __awaiter(void 0, void 0, void 0, function () {
        var namespace, workflow, result, loopNodes, items;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    namespace = getTestNamespace();
                    workflow = {
                        apiVersion: "argoproj.io/v1alpha1",
                        kind: "Workflow",
                        metadata: {
                            generateName: "cli-items-strings-direct-",
                            namespace: namespace,
                        },
                        spec: {
                            entrypoint: "main",
                            activeDeadlineSeconds: 30,
                            serviceAccountName: "test-runner",
                            templates: [
                                {
                                    name: "process-item",
                                    inputs: {
                                        parameters: [{ name: "value" }],
                                    },
                                    suspend: {
                                        duration: "0",
                                    },
                                },
                                {
                                    name: "main",
                                    steps: [
                                        [
                                            {
                                                name: "loop-step",
                                                template: "process-item",
                                                arguments: {
                                                    parameters: [
                                                        {
                                                            name: "value",
                                                            value: "{{item}}",
                                                        },
                                                    ],
                                                },
                                                withItems: ["a", "b", "c"],
                                            },
                                        ],
                                    ],
                                },
                            ],
                        },
                    };
                    return [4 /*yield*/, submitAndWait(workflow)];
                case 1:
                    result = _a.sent();
                    expect(result.phase).toBe("Succeeded");
                    loopNodes = Object.values(result.raw.status.nodes).filter(function (n) { return n.displayName && n.displayName.startsWith("loop-step("); });
                    expect(loopNodes.length).toBe(3);
                    items = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "value"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                    expect(items).toEqual(["a", "b", "c"]);
                    return [2 /*return*/];
            }
        });
    }); });
    test("withItems iterates over numbers", function () { return __awaiter(void 0, void 0, void 0, function () {
        var namespace, workflow, result, loopNodes, items;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    namespace = getTestNamespace();
                    workflow = {
                        apiVersion: "argoproj.io/v1alpha1",
                        kind: "Workflow",
                        metadata: {
                            generateName: "cli-items-numbers-direct-",
                            namespace: namespace,
                        },
                        spec: {
                            entrypoint: "main",
                            activeDeadlineSeconds: 30,
                            serviceAccountName: "test-runner",
                            templates: [
                                {
                                    name: "process-item",
                                    inputs: {
                                        parameters: [{ name: "value" }],
                                    },
                                    suspend: {
                                        duration: "0",
                                    },
                                },
                                {
                                    name: "main",
                                    steps: [
                                        [
                                            {
                                                name: "loop-step",
                                                template: "process-item",
                                                arguments: {
                                                    parameters: [
                                                        {
                                                            name: "value",
                                                            value: "{{item}}",
                                                        },
                                                    ],
                                                },
                                                withItems: [1, 2, 3],
                                            },
                                        ],
                                    ],
                                },
                            ],
                        },
                    };
                    return [4 /*yield*/, submitAndWait(workflow)];
                case 1:
                    result = _a.sent();
                    expect(result.phase).toBe("Succeeded");
                    loopNodes = Object.values(result.raw.status.nodes).filter(function (n) { return n.displayName && n.displayName.startsWith("loop-step("); });
                    items = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "value"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                    expect(items).toEqual(["1", "2", "3"]);
                    return [2 /*return*/];
            }
        });
    }); });
    test("withItems with JSON objects - item is serialized", function () { return __awaiter(void 0, void 0, void 0, function () {
        var namespace, workflow, result, loopNodes, items;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    namespace = getTestNamespace();
                    workflow = {
                        apiVersion: "argoproj.io/v1alpha1",
                        kind: "Workflow",
                        metadata: {
                            generateName: "cli-items-objects-direct-",
                            namespace: namespace,
                        },
                        spec: {
                            entrypoint: "main",
                            activeDeadlineSeconds: 30,
                            serviceAccountName: "test-runner",
                            templates: [
                                {
                                    name: "process-item",
                                    inputs: {
                                        parameters: [{ name: "obj" }],
                                    },
                                    suspend: {
                                        duration: "0",
                                    },
                                },
                                {
                                    name: "main",
                                    steps: [
                                        [
                                            {
                                                name: "loop-step",
                                                template: "process-item",
                                                arguments: {
                                                    parameters: [
                                                        {
                                                            name: "obj",
                                                            value: "{{item}}",
                                                        },
                                                    ],
                                                },
                                                withItems: [
                                                    { name: "alice", age: 30 },
                                                    { name: "bob", age: 25 },
                                                ],
                                            },
                                        ],
                                    ],
                                },
                            ],
                        },
                    };
                    return [4 /*yield*/, submitAndWait(workflow)];
                case 1:
                    result = _a.sent();
                    expect(result.phase).toBe("Succeeded");
                    loopNodes = Object.values(result.raw.status.nodes).filter(function (n) { return n.displayName && n.displayName.startsWith("loop-step("); });
                    items = loopNodes.map(function (n) {
                        var _a, _b, _c;
                        var objStr = (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "obj"; })) === null || _c === void 0 ? void 0 : _c.value;
                        return JSON.parse(objStr).name;
                    }).sort();
                    expect(items).toEqual(["alice", "bob"]);
                    return [2 /*return*/];
            }
        });
    }); });
    test("item can be used in expressions directly", function () { return __awaiter(void 0, void 0, void 0, function () {
        var namespace, workflow, result, loopNodes, items;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    namespace = getTestNamespace();
                    workflow = {
                        apiVersion: "argoproj.io/v1alpha1",
                        kind: "Workflow",
                        metadata: {
                            generateName: "cli-items-expr-direct-",
                            namespace: namespace,
                        },
                        spec: {
                            entrypoint: "main",
                            activeDeadlineSeconds: 30,
                            serviceAccountName: "test-runner",
                            templates: [
                                {
                                    name: "process-item",
                                    inputs: {
                                        parameters: [{ name: "computed" }],
                                    },
                                    suspend: {
                                        duration: "0",
                                    },
                                },
                                {
                                    name: "main",
                                    steps: [
                                        [
                                            {
                                                name: "loop-step",
                                                template: "process-item",
                                                arguments: {
                                                    parameters: [
                                                        {
                                                            name: "computed",
                                                            value: "{{=item + '-processed'}}",
                                                        },
                                                    ],
                                                },
                                                withItems: ["x", "y"],
                                            },
                                        ],
                                    ],
                                },
                            ],
                        },
                    };
                    return [4 /*yield*/, submitAndWait(workflow)];
                case 1:
                    result = _a.sent();
                    expect(result.phase).toBe("Succeeded");
                    loopNodes = Object.values(result.raw.status.nodes).filter(function (n) { return n.displayName && n.displayName.startsWith("loop-step("); });
                    items = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "computed"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                    expect(items).toEqual(["x-processed", "y-processed"]);
                    return [2 /*return*/];
            }
        });
    }); });
    test("withParam from JSON array", function () { return __awaiter(void 0, void 0, void 0, function () {
        var namespace, workflow, result, loopNodes, items;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    namespace = getTestNamespace();
                    workflow = {
                        apiVersion: "argoproj.io/v1alpha1",
                        kind: "Workflow",
                        metadata: {
                            generateName: "cli-param-json-array-direct-",
                            namespace: namespace,
                        },
                        spec: {
                            entrypoint: "main",
                            activeDeadlineSeconds: 30,
                            serviceAccountName: "test-runner",
                            arguments: {
                                parameters: [
                                    {
                                        name: "items",
                                        value: '["one","two","three"]',
                                    },
                                ],
                            },
                            templates: [
                                {
                                    name: "process-item",
                                    inputs: {
                                        parameters: [{ name: "value" }],
                                    },
                                    suspend: {
                                        duration: "0",
                                    },
                                },
                                {
                                    name: "main",
                                    steps: [
                                        [
                                            {
                                                name: "loop-step",
                                                template: "process-item",
                                                arguments: {
                                                    parameters: [
                                                        {
                                                            name: "value",
                                                            value: "{{item}}",
                                                        },
                                                    ],
                                                },
                                                withParam: "{{workflow.parameters.items}}",
                                            },
                                        ],
                                    ],
                                },
                            ],
                        },
                    };
                    return [4 /*yield*/, submitAndWait(workflow)];
                case 1:
                    result = _a.sent();
                    expect(result.phase).toBe("Succeeded");
                    loopNodes = Object.values(result.raw.status.nodes).filter(function (n) { return n.displayName && n.displayName.startsWith("loop-step("); });
                    items = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "value"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                    expect(items).toEqual(["one", "three", "two"]);
                    return [2 /*return*/];
            }
        });
    }); });
    test("item type coercion - number to string", function () { return __awaiter(void 0, void 0, void 0, function () {
        var namespace, workflow, result, loopNodes, items;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    namespace = getTestNamespace();
                    workflow = {
                        apiVersion: "argoproj.io/v1alpha1",
                        kind: "Workflow",
                        metadata: {
                            generateName: "cli-items-coerce-direct-",
                            namespace: namespace,
                        },
                        spec: {
                            entrypoint: "main",
                            activeDeadlineSeconds: 30,
                            serviceAccountName: "test-runner",
                            templates: [
                                {
                                    name: "process-item",
                                    inputs: {
                                        parameters: [{ name: "computed" }],
                                    },
                                    suspend: {
                                        duration: "0",
                                    },
                                },
                                {
                                    name: "main",
                                    steps: [
                                        [
                                            {
                                                name: "loop-step",
                                                template: "process-item",
                                                arguments: {
                                                    parameters: [
                                                        {
                                                            name: "computed",
                                                            value: "{{='value-' + string(item)}}",
                                                        },
                                                    ],
                                                },
                                                withItems: [10, 20, 30],
                                            },
                                        ],
                                    ],
                                },
                            ],
                        },
                    };
                    return [4 /*yield*/, submitAndWait(workflow)];
                case 1:
                    result = _a.sent();
                    expect(result.phase).toBe("Succeeded");
                    loopNodes = Object.values(result.raw.status.nodes).filter(function (n) { return n.displayName && n.displayName.startsWith("loop-step("); });
                    items = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "computed"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                    expect(items).toEqual(["value-10", "value-20", "value-30"]);
                    return [2 /*return*/];
            }
        });
    }); });
});
