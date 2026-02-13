import { WorkflowBuilder, renderWorkflowTemplate, defineParam } from "../../../src";
import { submitRenderedWorkflow } from "../infra/probeHelper";

describe("Negative Model Validation Tests", () => {
  test("serialized object is not plain string", async () => {
    const wf = WorkflowBuilder.create({ k8sResourceName: "test-object-not-string" })
      .addParams({
        config: defineParam({ expression: { host: "localhost", port: 9200 } }),
      })
      .addTemplate("main", t => t.addSuspend(0))
      .getFullScope();
    
    const rendered = renderWorkflowTemplate(wf);
    const params = (rendered.spec.arguments && !Array.isArray(rendered.spec.arguments)) ? rendered.spec.arguments.parameters as any[] : [];
    const configParam = params.find((p: any) => p.name === "config");
    
    // Should be JSON string, not plain "localhost" or "[object Object]"
    expect(configParam.value).not.toBe("localhost");
    expect(configParam.value).not.toBe("[object Object]");
    expect(configParam.value).not.toBe("{ host: localhost, port: 9200 }");
    
    const parsed = JSON.parse(configParam.value);
    expect(parsed).toEqual({ host: "localhost", port: 9200 });
  });

  test("number is not stringified", async () => {
    const wf = WorkflowBuilder.create({ k8sResourceName: "test-number-not-string" })
      .addParams({
        count: defineParam({ expression: 42 }),
      })
      .addTemplate("main", t => t.addSuspend(0))
      .getFullScope();
    
    const rendered = renderWorkflowTemplate(wf);
    const params = (rendered.spec.arguments && !Array.isArray(rendered.spec.arguments)) ? rendered.spec.arguments.parameters as any[] : [];
    const countParam = params.find((p: any) => p.name === "count");
    
    expect(countParam.value).not.toBe("42");
    expect(countParam.value).not.toBe("42.0");
    expect(countParam.value).toBe(42);
  });

  test("boolean is not capitalized", async () => {
    const wf = WorkflowBuilder.create({ k8sResourceName: "test-bool-not-cap" })
      .addParams({
        flag: defineParam({ expression: true }),
      })
      .addTemplate("main", t => t.addSuspend(0))
      .getFullScope();
    
    const rendered = renderWorkflowTemplate(wf);
    const params = (rendered.spec.arguments && !Array.isArray(rendered.spec.arguments)) ? rendered.spec.arguments.parameters as any[] : [];
    const flagParam = params.find((p: any) => p.name === "flag");
    
    expect(flagParam.value).not.toBe("true");
    expect(flagParam.value).not.toBe("True");
    expect(flagParam.value).not.toBe("TRUE");
    expect(flagParam.value).toBe(true);
  });

  test("workflow with parameters runs successfully", async () => {
    const wf = WorkflowBuilder.create({ k8sResourceName: "test-params-work" })
      .addParams({
        input: defineParam({ expression: "test-value" }),
      })
      .addTemplate("main", t => t.addSuspend(0))
      .getFullScope();
    
    const rendered = renderWorkflowTemplate(wf);
    const result = await submitRenderedWorkflow(rendered);
    
    expect(result.phase).toBe("Succeeded");
  });
});
