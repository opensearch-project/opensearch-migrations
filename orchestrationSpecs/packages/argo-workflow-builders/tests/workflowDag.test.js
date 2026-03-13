"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var src_1 = require("../src");
var expect_type_1 = require("expect-type");
describe("paramsFns runtime validation", function () {
    var sharedNothingTemplate = src_1.WorkflowBuilder.create({ k8sResourceName: "shared-do-nothing" })
        .addTemplate("doNothing", function (b) {
        var result = b
            .addOptionalInput("strParam", function (s) { return "str"; })
            .addOptionalInput("optNum", function (n) { return 42; })
            .addSteps(function (x) { return x; })
            .addExpressionOutput("out1", function (inputs) { return "outputStr"; });
        (0, expect_type_1.expectTypeOf)(result.outputsScope).toEqualTypeOf();
        return result;
    })
        .getFullScope();
    (0, expect_type_1.expectTypeOf)(sharedNothingTemplate.templates.doNothing.outputs).toEqualTypeOf();
    var templateBuilder = new src_1.TemplateBuilder({}, {}, {}, {});
    it("Creates empty dag template", function () {
        var empty = src_1.WorkflowBuilder
            .create({
            k8sResourceName: "test"
        })
            .addTemplate("emptyDag", function (b) { return b
            .addDag(function (d) { return d; }); })
            .getFullScope();
        expect(empty).toEqual({
            "metadata": {
                "k8sMetadata": { "name": "test" },
                "name": "",
                "parallelism": undefined,
                "serviceAccountName": undefined,
            },
            "templates": {
                "emptyDag": {
                    "body": { "dag": [] },
                    "inputs": {},
                    "outputs": {},
                    "retryStrategy": {}
                },
            },
            "workflowParameters": {}
        });
    });
    it("addTasks to dag template", function () {
        var d = templateBuilder.addDag(function (td) {
            var result = td.addTask("init", sharedNothingTemplate, "doNothing", function (c) { return c.register({
                strParam: "b"
            }); });
            (0, expect_type_1.expectTypeOf)(result).toExtend();
            (0, expect_type_1.expectTypeOf)(result).not.toBeAny();
            return result;
        });
    });
    it("test that a dag workflow can be created", function () {
        var wf = src_1.WorkflowBuilder
            .create({
            k8sResourceName: "test"
        })
            .addTemplate("doNothing", function (t) { return t
            //.addRequiredInput("str", typeToken<string>())
            .addSteps(function (b) { return b; }); } // no steps necessary
        )
            .addTemplate("dagWF", function (t) { return t
            .addRequiredInput("str1", (0, src_1.typeToken)())
            .addDag(function (b) { return b
            .addTask("first", sharedNothingTemplate, "doNothing", function (c) { return c.register({
            strParam: ""
        }); })
            .addTask("second", src_1.INTERNAL, "doNothing", { dependencies: ["first"] })
            .addTask("third", src_1.INTERNAL, "doNothing", { dependencies: ["first", "second"] }); }); })
            .getFullScope();
        (0, expect_type_1.expectTypeOf)(wf).not.toBeAny();
        expect(wf).toEqual({
            "metadata": {
                "k8sMetadata": { "name": "test" },
                "name": "",
                "parallelism": undefined,
                "serviceAccountName": undefined,
            },
            "templates": {
                "dagWF": {
                    "body": {
                        "dag": [
                            {
                                "args": {
                                    "strParam": "",
                                },
                                "name": "first",
                                "templateRef": {
                                    "name": "shared-do-nothing",
                                    "template": "doNothing",
                                },
                            },
                            {
                                "args": {},
                                "dependencies": [
                                    "first",
                                ],
                                "name": "second",
                                "template": "doNothing",
                            },
                            {
                                "args": {},
                                "dependencies": [
                                    "first",
                                    "second",
                                ],
                                "name": "third",
                                "template": "doNothing",
                            },
                        ],
                    },
                    "inputs": {
                        "str1": {
                            "description": undefined,
                        },
                    },
                    "outputs": {},
                    "retryStrategy": {}
                },
                "doNothing": {
                    "body": {
                        "steps": [],
                    },
                    "inputs": {},
                    "outputs": {},
                    "retryStrategy": {}
                },
            },
            "workflowParameters": {}
        });
    });
});
