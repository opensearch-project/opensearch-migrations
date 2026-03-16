import { WorkflowBuilder, renderWorkflowTemplate, defineParam } from "../../../src";
import { submitRenderedWorkflow } from "../infra/probeHelper";

describe("Renderer to Argo Tests", () => {
  test("Workflow with parameters renders and runs", async () => {
    const wf = WorkflowBuilder.create({ k8sResourceName: "mvr-basic" })
      .addParams({
        input: defineParam({ expression: "test-value" }),
      })
      .addTemplate("main", t => t.addSuspend(0))
      .getFullScope();
    
    const rendered = renderWorkflowTemplate(wf);
    const result = await submitRenderedWorkflow(rendered);
    
    expect(result.phase).toBe("Succeeded");
  });

  test("Workflow with steps template runs", async () => {
    const suspendWf = WorkflowBuilder.create({ k8sResourceName: "mvr-steps-suspend" })
      .addTemplate("suspend-task", t => t.addSuspend(0))
      .getFullScope();
    
    const wf = WorkflowBuilder.create({ k8sResourceName: "mvr-steps" })
      .addTemplate("main", t => t
        .addSteps(sb => sb
          .addStep("task1", suspendWf, "suspend-task")
        )
      )
      .getFullScope();
    
    const rendered = renderWorkflowTemplate(wf);
    const result = await submitRenderedWorkflow(rendered);
    
    expect(result.phase).toBe("Succeeded");
  });

  test("Workflow with DAG template runs", async () => {
    const suspendWf = WorkflowBuilder.create({ k8sResourceName: "mvr-dag-suspend" })
      .addTemplate("suspend-task", t => t.addSuspend(0))
      .getFullScope();
    
    const wf = WorkflowBuilder.create({ k8sResourceName: "mvr-dag" })
      .addTemplate("main", t => t
        .addDag(db => db
          .addTask("task1", suspendWf, "suspend-task")
        )
      )
      .getFullScope();
    
    const rendered = renderWorkflowTemplate(wf);
    const result = await submitRenderedWorkflow(rendered);
    
    expect(result.phase).toBe("Succeeded");
  });
});
