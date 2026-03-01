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
import { submitRenderedWorkflow } from "../infra/probeHelper.js";
import { submitAndWait } from "../infra/workflowRunner.js";
import { reportContractResult, reportParityResult } from "../infra/parityHelper.js";
function submitConditionalProbe(input, whenCondition) {
    return __awaiter(this, void 0, void 0, function () {
        var namespace, workflow;
        return __generator(this, function (_a) {
            namespace = getTestNamespace();
            workflow = {
                apiVersion: "argoproj.io/v1alpha1",
                kind: "Workflow",
                metadata: { generateName: "pwcn-when-direct-", namespace: namespace },
                spec: {
                    entrypoint: "main",
                    activeDeadlineSeconds: 30,
                    serviceAccountName: "test-runner",
                    arguments: { parameters: [{ name: "input", value: input }] },
                    templates: [
                        { name: "conditional", inputs: { parameters: [{ name: "val" }] }, steps: [[]] },
                        {
                            name: "main",
                            steps: [[{
                                        name: "conditional-step",
                                        template: "conditional",
                                        when: whenCondition,
                                        arguments: { parameters: [{ name: "val", value: "{{workflow.parameters.input}}" }] },
                                    }]],
                        },
                    ],
                },
            };
            return [2 /*return*/, submitAndWait(workflow)];
        });
    });
}
function spec(name, input, whenCondition) {
    return {
        category: "When Conditions",
        name: name,
        inputs: { input: input },
        argoExpression: whenCondition,
    };
}
describe("When Conditions Negative - false condition not succeeded", function () {
    var s = spec("false condition not succeeded", "no", "{{workflow.parameters.input}} == yes");
    describe("ArgoYaml", function () {
        test("raw conditional workflow does not execute step", function () { return __awaiter(void 0, void 0, void 0, function () {
            var r;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0: return [4 /*yield*/, submitConditionalProbe("no", s.argoExpression)];
                    case 1:
                        r = _a.sent();
                        expect(r.phase).toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).toBe("Skipped");
                        reportContractResult(s, r);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describe("Builder - when equals", function () {
        var builderVariant = {
            name: "when equals",
            code: "addStep(..., { when: { templateExp: expr.equals(expr.length(ctx.inputs.input), expr.literal(3)) } })",
        };
        test("builder conditional workflow does not execute step", function () { return __awaiter(void 0, void 0, void 0, function () {
            var wf, rendered, r;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        wf = WorkflowBuilder.create({ k8sResourceName: "pwcn-when-neg-literal-false-builder" })
                            .addTemplate("conditional", function (t) { return t
                            .addSteps(function (s) { return s.addStepGroup(function (c) { return c; }); }); })
                            .addTemplate("main", function (t) { return t
                            .addRequiredInput("input", typeToken())
                            .addSteps(function (s) { return s.addStep("conditional-step", INTERNAL, "conditional", {
                            when: {
                                templateExp: expr.equals(expr.length(s.inputs.input), expr.literal(3)),
                            },
                        }); }); })
                            .setEntrypoint("main")
                            .getFullScope();
                        rendered = renderWorkflowTemplate(wf);
                        return [4 /*yield*/, submitRenderedWorkflow(rendered, { input: s.inputs.input })];
                    case 1:
                        r = _a.sent();
                        expect(r.phase).toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).toBe("Skipped");
                        reportParityResult(s, builderVariant, r);
                        return [2 /*return*/];
                }
            });
        }); });
    });
});
describe("When Conditions Negative - true condition not skipped", function () {
    var s = spec("true condition not skipped", "yes", "{{workflow.parameters.input}} == yes");
    describe("ArgoYaml", function () {
        test("raw conditional workflow executes step", function () { return __awaiter(void 0, void 0, void 0, function () {
            var r;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0: return [4 /*yield*/, submitConditionalProbe("yes", s.argoExpression)];
                    case 1:
                        r = _a.sent();
                        expect(r.phase).toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Skipped");
                        expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Omitted");
                        expect(r.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
                        reportContractResult(s, r);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describe("Builder - when equals", function () {
        var builderVariant = {
            name: "when equals",
            code: "addStep(..., { when: { templateExp: expr.equals(expr.length(ctx.inputs.input), expr.literal(3)) } })",
        };
        test("builder conditional workflow executes step", function () { return __awaiter(void 0, void 0, void 0, function () {
            var wf, rendered, r;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        wf = WorkflowBuilder.create({ k8sResourceName: "pwcn-when-neg-literal-true-builder" })
                            .addTemplate("conditional", function (t) { return t
                            .addSteps(function (s) { return s.addStepGroup(function (c) { return c; }); }); })
                            .addTemplate("main", function (t) { return t
                            .addRequiredInput("input", typeToken())
                            .addSteps(function (s) { return s.addStep("conditional-step", INTERNAL, "conditional", {
                            when: {
                                templateExp: expr.equals(expr.length(s.inputs.input), expr.literal(3)),
                            },
                        }); }); })
                            .setEntrypoint("main")
                            .getFullScope();
                        rendered = renderWorkflowTemplate(wf);
                        return [4 /*yield*/, submitRenderedWorkflow(rendered, { input: s.inputs.input })];
                    case 1:
                        r = _a.sent();
                        expect(r.phase).toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Skipped");
                        expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Omitted");
                        expect(r.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
                        reportParityResult(s, builderVariant, r);
                        return [2 /*return*/];
                }
            });
        }); });
    });
});
describe("When Conditions Negative - expression false not succeeded", function () {
    var s = spec("expression false not succeeded", "1", "{{= asInt(workflow.parameters.input) > 3 }}");
    describe("ArgoYaml", function () {
        test("raw conditional workflow does not execute step", function () { return __awaiter(void 0, void 0, void 0, function () {
            var r;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0: return [4 /*yield*/, submitConditionalProbe("1", s.argoExpression)];
                    case 1:
                        r = _a.sent();
                        expect(r.phase).toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).toBe("Skipped");
                        reportContractResult(s, r);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describe("Builder - when numeric comparison", function () {
        var builderVariant = {
            name: "when numeric comparison",
            code: "addStep(..., { when: { templateExp: expr.greaterThan(expr.deserializeRecord(ctx.inputs.input), expr.literal(3)) } })",
        };
        test("builder conditional workflow does not execute step", function () { return __awaiter(void 0, void 0, void 0, function () {
            var wf, rendered, r;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        wf = WorkflowBuilder.create({ k8sResourceName: "pwcn-when-neg-expr-false-builder" })
                            .addTemplate("conditional", function (t) { return t
                            .addSteps(function (s) { return s.addStepGroup(function (c) { return c; }); }); })
                            .addTemplate("main", function (t) { return t
                            .addRequiredInput("input", typeToken())
                            .addSteps(function (s) { return s.addStep("conditional-step", INTERNAL, "conditional", {
                            when: {
                                templateExp: expr.greaterThan(expr.deserializeRecord(s.inputs.input), expr.literal(3)),
                            },
                        }); }); })
                            .setEntrypoint("main")
                            .getFullScope();
                        rendered = renderWorkflowTemplate(wf);
                        return [4 /*yield*/, submitRenderedWorkflow(rendered, { input: s.inputs.input })];
                    case 1:
                        r = _a.sent();
                        expect(r.phase).toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).toBe("Skipped");
                        reportParityResult(s, builderVariant, r);
                        return [2 /*return*/];
                }
            });
        }); });
    });
});
describe("When Conditions Negative - expression true not skipped", function () {
    var s = spec("expression true not skipped", "5", "{{= asInt(workflow.parameters.input) > 3 }}");
    describe("ArgoYaml", function () {
        test("raw conditional workflow executes step", function () { return __awaiter(void 0, void 0, void 0, function () {
            var r;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0: return [4 /*yield*/, submitConditionalProbe("5", s.argoExpression)];
                    case 1:
                        r = _a.sent();
                        expect(r.phase).toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Skipped");
                        expect(r.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
                        reportContractResult(s, r);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describe("Builder - when numeric comparison", function () {
        var builderVariant = {
            name: "when numeric comparison",
            code: "addStep(..., { when: { templateExp: expr.greaterThan(expr.deserializeRecord(ctx.inputs.input), expr.literal(3)) } })",
        };
        test("builder conditional workflow executes step", function () { return __awaiter(void 0, void 0, void 0, function () {
            var wf, rendered, r;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        wf = WorkflowBuilder.create({ k8sResourceName: "pwcn-when-neg-expr-true-builder" })
                            .addTemplate("conditional", function (t) { return t
                            .addSteps(function (s) { return s.addStepGroup(function (c) { return c; }); }); })
                            .addTemplate("main", function (t) { return t
                            .addRequiredInput("input", typeToken())
                            .addSteps(function (s) { return s.addStep("conditional-step", INTERNAL, "conditional", {
                            when: {
                                templateExp: expr.greaterThan(expr.deserializeRecord(s.inputs.input), expr.literal(3)),
                            },
                        }); }); })
                            .setEntrypoint("main")
                            .getFullScope();
                        rendered = renderWorkflowTemplate(wf);
                        return [4 /*yield*/, submitRenderedWorkflow(rendered, { input: s.inputs.input })];
                    case 1:
                        r = _a.sent();
                        expect(r.phase).toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Skipped");
                        expect(r.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
                        reportParityResult(s, builderVariant, r);
                        return [2 /*return*/];
                }
            });
        }); });
    });
});
describe("When Conditions Negative - wrong comparison value skips", function () {
    var s = spec("wrong comparison value skips", "maybe", "{{workflow.parameters.input}} == yes");
    describe("ArgoYaml", function () {
        test("raw conditional workflow skips step", function () { return __awaiter(void 0, void 0, void 0, function () {
            var r;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0: return [4 /*yield*/, submitConditionalProbe("maybe", s.argoExpression)];
                    case 1:
                        r = _a.sent();
                        expect(r.phase).toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).toBe("Skipped");
                        reportContractResult(s, r);
                        return [2 /*return*/];
                }
            });
        }); });
    });
    describe("Builder - when equals", function () {
        var builderVariant = {
            name: "when equals",
            code: "addStep(..., { when: { templateExp: expr.equals(expr.length(ctx.inputs.input), expr.literal(3)) } })",
        };
        test("builder conditional workflow skips step", function () { return __awaiter(void 0, void 0, void 0, function () {
            var wf, rendered, r;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        wf = WorkflowBuilder.create({ k8sResourceName: "pwcn-when-neg-wrong-val-builder" })
                            .addTemplate("conditional", function (t) { return t
                            .addSteps(function (s) { return s.addStepGroup(function (c) { return c; }); }); })
                            .addTemplate("main", function (t) { return t
                            .addRequiredInput("input", typeToken())
                            .addSteps(function (s) { return s.addStep("conditional-step", INTERNAL, "conditional", {
                            when: {
                                templateExp: expr.equals(expr.length(s.inputs.input), expr.literal(3)),
                            },
                        }); }); })
                            .setEntrypoint("main")
                            .getFullScope();
                        rendered = renderWorkflowTemplate(wf);
                        return [4 /*yield*/, submitRenderedWorkflow(rendered, { input: s.inputs.input })];
                    case 1:
                        r = _a.sent();
                        expect(r.phase).toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Succeeded");
                        expect(r.nodeOutputs["conditional-step"].phase).toBe("Skipped");
                        reportParityResult(s, builderVariant, r);
                        return [2 /*return*/];
                }
            });
        }); });
    });
});
