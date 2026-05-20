# Workflow Scripts

These scripts are implementation details for the generated Argo migration workflows.

ONLY the Argo migration workflows should call these scripts as part of their automated workflow execution. Do not treat them as user-facing CLI commands, stable public APIs, or general-purpose operational tools.

The migration console image should install these scripts into a hidden destination directory, currently planned as:

```text
/root/workflows/.workflowScripts
```

Workflow templates should receive the installed script directory as a workflow parameter and invoke scripts by path from that parameter. The TypeScript workflow definitions should not hard-code the final image layout.
