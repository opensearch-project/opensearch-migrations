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
import { INTERNAL, WorkflowBuilder, expr, makeItemsLoop, makeParameterLoop, renderWorkflowTemplate, typeToken, } from "../../../src/index.js";
import { getTestNamespace, getServiceAccountName } from "../infra/argoCluster.js";
import { submitRenderedWorkflow } from "../infra/probeHelper.js";
import { submitAndWait } from "../infra/workflowRunner.js";
import { reportContractResult, reportParityResult } from "../infra/parityHelper.js";
function countLoopNodes(result, prefix) {
    if (prefix === void 0) { prefix = "loop-step("; }
    return Object.values(result.raw.status.nodes).filter(function (n) { return n.displayName && n.displayName.startsWith(prefix); });
}
describe("Loop Item - withItems iterates over array strings", function () {
    var spec = {
        category: "Loop Item",
        name: "withItems iterates over array strings",
        argoExpression: "withItems: ['a','b','c']",
    };
    describe("ArgoYaml", function () {
        test("raw workflow loops 3 times and passes item", function () { return __awaiter(void 0, void 0, void 0, function () {
            var namespace, workflow, result, loopNodes, items;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        namespace = getTestNamespace();
                        workflow = {
                            apiVersion: "argoproj.io/v1alpha1",
                            kind: "Workflow",
                            metadata: { generateName: "pli-items-strings-direct-", namespace: namespace },
                            spec: {
                                entrypoint: "main",
                                activeDeadlineSeconds: 30,
                                serviceAccountName: "test-runner",
                                templates: [
                                    { name: "process-item", inputs: { parameters: [{ name: "value" }] }, suspend: { duration: "0" } },
                                    {
                                        name: "main",
                                        steps: [[{
                                                    name: "loop-step",
                                                    template: "process-item",
                                                    arguments: { parameters: [{ name: "value", value: "{{item}}" }] },
                                                    withItems: ["a", "b", "c"],
                                                }]],
                                    },
                                ],
                            },
                        };
                        return [4 /*yield*/, submitAndWait(workflow)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        loopNodes = countLoopNodes(result);
                        expect(loopNodes.length).toBe(3);
                        items = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "value"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                        expect(items).toEqual(["a", "b", "c"]);
                        reportContractResult(spec, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describe("Builder - loopWith items", function () {
        var builderVariant = {
            name: "loopWith items",
            code: "addStep(..., c => c.register({ value: expr.asString(c.item) }), { loopWith: makeItemsLoop(['a','b','c']) })",
        };
        test("builder workflow loops 3 times and passes item", function () { return __awaiter(void 0, void 0, void 0, function () {
            var wf, rendered, result, loopNodes, items;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        wf = WorkflowBuilder.create({ k8sResourceName: "pli-items-strings-builder" })
                            .addTemplate("process-item", function (t) { return t
                            .addRequiredInput("value", typeToken())
                            .addSuspend(0); })
                            .addTemplate("main", function (t) { return t
                            .addSteps(function (s) { return s.addStep("loop-step", INTERNAL, "process-item", function (c) { return c.register({ value: expr.asString(c.item) }); }, { loopWith: makeItemsLoop(["a", "b", "c"]) }); }); })
                            .setEntrypoint("main")
                            .getFullScope();
                        rendered = renderWorkflowTemplate(wf);
                        return [4 /*yield*/, submitRenderedWorkflow(rendered)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        loopNodes = countLoopNodes(result);
                        expect(loopNodes.length).toBe(3);
                        items = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "value"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                        expect(items).toEqual(["a", "b", "c"]);
                        reportParityResult(spec, builderVariant, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
});
describe("Loop Item - withItems iterates over numbers", function () {
    var spec = {
        category: "Loop Item",
        name: "withItems iterates over numbers",
        argoExpression: "withItems: [1,2,3]",
    };
    describe("ArgoYaml", function () {
        test("raw workflow loops and coerces numbers to strings", function () { return __awaiter(void 0, void 0, void 0, function () {
            var namespace, workflow, result, loopNodes, items;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        namespace = getTestNamespace();
                        workflow = {
                            apiVersion: "argoproj.io/v1alpha1",
                            kind: "Workflow",
                            metadata: { generateName: "pli-items-numbers-direct-", namespace: namespace },
                            spec: {
                                entrypoint: "main",
                                activeDeadlineSeconds: 30,
                                serviceAccountName: "test-runner",
                                templates: [
                                    { name: "process-item", inputs: { parameters: [{ name: "value" }] }, suspend: { duration: "0" } },
                                    {
                                        name: "main",
                                        steps: [[{
                                                    name: "loop-step",
                                                    template: "process-item",
                                                    arguments: { parameters: [{ name: "value", value: "{{item}}" }] },
                                                    withItems: [1, 2, 3],
                                                }]],
                                    },
                                ],
                            },
                        };
                        return [4 /*yield*/, submitAndWait(workflow)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        loopNodes = countLoopNodes(result);
                        items = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "value"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                        expect(items).toEqual(["1", "2", "3"]);
                        reportContractResult(spec, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describe("Builder - loopWith items", function () {
        var builderVariant = {
            name: "loopWith items",
            code: "addStep(..., c => c.register({ value: expr.asString(c.item) }), { loopWith: makeItemsLoop([1,2,3]) })",
        };
        test("builder workflow loops and coerces numbers to strings", function () { return __awaiter(void 0, void 0, void 0, function () {
            var wf, rendered, result, loopNodes, items;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        wf = WorkflowBuilder.create({ k8sResourceName: "pli-items-numbers-builder" })
                            .addTemplate("process-item", function (t) { return t
                            .addRequiredInput("value", typeToken())
                            .addSuspend(0); })
                            .addTemplate("main", function (t) { return t
                            .addSteps(function (s) { return s.addStep("loop-step", INTERNAL, "process-item", function (c) { return c.register({ value: expr.asString(c.item) }); }, { loopWith: makeItemsLoop([1, 2, 3]) }); }); })
                            .setEntrypoint("main")
                            .getFullScope();
                        rendered = renderWorkflowTemplate(wf);
                        return [4 /*yield*/, submitRenderedWorkflow(rendered)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        loopNodes = countLoopNodes(result);
                        items = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "value"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                        expect(items).toEqual(["1", "2", "3"]);
                        reportParityResult(spec, builderVariant, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
});
describe("Loop Item - withItems JSON objects are serialized", function () {
    var spec = {
        category: "Loop Item",
        name: "withItems JSON objects are serialized",
        argoExpression: "withItems: [{name:'alice'...}, {name:'bob'...}]",
    };
    describe("ArgoYaml", function () {
        test("raw workflow loops objects as serialized values", function () { return __awaiter(void 0, void 0, void 0, function () {
            var namespace, workflow, result, loopNodes, items;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        namespace = getTestNamespace();
                        workflow = {
                            apiVersion: "argoproj.io/v1alpha1",
                            kind: "Workflow",
                            metadata: { generateName: "pli-items-objects-direct-", namespace: namespace },
                            spec: {
                                entrypoint: "main",
                                activeDeadlineSeconds: 30,
                                serviceAccountName: "test-runner",
                                templates: [
                                    { name: "process-item", inputs: { parameters: [{ name: "obj" }] }, suspend: { duration: "0" } },
                                    {
                                        name: "main",
                                        steps: [[{
                                                    name: "loop-step",
                                                    template: "process-item",
                                                    arguments: { parameters: [{ name: "obj", value: "{{item}}" }] },
                                                    withItems: [{ name: "alice", age: 30 }, { name: "bob", age: 25 }],
                                                }]],
                                    },
                                ],
                            },
                        };
                        return [4 /*yield*/, submitAndWait(workflow)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        loopNodes = countLoopNodes(result);
                        items = loopNodes
                            .map(function (n) { var _a, _b, _c; return JSON.parse((_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "obj"; })) === null || _c === void 0 ? void 0 : _c.value).name; })
                            .sort();
                        expect(items).toEqual(["alice", "bob"]);
                        reportContractResult(spec, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describe("Builder - loopWith items objects", function () {
        var builderVariant = {
            name: "loopWith items objects",
            code: "addStep(..., c => c.register({ obj: expr.serialize(c.item) }), { loopWith: makeItemsLoop([{name:'alice',age:30},{name:'bob',age:25}]) })",
        };
        test("builder workflow loops objects as serialized values", function () { return __awaiter(void 0, void 0, void 0, function () {
            var wf, rendered, result, loopNodes, items;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        wf = WorkflowBuilder.create({ k8sResourceName: "pli-items-objects-builder" })
                            .addTemplate("process-item", function (t) { return t
                            .addRequiredInput("obj", typeToken())
                            .addSuspend(0); })
                            .addTemplate("main", function (t) { return t
                            .addSteps(function (s) { return s.addStep("loop-step", INTERNAL, "process-item", function (c) { return c.register({ obj: expr.serialize(c.item) }); }, { loopWith: makeItemsLoop([{ name: "alice", age: 30 }, { name: "bob", age: 25 }]) }); }); })
                            .setEntrypoint("main")
                            .getFullScope();
                        rendered = renderWorkflowTemplate(wf);
                        return [4 /*yield*/, submitRenderedWorkflow(rendered)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        loopNodes = countLoopNodes(result);
                        items = loopNodes
                            .map(function (n) { var _a, _b, _c; return JSON.parse((_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "obj"; })) === null || _c === void 0 ? void 0 : _c.value).name; })
                            .sort();
                        expect(items).toEqual(["alice", "bob"]);
                        reportParityResult(spec, builderVariant, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
});
describe("Loop Item - item used in expression directly", function () {
    var spec = {
        category: "Loop Item",
        name: "item used in expression directly",
        argoExpression: "{{=item + '-processed'}}",
    };
    describe("ArgoYaml", function () {
        test("raw workflow computes per-item expression values", function () { return __awaiter(void 0, void 0, void 0, function () {
            var namespace, workflow, result, loopNodes, items;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        namespace = getTestNamespace();
                        workflow = {
                            apiVersion: "argoproj.io/v1alpha1",
                            kind: "Workflow",
                            metadata: { generateName: "pli-items-expr-direct-", namespace: namespace },
                            spec: {
                                entrypoint: "main",
                                activeDeadlineSeconds: 30,
                                serviceAccountName: "test-runner",
                                templates: [
                                    { name: "process-item", inputs: { parameters: [{ name: "computed" }] }, suspend: { duration: "0" } },
                                    {
                                        name: "main",
                                        steps: [[{
                                                    name: "loop-step",
                                                    template: "process-item",
                                                    arguments: { parameters: [{ name: "computed", value: "{{=item + '-processed'}}" }] },
                                                    withItems: ["x", "y"],
                                                }]],
                                    },
                                ],
                            },
                        };
                        return [4 /*yield*/, submitAndWait(workflow)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        loopNodes = countLoopNodes(result);
                        items = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "computed"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                        expect(items).toEqual(["x-processed", "y-processed"]);
                        reportContractResult(spec, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describe("Builder - loop item expression", function () {
        var builderVariant = {
            name: "item expression",
            code: "addStep(..., c => c.register({ computed: expr.concat(expr.asString(c.item), expr.literal('-processed')) }), { loopWith: makeItemsLoop(['x','y']) })",
        };
        test("builder workflow computes per-item expression values", function () { return __awaiter(void 0, void 0, void 0, function () {
            var wf, rendered, result, loopNodes, items;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        wf = WorkflowBuilder.create({ k8sResourceName: "pli-items-expr-builder" })
                            .addTemplate("process-item", function (t) { return t
                            .addRequiredInput("computed", typeToken())
                            .addSuspend(0); })
                            .addTemplate("main", function (t) { return t
                            .addSteps(function (s) { return s.addStep("loop-step", INTERNAL, "process-item", function (c) { return c.register({
                            computed: expr.concat(expr.asString(c.item), expr.literal("-processed")),
                        }); }, { loopWith: makeItemsLoop(["x", "y"]) }); }); })
                            .setEntrypoint("main")
                            .getFullScope();
                        rendered = renderWorkflowTemplate(wf);
                        return [4 /*yield*/, submitRenderedWorkflow(rendered)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        loopNodes = countLoopNodes(result);
                        items = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "computed"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                        expect(items).toEqual(["x-processed", "y-processed"]);
                        reportParityResult(spec, builderVariant, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
});
describe("Loop Item - withParam from JSON array", function () {
    var spec = {
        category: "Loop Item",
        name: "withParam from JSON array",
        argoExpression: "withParam: {{workflow.parameters.items}}",
        inputs: { items: '["one","two","three"]' },
    };
    describe("ArgoYaml", function () {
        test("raw workflow loops over parameter array", function () { return __awaiter(void 0, void 0, void 0, function () {
            var namespace, workflow, result, loopNodes, items;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        namespace = getTestNamespace();
                        workflow = {
                            apiVersion: "argoproj.io/v1alpha1",
                            kind: "Workflow",
                            metadata: { generateName: "pli-param-json-array-direct-", namespace: namespace },
                            spec: {
                                entrypoint: "main",
                                activeDeadlineSeconds: 30,
                                serviceAccountName: "test-runner",
                                arguments: { parameters: [{ name: "items", value: '["one","two","three"]' }] },
                                templates: [
                                    { name: "process-item", inputs: { parameters: [{ name: "value" }] }, suspend: { duration: "0" } },
                                    {
                                        name: "main",
                                        steps: [[{
                                                    name: "loop-step",
                                                    template: "process-item",
                                                    arguments: { parameters: [{ name: "value", value: "{{item}}" }] },
                                                    withParam: "{{workflow.parameters.items}}",
                                                }]],
                                    },
                                ],
                            },
                        };
                        return [4 /*yield*/, submitAndWait(workflow)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        loopNodes = countLoopNodes(result);
                        items = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "value"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                        expect(items).toEqual(["one", "three", "two"]);
                        reportContractResult(spec, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describe("Builder - loopWith param", function () {
        var builderVariant = {
            name: "loopWith param",
            code: "addStep(..., c => c.register({ value: expr.asString(c.item) }), { loopWith: makeParameterLoop(ctx.inputs.items) })",
        };
        test("builder workflow loops over parameter array", function () { return __awaiter(void 0, void 0, void 0, function () {
            var wf, rendered, result, loopNodes, items;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        wf = WorkflowBuilder.create({ k8sResourceName: "pli-param-json-array-builder" })
                            .addTemplate("process-item", function (t) { return t
                            .addRequiredInput("value", typeToken())
                            .addSuspend(0); })
                            .addTemplate("main", function (t) { return t
                            .addRequiredInput("items", typeToken())
                            .addSteps(function (s) { return s.addStep("loop-step", INTERNAL, "process-item", function (c) { return c.register({ value: expr.asString(c.item) }); }, { loopWith: makeParameterLoop(expr.deserializeRecord(s.inputs.items)) }); }); })
                            .setEntrypoint("main")
                            .getFullScope();
                        rendered = renderWorkflowTemplate(wf);
                        return [4 /*yield*/, submitRenderedWorkflow(rendered, { items: spec.inputs.items })];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        loopNodes = countLoopNodes(result);
                        items = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "value"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                        expect(items).toEqual(["one", "three", "two"]);
                        reportParityResult(spec, builderVariant, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
});
describe("Loop Item - item number coerced with string()", function () {
    var spec = {
        category: "Loop Item",
        name: "item number coerced with string()",
        argoExpression: "{{='value-' + string(item)}}",
    };
    describe("ArgoYaml", function () {
        test("raw workflow computes value-prefixed numeric items", function () { return __awaiter(void 0, void 0, void 0, function () {
            var namespace, workflow, result, loopNodes, items;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        namespace = getTestNamespace();
                        workflow = {
                            apiVersion: "argoproj.io/v1alpha1",
                            kind: "Workflow",
                            metadata: { generateName: "pli-items-coerce-direct-", namespace: namespace },
                            spec: {
                                entrypoint: "main",
                                activeDeadlineSeconds: 30,
                                serviceAccountName: "test-runner",
                                templates: [
                                    { name: "process-item", inputs: { parameters: [{ name: "computed" }] }, suspend: { duration: "0" } },
                                    {
                                        name: "main",
                                        steps: [[{
                                                    name: "loop-step",
                                                    template: "process-item",
                                                    arguments: { parameters: [{ name: "computed", value: "{{='value-' + string(item)}}" }] },
                                                    withItems: [10, 20, 30],
                                                }]],
                                    },
                                ],
                            },
                        };
                        return [4 /*yield*/, submitAndWait(workflow)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        loopNodes = countLoopNodes(result);
                        items = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "computed"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                        expect(items).toEqual(["value-10", "value-20", "value-30"]);
                        reportContractResult(spec, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describe("Builder - item number coercion", function () {
        var builderVariant = {
            name: "item number coercion",
            code: "addStep(..., c => c.register({ computed: expr.concat(expr.literal('value-'), expr.asString(c.item)) }), { loopWith: makeItemsLoop([10,20,30]) })",
        };
        test("builder workflow computes value-prefixed numeric items", function () { return __awaiter(void 0, void 0, void 0, function () {
            var wf, rendered, result, loopNodes, items;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        wf = WorkflowBuilder.create({ k8sResourceName: "pli-items-coerce-builder" })
                            .addTemplate("process-item", function (t) { return t
                            .addRequiredInput("computed", typeToken())
                            .addSuspend(0); })
                            .addTemplate("main", function (t) { return t
                            .addSteps(function (s) { return s.addStep("loop-step", INTERNAL, "process-item", function (c) { return c.register({
                            computed: expr.concat(expr.literal("value-"), expr.asString(c.item)),
                        }); }, { loopWith: makeItemsLoop([10, 20, 30]) }); }); })
                            .setEntrypoint("main")
                            .getFullScope();
                        rendered = renderWorkflowTemplate(wf);
                        return [4 /*yield*/, submitRenderedWorkflow(rendered)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        loopNodes = countLoopNodes(result);
                        items = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "computed"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                        expect(items).toEqual(["value-10", "value-20", "value-30"]);
                        reportParityResult(spec, builderVariant, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
});
describe("Loop Item - withParam over objects delivers parsed objects", function () {
    var spec = {
        category: "Loop Item",
        name: "withParam over objects delivers parsed objects",
        argoExpression: "withParam: {{=toJSON(workflow.parameters.items)}}",
        inputs: { items: JSON.stringify([{ name: "alice", age: 30 }, { name: "bob", age: 25 }]) },
    };
    describe("ArgoYaml", function () {
        test("raw workflow: each item is a parsed object, nested fields accessible via item['field']", function () { return __awaiter(void 0, void 0, void 0, function () {
            var namespace, workflow, result, loopNodes, names;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        namespace = getTestNamespace();
                        workflow = {
                            apiVersion: "argoproj.io/v1alpha1",
                            kind: "Workflow",
                            metadata: { generateName: "pli-param-objects-direct-", namespace: namespace },
                            spec: {
                                entrypoint: "main",
                                activeDeadlineSeconds: 30,
                                serviceAccountName: "test-runner",
                                arguments: { parameters: [{ name: "items", value: spec.inputs.items }] },
                                templates: [
                                    { name: "process-item", inputs: { parameters: [{ name: "name" }] }, suspend: { duration: "0" } },
                                    {
                                        name: "main",
                                        steps: [[{
                                                    name: "loop-step",
                                                    template: "process-item",
                                                    arguments: { parameters: [
                                                            // item is a parsed object in withParam — access fields directly
                                                            { name: "name", value: "{{=item['name']}}" },
                                                        ] },
                                                    withParam: "{{workflow.parameters.items}}",
                                                }]],
                                    },
                                ],
                            },
                        };
                        return [4 /*yield*/, submitAndWait(workflow)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        loopNodes = countLoopNodes(result);
                        names = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "name"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                        expect(names).toEqual(["alice", "bob"]);
                        reportContractResult(spec, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describe("Builder - withParam over objects: c.item is a parsed object at runtime", function () {
        var builderVariant = {
            name: "withParam over objects",
            code: "c.item is T at runtime in withParam; use expr.get(c.item, 'field') for field access, expr.serialize(c.item) to pass as Serialized<T>",
        };
        test("builder: c.item is a parsed object; expr.get accesses fields; expr.serialize passes to typed inputs", function () { return __awaiter(void 0, void 0, void 0, function () {
            var items, wf, rendered, result, loopNodes, names;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        items = [{ name: "alice", age: 30 }, { name: "bob", age: 25 }];
                        wf = WorkflowBuilder.create({ k8sResourceName: "pli-param-objects-builder" })
                            .addTemplate("process-item", function (t) { return t
                            .addRequiredInput("name", typeToken())
                            .addSuspend(0); })
                            .addTemplate("main", function (t) { return t
                            .addRequiredInput("items", typeToken())
                            .addSteps(function (s) { return s.addStep("loop-step", INTERNAL, "process-item", function (c) { return c.register({
                            // c.item is a parsed object at runtime — expr.get works directly
                            name: expr.asString(expr.get(c.item, "name")),
                        }); }, { loopWith: makeParameterLoop(expr.deserializeRecord(s.inputs.items)) }); }); })
                            .setEntrypoint("main")
                            .getFullScope();
                        rendered = renderWorkflowTemplate(wf);
                        return [4 /*yield*/, submitRenderedWorkflow(rendered, { items: JSON.stringify(items) })];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        loopNodes = countLoopNodes(result);
                        names = loopNodes.map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "name"; })) === null || _c === void 0 ? void 0 : _c.value; }).sort();
                        expect(names).toEqual(["alice", "bob"]);
                        reportParityResult(spec, builderVariant, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
});
describe("Loop Item - withParam over objects with nested fields (realistic fullMigration pattern)", function () {
    var proxies = [
        { name: "proxy-a", kafkaConfig: { connection: "kafka-a:9092", topic: "topic-a" }, listenPort: 9200 },
        { name: "proxy-b", kafkaConfig: { connection: "kafka-b:9092", topic: "topic-b" }, listenPort: 9201 },
    ];
    var spec = {
        category: "Loop Item",
        name: "withParam over nested objects (fullMigration pattern)",
        argoExpression: "withParam over complex objects, accessing nested fields",
        inputs: { proxies: JSON.stringify(proxies) },
    };
    describe("ArgoYaml", function () {
        test("raw: nested fields accessible via item['kafkaConfig']['connection']", function () { return __awaiter(void 0, void 0, void 0, function () {
            var namespace, workflow, result, loopNodes, connections;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        namespace = getTestNamespace();
                        workflow = {
                            apiVersion: "argoproj.io/v1alpha1",
                            kind: "Workflow",
                            metadata: { generateName: "pli-param-nested-direct-", namespace: namespace },
                            spec: {
                                entrypoint: "main",
                                activeDeadlineSeconds: 30,
                                serviceAccountName: getServiceAccountName(),
                                arguments: { parameters: [{ name: "proxies", value: spec.inputs.proxies }] },
                                templates: [
                                    {
                                        name: "process-proxy",
                                        inputs: { parameters: [{ name: "name" }, { name: "connection" }, { name: "topic" }] },
                                        suspend: { duration: "0" }
                                    },
                                    {
                                        name: "main",
                                        steps: [[{
                                                    name: "loop-step",
                                                    template: "process-proxy",
                                                    arguments: { parameters: [
                                                            { name: "name", value: "{{=item['name']}}" },
                                                            // nested object: item['kafkaConfig'] is a JSON string, need fromJSON to parse it
                                                            { name: "connection", value: "{{=fromJSON(item['kafkaConfig'])['connection']}}" },
                                                            { name: "topic", value: "{{=fromJSON(item['kafkaConfig'])['topic']}}" },
                                                        ] },
                                                    withParam: "{{workflow.parameters.proxies}}",
                                                }]],
                                    },
                                ],
                            },
                        };
                        return [4 /*yield*/, submitAndWait(workflow)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        loopNodes = countLoopNodes(result);
                        connections = loopNodes
                            .map(function (n) { var _a, _b, _c; return (_c = (_b = (_a = n.inputs) === null || _a === void 0 ? void 0 : _a.parameters) === null || _b === void 0 ? void 0 : _b.find(function (p) { return p.name === "connection"; })) === null || _c === void 0 ? void 0 : _c.value; })
                            .sort();
                        expect(connections).toEqual(["kafka-a:9092", "kafka-b:9092"]);
                        reportContractResult(spec, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describe("Builder - withParam over nested objects without casts", function () {
        var builderVariant = {
            name: "withParam nested objects no-cast",
            code: "TODO: find the cast-free pattern for passing c.item to typed inputs and accessing nested fields",
        };
        test.skip("builder: pass c.item to typed input and access nested fields without cast", function () { return __awaiter(void 0, void 0, void 0, function () {
            return __generator(this, function (_a) {
                // This test documents the GOAL: no expr.cast() needed.
                // Currently fullMigration uses expr.cast(c.item).to<Serialized<T>>() as a workaround.
                // The ideal would be:
                //   proxyConfig: c.item  (directly, type-safe)
                //   kafkaConnection: expr.get(expr.get(c.item, "kafkaConfig"), "connection")
                //
                // Blocked by: c.item is typed as T but withParam delivers it as a parsed object,
                // and the register() call expects Serialized<T> for complex object inputs.
                // See: taskBuilder.ts ParamProviderCallbackObject item type.
                reportParityResult(spec, builderVariant, { phase: "Skipped" });
                return [2 /*return*/];
            });
        }); });
    });
});
