var __assign = (this && this.__assign) || function () {
    __assign = Object.assign || function(t) {
        for (var s, i = 1, n = arguments.length; i < n; i++) {
            s = arguments[i];
            for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p))
                t[p] = s[p];
        }
        return t;
    };
    return __assign.apply(this, arguments);
};
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
import { submitAndWait } from "./workflowRunner";
import { getTestNamespace, getServiceAccountName } from "./argoCluster";
export function submitProbe(config) {
    return __awaiter(this, void 0, void 0, function () {
        var namespace, inputParams, inputParamDefs, inputParamArgs, workflow;
        return __generator(this, function (_a) {
            namespace = getTestNamespace();
            inputParams = Object.entries(config.inputs || {}).map(function (_a) {
                var name = _a[0], value = _a[1];
                return ({
                    name: name,
                    value: value,
                });
            });
            inputParamDefs = Object.keys(config.inputs || {}).map(function (name) { return ({ name: name }); });
            inputParamArgs = Object.keys(config.inputs || {}).map(function (name) { return ({
                name: name,
                value: "{{workflow.parameters.".concat(name, "}}"),
            }); });
            workflow = {
                apiVersion: "argoproj.io/v1alpha1",
                kind: "Workflow",
                metadata: {
                    generateName: "probe-",
                    namespace: namespace,
                },
                spec: {
                    entrypoint: "main",
                    activeDeadlineSeconds: 30,
                    serviceAccountName: getServiceAccountName(),
                    arguments: {
                        parameters: inputParams,
                    },
                    templates: [
                        {
                            name: "main",
                            inputs: {
                                parameters: inputParamDefs,
                            },
                            steps: [[]], // Empty steps - no containers, but provides execution context
                            outputs: {
                                parameters: [
                                    {
                                        name: "result",
                                        valueFrom: {
                                            expression: config.expression,
                                        },
                                    },
                                ],
                            },
                        },
                    ],
                },
            };
            return [2 /*return*/, submitAndWait(workflow)];
        });
    });
}
export function submitChainProbe(config) {
    return __awaiter(this, void 0, void 0, function () {
        var namespace, templates, stepGroups, workflow;
        return __generator(this, function (_a) {
            namespace = getTestNamespace();
            templates = [];
            stepGroups = [];
            // Create a template for each step
            config.steps.forEach(function (step, idx) {
                var templateName = "step".concat(idx);
                templates.push({
                    name: templateName,
                    inputs: {
                        parameters: [{ name: "input" }],
                    },
                    steps: [[]],
                    outputs: {
                        parameters: [
                            {
                                name: "result",
                                valueFrom: {
                                    expression: step.expression,
                                },
                            },
                        ],
                    },
                });
                // Build step reference
                var stepInput = idx === 0
                    ? "{{workflow.parameters.input}}"
                    : "{{steps.step".concat(idx - 1, ".outputs.parameters.result}}");
                stepGroups.push([
                    {
                        name: templateName,
                        template: templateName,
                        arguments: {
                            parameters: [
                                {
                                    name: "input",
                                    value: stepInput,
                                },
                            ],
                        },
                    },
                ]);
            });
            // Main template
            templates.push({
                name: "main",
                steps: stepGroups,
                outputs: {
                    parameters: [
                        {
                            name: "result",
                            valueFrom: {
                                parameter: "{{steps.step".concat(config.steps.length - 1, ".outputs.parameters.result}}"),
                            },
                        },
                    ],
                },
            });
            workflow = {
                apiVersion: "argoproj.io/v1alpha1",
                kind: "Workflow",
                metadata: {
                    generateName: "chain-probe-",
                    namespace: namespace,
                },
                spec: {
                    entrypoint: "main",
                    activeDeadlineSeconds: 30,
                    serviceAccountName: getServiceAccountName(),
                    arguments: {
                        parameters: [
                            {
                                name: "input",
                                value: config.input,
                            },
                        ],
                    },
                    templates: templates,
                },
            };
            return [2 /*return*/, submitAndWait(workflow)];
        });
    });
}
export function submitRenderedWorkflow(rendered, inputOverrides) {
    return __awaiter(this, void 0, void 0, function () {
        var namespace, workflow, existing, byName, _i, _a, _b, name_1, value;
        var _c, _d;
        return __generator(this, function (_e) {
            namespace = getTestNamespace();
            workflow = __assign(__assign({}, rendered), { kind: "Workflow", metadata: __assign(__assign({}, rendered.metadata), { generateName: (((_c = rendered.metadata) === null || _c === void 0 ? void 0 : _c.name) || "test") + "-", namespace: namespace }), spec: __assign(__assign({}, rendered.spec), { activeDeadlineSeconds: 30, serviceAccountName: getServiceAccountName() }) });
            // Apply input overrides. If arguments are absent, create them from overrides.
            if (inputOverrides) {
                if (!((_d = workflow.spec.arguments) === null || _d === void 0 ? void 0 : _d.parameters)) {
                    workflow.spec.arguments = __assign(__assign({}, (workflow.spec.arguments || {})), { parameters: Object.entries(inputOverrides).map(function (_a) {
                            var name = _a[0], value = _a[1];
                            return ({ name: name, value: value });
                        }) });
                }
                else {
                    existing = workflow.spec.arguments.parameters;
                    byName = new Map(existing.map(function (p) { return [p.name, p]; }));
                    for (_i = 0, _a = Object.entries(inputOverrides); _i < _a.length; _i++) {
                        _b = _a[_i], name_1 = _b[0], value = _b[1];
                        byName.set(name_1, __assign(__assign({}, (byName.get(name_1) || { name: name_1 })), { value: value }));
                    }
                    workflow.spec.arguments.parameters = Array.from(byName.values());
                }
            }
            return [2 /*return*/, submitAndWait(workflow)];
        });
    });
}
