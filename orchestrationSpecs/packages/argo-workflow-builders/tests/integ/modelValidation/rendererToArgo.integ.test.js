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
import { WorkflowBuilder, renderWorkflowTemplate, defineParam } from "../../../src";
import { submitRenderedWorkflow } from "../infra/probeHelper";
describe("Renderer to Argo Tests", function () {
    test("Workflow with parameters renders and runs", function () { return __awaiter(void 0, void 0, void 0, function () {
        var wf, rendered, result;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    wf = WorkflowBuilder.create({ k8sResourceName: "mvr-basic" })
                        .addParams({
                        input: defineParam({ expression: "test-value" }),
                    })
                        .addTemplate("main", function (t) { return t.addSuspend(0); })
                        .getFullScope();
                    rendered = renderWorkflowTemplate(wf);
                    return [4 /*yield*/, submitRenderedWorkflow(rendered)];
                case 1:
                    result = _a.sent();
                    expect(result.phase).toBe("Succeeded");
                    return [2 /*return*/];
            }
        });
    }); });
    test("Workflow with steps template runs", function () { return __awaiter(void 0, void 0, void 0, function () {
        var suspendWf, wf, rendered, result;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    suspendWf = WorkflowBuilder.create({ k8sResourceName: "mvr-steps-suspend" })
                        .addTemplate("suspend-task", function (t) { return t.addSuspend(0); })
                        .getFullScope();
                    wf = WorkflowBuilder.create({ k8sResourceName: "mvr-steps" })
                        .addTemplate("main", function (t) { return t
                        .addSteps(function (sb) { return sb
                        .addStep("task1", suspendWf, "suspend-task"); }); })
                        .getFullScope();
                    rendered = renderWorkflowTemplate(wf);
                    return [4 /*yield*/, submitRenderedWorkflow(rendered)];
                case 1:
                    result = _a.sent();
                    expect(result.phase).toBe("Succeeded");
                    return [2 /*return*/];
            }
        });
    }); });
    test("Workflow with DAG template runs", function () { return __awaiter(void 0, void 0, void 0, function () {
        var suspendWf, wf, rendered, result;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    suspendWf = WorkflowBuilder.create({ k8sResourceName: "mvr-dag-suspend" })
                        .addTemplate("suspend-task", function (t) { return t.addSuspend(0); })
                        .getFullScope();
                    wf = WorkflowBuilder.create({ k8sResourceName: "mvr-dag" })
                        .addTemplate("main", function (t) { return t
                        .addDag(function (db) { return db
                        .addTask("task1", suspendWf, "suspend-task"); }); })
                        .getFullScope();
                    rendered = renderWorkflowTemplate(wf);
                    return [4 /*yield*/, submitRenderedWorkflow(rendered)];
                case 1:
                    result = _a.sent();
                    expect(result.phase).toBe("Succeeded");
                    return [2 /*return*/];
            }
        });
    }); });
});
