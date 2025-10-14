#  Argo-Workflows Builder Library

This directory contains TypeScript definitions of Argo Workflows templates.

The [WorkflowBuilder](./src/models/workflowBuilder.ts) class provides the main 
entry point to begin building a workflow template.  Nearly all the 
developer-facing constructs use a _strongly typed_ fluent style.  As values
(parameters, templates, steps, tasks, etc) are defined, they can be referenced
in further scopes.  When they're referenced, they retain type information 
that they were defined with (explicitly or implicitly).  That allows those 
references so to be used in a type-consistent way, either as part of 
expressions or as inputs to other templates.

Models are rendered into Argo-Workflow Kubernetes resources with the function
[renderWorkflowTemplate](./src/renderers/argoResourceRenderer.ts).  That function
does final conversions to adjust for nuances of Argo Workflows' expression and
configuration models.  Those rendered resources should be complete 
Argo Workflow Template resources that are ready to send to a Kubernetes cluster. 
