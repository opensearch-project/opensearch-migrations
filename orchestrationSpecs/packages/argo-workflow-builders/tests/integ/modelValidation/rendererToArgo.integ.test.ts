import { WorkflowBuilder, renderWorkflowTemplate, defineParam } from "../../../src";
import { submitRenderedWorkflow } from "../infra/probeHelper";
import { getKubeConfig, getTestNamespace } from "../infra/argoCluster";
import { KubeConfig, CoreV1Api } from "@kubernetes/client-node";

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

  test("WaitForExistingResource with retryStrategy runs against real resource", async () => {
    const namespace = getTestNamespace();
    const configMapName = "mvr-wait-retry-target";

    const kc = new KubeConfig();
    kc.loadFromString(getKubeConfig());
    const coreApi = kc.makeApiClient(CoreV1Api);

    // Point the default artifact repository to the "empty" key (which has no
    // artifact driver) so resource templates don't fail with "artifact driver
    // test not found" from the quick-start-minimal minio plugin.
    await coreApi.createNamespacedConfigMap({
      namespace,
      body: {
        metadata: {
          name: "artifact-repositories",
          annotations: { "workflows.argoproj.io/default-artifact-repository": "empty" },
        },
        data: { empty: "" },
      },
    }).catch(() => { /* may already exist */ });

    // Create a ConfigMap for the workflow to wait on
    try {
      await coreApi.deleteNamespacedConfigMap({ name: configMapName, namespace });
    } catch { /* may not exist */ }
    await coreApi.createNamespacedConfigMap({
      namespace,
      body: { metadata: { name: configMapName }, data: { ready: "true" } },
    });

    try {
      const wf = WorkflowBuilder.create({
        k8sResourceName: "mvr-wait-retry",
        serviceAccountName: "test-runner",
      })
        .addTemplate("wait", t => t
          .addWaitForExistingResource(b => b
            .setDefinition({
              resource: { apiVersion: "v1", kind: "ConfigMap", name: configMapName },
              conditions: { successCondition: "data.ready == true" },
            })
            .addRetryParameters({
              limit: "3",
              retryPolicy: "Always",
              backoff: { duration: "1", factor: "1", cap: "5" },
            })
          )
        )
        .setEntrypoint("wait")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);

      expect(result.phase).toBe("Succeeded");

      // Verify the rendered template actually included retryStrategy
      const tmpl = rendered.spec.templates.find((t: any) => t.name === "wait");
      expect(tmpl.retryStrategy).toBeDefined();
      expect(tmpl.retryStrategy.limit).toBe("3");
    } finally {
      try {
        await coreApi.deleteNamespacedConfigMap({ name: configMapName, namespace });
      } catch { /* cleanup best-effort */ }
    }
  });
});
