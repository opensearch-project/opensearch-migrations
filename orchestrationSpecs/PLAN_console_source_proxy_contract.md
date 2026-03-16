## Goal

Change the generated migration console config contract so that:

- `source_cluster` remains `source_cluster`
- `target_cluster` remains `target_cluster`
- there is no top-level `proxy_cluster`
- `source_cluster` may include an optional nested `proxy` block

The user schema remains unchanged. Proxies stay top-level in user config and are only denormalized onto sources in the Argo/workflow model.

## Scope

### TypeScript in `orchestrationSpecs`

1. Update the Argo-facing schemas in `packages/schemas/src/argoSchemas.ts`.
2. Denormalize a single proxy onto each source in `packages/config-processor/src/migrationConfigTransformer.ts`.
3. Fail config transformation when a source maps to multiple proxies and a single console proxy would be ambiguous.
4. Update `packages/migration-workflow-templates/src/workflowTemplates/migrationConsole.ts` to emit:
   - `source_cluster`
   - `target_cluster`
   - nested `source_cluster.proxy` when present
5. Update affected tests and rendered snapshots.

### Python in `../migrationConsole`

1. Remove the new top-level `proxy_cluster` contract.
2. Keep `source_cluster` / `target_cluster` as the schema contract.
3. Add a source-specific cluster model that can carry optional nested proxy config.
4. Only allow explicit proxy targeting for:
   - connection-check
   - cat-indices
   - curl
5. Keep all other commands direct to source/target only.

## Implementation Notes

- The proxy is not a cluster peer. It is an alternate route for the source only.
- Source auth remains on the source config; the nested proxy only overrides route-specific connection fields.
- `curl` is the intentional escape hatch for hitting the proxy beyond the two diagnostic commands.
- The workflow model should resolve source-to-proxy relationships in the config transformer, not inside `getConsoleConfig`.
- Only the CLI destination names are shortened to `source`, `target`, and `proxy`.
