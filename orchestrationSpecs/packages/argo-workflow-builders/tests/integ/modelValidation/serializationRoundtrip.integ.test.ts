import { WorkflowBuilder, renderWorkflowTemplate, defineParam } from "../../../src";
import { submitRenderedWorkflow } from "../infra/probeHelper";

describe("Serialization Round-trip Tests", () => {
  test("Object param serialized as JSON string", async () => {
    const wf = WorkflowBuilder.create({ k8sResourceName: "test-object-param" })
      .addParams({
        config: defineParam({ expression: { host: "localhost", port: 9200 } }),
      })
      .addTemplate("main", t => t.addSuspend(0))
      .getFullScope();
    
    const rendered = renderWorkflowTemplate(wf);
    
    // Check rendered structure
    const params = (rendered.spec.arguments && !Array.isArray(rendered.spec.arguments)) ? rendered.spec.arguments.parameters as any[] : [];
    const configParam = params.find((p: any) => p.name === "config");
    expect(configParam).toBeDefined();
    if (!configParam) return;
    
    expect(typeof configParam.value).toBe("string");
    expect(JSON.parse(configParam.value)).toEqual({ host: "localhost", port: 9200 });
    
    // Verify Argo runtime
    const result = await submitRenderedWorkflow(rendered);
    expect(result.phase).toBe("Succeeded");
  });

  test("Number param stays as number in YAML", async () => {
    const wf = WorkflowBuilder.create({ k8sResourceName: "test-number-param" })
      .addParams({
        count: defineParam({ expression: 42 }),
      })
      .addTemplate("main", t => t.addSuspend(0))
      .getFullScope();
    
    const rendered = renderWorkflowTemplate(wf);
    
    // Check rendered structure
    const params = (rendered.spec.arguments && !Array.isArray(rendered.spec.arguments)) ? rendered.spec.arguments.parameters as any[] : [];
    const countParam = params.find((p: any) => p.name === "count");
    expect(countParam).toBeDefined();
    if (!countParam) return;
    
    expect(countParam.value).toBe(42);
    
    // Verify Argo runtime
    const result = await submitRenderedWorkflow(rendered);
    expect(result.phase).toBe("Succeeded");
  });

  test("Boolean param", async () => {
    const wf = WorkflowBuilder.create({ k8sResourceName: "test-bool-param" })
      .addParams({
        flag: defineParam({ expression: true }),
      })
      .addTemplate("main", t => t.addSuspend(0))
      .getFullScope();
    
    const rendered = renderWorkflowTemplate(wf);
    
    // Check rendered structure
    const params = (rendered.spec.arguments && !Array.isArray(rendered.spec.arguments)) ? rendered.spec.arguments.parameters as any[] : [];
    const flagParam = params.find((p: any) => p.name === "flag");
    expect(flagParam).toBeDefined();
    if (!flagParam) return;
    
    expect(flagParam.value).toBe(true);
    
    // Verify Argo runtime
    const result = await submitRenderedWorkflow(rendered);
    expect(result.phase).toBe("Succeeded");
  });
});
