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
import { INTERNAL, WorkflowBuilder, expr, renderWorkflowTemplate, typeToken } from "../../../src/index.js";
import { getTestNamespace } from "../infra/argoCluster.js";
import { submitProbe, submitRenderedWorkflow } from "../infra/probeHelper.js";
import { submitAndWait } from "../infra/workflowRunner.js";
import { reportContractResult, reportKnownBroken, reportParityResult } from "../infra/parityHelper.js";
import { makeTestWorkflow } from "../infra/testWorkflowHelper.js";
import { describeBroken } from "../infra/brokenTestControl.js";
describe("Expression Utilities - fillTemplate", function () {
    var spec = {
        category: "Expression Utilities",
        name: "fillTemplate",
        inputs: { first: "alpha", second: "beta" },
        argoExpression: "inputs.parameters.first + '-' + inputs.parameters.second",
        expectedResult: "alpha-beta",
    };
    describe("ArgoYaml", function () {
        test("raw expression produces expected result", function () { return __awaiter(void 0, void 0, void 0, function () {
            var result;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0: return [4 /*yield*/, submitProbe({
                            inputs: spec.inputs,
                            expression: spec.argoExpression,
                        })];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        expect(result.globalOutputs.result).toBe(spec.expectedResult);
                        reportContractResult(spec, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describeBroken("Builder - fillTemplate", function () {
        var builderVariant = {
            name: "fillTemplate",
            code: 'expr.fillTemplate("{{first}}-{{second}}", { first: ctx.inputs.first, second: ctx.inputs.second })',
        };
        reportKnownBroken(spec, builderVariant, "Runtime phase Error: fillTemplate builder output does not execute successfully in Argo.");
        test("builder API produces same result", function () { return __awaiter(void 0, void 0, void 0, function () {
            var wf, rendered, result;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        wf = makeTestWorkflow(function (t) { return t
                            .addRequiredInput("first", typeToken())
                            .addRequiredInput("second", typeToken())
                            .addSteps(function (s) { return s.addStepGroup(function (c) { return c; }); })
                            .addExpressionOutput("result", function (ctx) {
                            return expr.fillTemplate("{{first}}-{{second}}", {
                                first: ctx.inputs.first,
                                second: ctx.inputs.second,
                            });
                        }); });
                        rendered = renderWorkflowTemplate(wf);
                        return [4 /*yield*/, submitRenderedWorkflow(rendered, spec.inputs)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        expect(result.globalOutputs.result).toBe(spec.expectedResult);
                        reportParityResult(spec, builderVariant, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
});
describeBroken("Expression Utilities - workflow serviceAccountName value", function () {
    var spec = {
        category: "Expression Utilities",
        name: "workflow serviceAccountName value",
        argoExpression: "workflow.serviceAccountName",
        expectedResult: "test-runner",
    };
    describe("ArgoYaml", function () {
        test("raw expression produces expected result", function () { return __awaiter(void 0, void 0, void 0, function () {
            var result;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0: return [4 /*yield*/, submitProbe({
                            expression: spec.argoExpression,
                        })];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        expect(result.globalOutputs.result).toBe(spec.expectedResult);
                        reportContractResult(spec, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describe("Builder - getWorkflowValue", function () {
        var builderVariant = {
            name: "getWorkflowValue",
            code: 'expr.getWorkflowValue("serviceAccountName")',
        };
        test("builder API produces same result", function () { return __awaiter(void 0, void 0, void 0, function () {
            var wf, rendered, result;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        wf = makeTestWorkflow(function (t) { return t
                            .addSteps(function (s) { return s.addStepGroup(function (c) { return c; }); })
                            .addExpressionOutput("result", function () {
                            return expr.getWorkflowValue("serviceAccountName");
                        }); });
                        rendered = renderWorkflowTemplate(wf);
                        return [4 /*yield*/, submitRenderedWorkflow(rendered)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        expect(result.globalOutputs.result).toBe(spec.expectedResult);
                        reportParityResult(spec, builderVariant, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
});
describe("Expression Utilities - taskData steps output reference", function () {
    var spec = {
        category: "Expression Utilities",
        name: "taskData steps output reference",
        argoExpression: "steps.produce.outputs.parameters.value",
        expectedResult: "hello",
    };
    describe("ArgoYaml", function () {
        test("raw workflow expression can read prior step output", function () { return __awaiter(void 0, void 0, void 0, function () {
            var namespace, workflow, result;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        namespace = getTestNamespace();
                        workflow = {
                            apiVersion: "argoproj.io/v1alpha1",
                            kind: "Workflow",
                            metadata: { generateName: "peu-taskdata-raw-", namespace: namespace },
                            spec: {
                                entrypoint: "main",
                                activeDeadlineSeconds: 30,
                                serviceAccountName: "test-runner",
                                templates: [
                                    {
                                        name: "producer",
                                        steps: [[]],
                                        outputs: {
                                            parameters: [
                                                {
                                                    name: "value",
                                                    valueFrom: {
                                                        expression: "'hello'",
                                                    },
                                                },
                                            ],
                                        },
                                    },
                                    {
                                        name: "main",
                                        steps: [[{ name: "produce", template: "producer" }]],
                                        outputs: {
                                            parameters: [
                                                {
                                                    name: "result",
                                                    valueFrom: {
                                                        expression: spec.argoExpression,
                                                    },
                                                },
                                            ],
                                        },
                                    },
                                ],
                            },
                        };
                        return [4 /*yield*/, submitAndWait(workflow)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        expect(result.globalOutputs.result).toBe(spec.expectedResult);
                        reportContractResult(spec, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describeBroken("Builder - taskData", function () {
        var builderVariant = {
            name: "taskData",
            code: 'expr.taskData("steps", "produce", "outputs.parameters.value")',
        };
        reportKnownBroken(spec, builderVariant, "Runtime Failed: taskData reference rendering is not yet compatible for this steps output access.");
        test("builder API produces same result", function () { return __awaiter(void 0, void 0, void 0, function () {
            var wf, rendered, result;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        wf = WorkflowBuilder.create({ k8sResourceName: "peu-taskdata-builder" })
                            .addTemplate("producer", function (t) { return t
                            .addSteps(function (s) { return s.addStepGroup(function (c) { return c; }); })
                            .addExpressionOutput("value", function () { return expr.literal("hello"); }); })
                            .addTemplate("main", function (t) { return t
                            .addSteps(function (s) { return s.addStep("produce", INTERNAL, "producer"); })
                            .addExpressionOutput("result", function () {
                            return expr.taskData("steps", "produce", "outputs.parameters.value");
                        }); })
                            .setEntrypoint("main")
                            .getFullScope();
                        rendered = renderWorkflowTemplate(wf);
                        return [4 /*yield*/, submitRenderedWorkflow(rendered)];
                    case 1:
                        result = _a.sent();
                        expect(result.phase).toBe("Succeeded");
                        expect(result.globalOutputs.result).toBe(spec.expectedResult);
                        reportParityResult(spec, builderVariant, result);
                        return [2 /*return*/];
                }
            });
        }); });
    });
});
