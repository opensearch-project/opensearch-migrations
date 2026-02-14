import { TemplateBuilder, WorkflowBuilder } from "../../../src/index.js";

/**
 * Creates a test workflow from a template builder function.
 * Reduces boilerplate by automatically creating a workflow with a single template as entrypoint.
 * 
 * @param templateBuilder - Function that builds the template (adds inputs, steps, outputs)
 * @returns A workflow scope ready to be rendered and submitted
 * 
 * @example
 * const wf = makeTestWorkflow(t => t
 *   .addOptionalInput("a", () => "default")
 *   .addSteps(s => s.addStepGroup(c => c))
 *   .addExpressionOutput("result", (ctx) => expr.literal(ctx.inputs.a))
 * );
 */
export function makeTestWorkflow(
  templateBuilder: (
    t: TemplateBuilder<{ workflowParameters: {}; templates: {} }, {}, {}, {}>
  ) => { getFullTemplateScope(): unknown }
) {
  const uniqueName = `test-${Date.now()}-${Math.random().toString(36).substring(7)}`.toLowerCase();
  
  return WorkflowBuilder.create({ k8sResourceName: uniqueName as any })
    .addTemplate("test" as any, (t) => templateBuilder(t))
    .setEntrypoint("test" as any)
    .getFullScope();
}
