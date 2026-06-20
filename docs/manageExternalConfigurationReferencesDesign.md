# Manage External Configuration References

`workflow manage` should make external configuration references visible, selectable, creatable, and validatable without asking users to memorize Kubernetes resource shapes. This design covers the workflow YAML fields that refer to Secrets, ConfigMaps, mountable OCI images, and the adjacent cert-manager issuer reference.

The important split stays the same as the rest of manage:

- TypeScript owns workflow schema semantics, concrete reference planning, and path-specific diagnostics.
- Python owns Textual presentation, Kubernetes reads/writes, and command orchestration.
- Image and Kubernetes existence checks are standalone validation steps. They are not hidden inside Zod parsing and they are not tied only to the interactive UI.

There is no Node.js daemon in this design. Manage uses one-shot TS commands at committed interaction boundaries, plus cancellable Python-side validation jobs for cluster/image checks.

## Goals

- Show matching existing ConfigMaps and Secrets at fields that reference them.
- Filter resources by expected type and keys, while still letting users inspect near misses.
- Let users create the common resource shapes from inside manage.
- Let users enter image references manually, then validate that the image can be pulled and that the requested file or directory exists.
- Reuse the same external-reference validation from `workflow configure`, `workflow submit`, and manage.
- Keep external-reference metadata close to `userSchemas.ts` so new fields do not require bespoke Python knowledge.

## Non-Goals

- Do not make external ConfigMaps and Secrets first-class migration CRs.
- Do not owner-reference user-provided Secrets or ConfigMaps to migration resources.
- Do not decode and display existing Secret values in the tree.
- Do not build or push transform images from manage.
- Do not make image pull validation part of pure schema validation. Pulling images is a side-effecting cluster/registry operation.

## Reference Hint Contract

The schema should grow an external-reference hint next to the existing UI hints:

```ts
type ExternalRefKind =
  | "secret"
  | "configMap"
  | "image"
  | "certManagerIssuer";

type ExternalRefPurpose =
  | "http-basic-auth"
  | "http-mtls-client-cert"
  | "proxy-server-tls"
  | "proxy-client-ca"
  | "kafka-scram-password"
  | "kafka-ca"
  | "log4j-config"
  | "transform-entrypoint"
  | "transform-context-file"
  | "transform-context-directory"
  | "cert-manager-issuer";

type ExternalRefHint = {
  kind: ExternalRefKind;
  purpose: ExternalRefPurpose;
  displayName: string;
  description?: string;
  k8s?: {
    resource: "Secret" | "ConfigMap" | "Issuer" | "ClusterIssuer";
    acceptedSecretTypes?: string[];
    requiredKeys?: string[];
    recommendedKeys?: string[];
    keyPatterns?: string[];
    contentValidationIds?: ExternalContentValidationId[];
  };
  image?: {
    expects: "file" | "directory";
    recommendedPathPatterns?: string[];
    contentValidationIds?: ExternalContentValidationId[];
  };
  create?: ExternalResourceCreateDescriptor;
};

type ExternalResourceCreateDescriptor = {
  label: string;
  fields: ExternalResourceFormField[];
  output:
    | {
        kind: "Secret";
        type: string;
        stringData: Record<string, {fromField: string}>;
      }
    | {
        kind: "ConfigMap";
        data: Record<string, {fromField: string}>;
      };
  apply:
    | {target: "scalarName"; nameField: string}
    | {target: "fileRefConfigMap"; nameField: string; pathField: string};
};

type ExternalResourceFormField = {
  name: string;
  label: string;
  input: "name" | "text" | "password" | "multilineText" | "secretMultilineText" | "select";
  required?: boolean;
  default?: string;
  sensitive?: boolean;
  options?: string[];
  validationIds?: ExternalFormValidationId[];
  confirm?: boolean;
};

type ExternalFormValidationId =
  | "k8s-name"
  | "configmap-key"
  | "non-empty"
  | ExternalContentValidationId;

type ExternalContentValidationId =
  | "non-empty-keys"
  | "pem-certificate-chain"
  | "pem-private-key"
  | "tls-certificate-key-pair"
  | "log4j-properties"
  | "javascript-syntax"
  | "python-syntax"
  | "json";
```

The hint is exported into JSON Schema as `x-external-ref` and copied into `EditStateV1` nodes as `externalRef`. For generic `FILE_REF` fields, the hint must be attached at the use site, not only to `FILE_REF`, because a JavaScript transform file, Python transform file, Log4j ConfigMap, and CA certificate all have different filters and create forms.

The create descriptor is intentionally declarative. Python renders the field list with generic controls, creates the described Kubernetes Secret or ConfigMap, and applies the described edit operation back to YAML. Purpose-specific behavior should come from named validation IDs, not from Python branching on YAML paths. `sensitive` marks fields that must not be displayed after creation; it defaults to true for `password` and `secretMultilineText` inputs, and false otherwise. Existing Secret values are treated as sensitive unless they map back to a descriptor field that explicitly marks them non-sensitive.

Examples:

```ts
secretName: z.string()
  .regex(K8S_NAMING_PATTERN)
  .describe("Name of a Kubernetes Secret containing 'username' and 'password' keys for HTTP Basic authentication.")
  .externalRef({
    kind: "secret",
    purpose: "http-basic-auth",
    displayName: "HTTP Basic Auth Secret",
    k8s: {
      resource: "Secret",
      acceptedSecretTypes: ["kubernetes.io/basic-auth", "Opaque"],
      requiredKeys: ["username", "password"],
      contentValidationIds: ["non-empty-keys"]
    },
    create: {
      label: "HTTP Basic Auth Secret",
      fields: [
        {name: "name", label: "Secret name", input: "name", required: true, validationIds: ["k8s-name"]},
        {name: "username", label: "Username", input: "text", required: true},
        {name: "password", label: "Password", input: "password", required: true, confirm: true}
      ],
      output: {
        kind: "Secret",
        type: "kubernetes.io/basic-auth",
        stringData: {
          username: {fromField: "username"},
          password: {fromField: "password"}
        }
      },
      apply: {target: "scalarName", nameField: "name"}
    }
  });

trustedClientCaFile: FILE_REF.optional()
  .describe("PEM trusted CA certificate file used to verify client certificates accepted by the capture proxy.")
  .externalRef({
    kind: "configMap",
    purpose: "proxy-client-ca",
    displayName: "Trusted Client CA",
    k8s: {
      resource: "ConfigMap",
      recommendedKeys: ["ca.crt", "ca.pem"],
      keyPatterns: ["*.crt", "*.pem"],
      contentValidationIds: ["pem-certificate-chain"]
    },
    image: {
      expects: "file",
      recommendedPathPatterns: ["*.crt", "*.pem"],
      contentValidationIds: ["pem-certificate-chain"]
    },
    create: {
      label: "Trusted Client CA ConfigMap",
      fields: [
        {name: "name", label: "ConfigMap name", input: "name", required: true, validationIds: ["k8s-name"]},
        {name: "key", label: "Key", input: "text", required: true, default: "ca.crt", validationIds: ["configmap-key"]},
        {name: "contents", label: "CA PEM", input: "multilineText", required: true, validationIds: ["pem-certificate-chain"]}
      ],
      output: {
        kind: "ConfigMap",
        data: {
          "{{key}}": {fromField: "contents"}
        }
      },
      apply: {target: "fileRefConfigMap", nameField: "name", pathField: "key"}
    }
  });
```

## Validation ID Registry

Validation IDs are not arbitrary strings. They are part of the schema metadata contract:

- TypeScript defines the allowed IDs and exports them through JSON Schema metadata.
- Python implements the runtime validator registry used by Textual forms, Kubernetes resource inspection, and image-file inspection.
- Tests must fail if an exported validation ID has no Python implementation or if Python implements an ID that is not in the TS metadata type.

`validationIds` on create-form fields run while the user edits a create form and again immediately before any Kubernetes write. They validate user-provided form values, such as a Secret name or pasted PEM.

`contentValidationIds` on `k8s` or `image` references run against existing resources during external-resource preflight:

- ConfigMap values can be inspected directly.
- Secret values may be decoded for validation but must never be rendered back into the picker or tree.
- Image file contents can be inspected only when validation is running at `pull-and-path`; lower image-check levels can validate only reference syntax and path presence in YAML.

`confirm` is not a validator. It is form behavior for sensitive fields: Textual renders a second hidden input and requires the two values to match before `validationIds` and the create operation run.

Validator semantics:

| ID | Applies to | Meaning |
| --- | --- | --- |
| `k8s-name` | form fields | Kubernetes DNS-style resource name. |
| `configmap-key` | form fields | ConfigMap key compatible with `CONFIGMAP_FILE_KEY`. |
| `non-empty` | form fields/content | Value must not be empty after trimming. |
| `non-empty-keys` | Secret/ConfigMap content | Required keys exist and have non-empty values when values are available. |
| `pem-certificate-chain` | form fields/content/image files | One or more PEM certificates parse successfully. |
| `pem-private-key` | form fields/content | PEM private key parses successfully. |
| `tls-certificate-key-pair` | Secret content/create form | `tls.crt` and `tls.key` parse and the leaf certificate matches the private key. |
| `log4j-properties` | ConfigMap content | Content is non-empty and looks like Log4j2 properties. |
| `javascript-syntax` | ConfigMap content/image files | Script content parses as JavaScript when a syntax checker is available. |
| `python-syntax` | ConfigMap content/image files | Script content compiles as Python when a syntax checker is available. |
| `json` | form fields/content | Content parses as JSON. |

## Validation Boundary

The JSON Schema contains the static contract: this field can point at a Secret, this Secret needs `username` and `password`, this `FILE_REF` points at a JavaScript file, and so on. That is enough for generic editors to choose the right picker and create form.

Validation still needs a concrete reference plan for the current YAML. The plan answers questions the static schema does not answer by itself:

- which record entries and array items currently exist;
- which union variant is selected, such as basic auth versus mTLS;
- which `FILE_REF` variant is selected, such as ConfigMap versus image;
- what actual resource name and key/path value should be checked;
- which path should receive diagnostics after a Kubernetes or image check fails;
- which incomplete loose-parse branches can still be checked while the user is building a config.

That concrete reference plan is internal service plumbing, not a user-facing workflow command. It can be implemented as a TS function behind `ScriptRunner`, a config-processor subcommand used only by Python, or folded into the existing edit-state/resource-projection calls if that is cleaner. The design requirement is the contract between TS and Python, not the executable name.

The internal plan shape is:

```json
{
  "formatVersion": 1,
  "workflowName": "migration-workflow",
  "validationMode": "loose",
  "references": [
    {
      "id": "sourceClusters.source.authConfig.basic.secretName",
      "path": ["sourceClusters", "source", "authConfig", "basic", "secretName"],
      "value": "source-creds",
      "hint": {
        "kind": "secret",
        "purpose": "http-basic-auth",
        "k8s": {
          "resource": "Secret",
          "acceptedSecretTypes": ["kubernetes.io/basic-auth", "Opaque"],
          "requiredKeys": ["username", "password"]
        }
      }
    }
  ]
}
```

Python consumes the plan, performs the Kubernetes and image checks, and returns a report to the shared validation layer:

```json
{
  "formatVersion": 1,
  "namespace": "migrations",
  "items": [
    {
      "id": "sourceClusters.source.authConfig.basic.secretName",
      "path": ["sourceClusters", "source", "authConfig", "basic", "secretName"],
      "status": "missing",
      "message": "Secret source-creds does not exist"
    }
  ]
}
```

An internal report-mapping helper turns resource-check results into the same diagnostic shape manage already renders. The important point is that path mapping stays schema-aware while Kubernetes and registry access stay in Python.

No public workflow subcommand is required for normal operation. `workflow configure`, `workflow submit`, and manage should call a shared external-resource preflight internally after regular schema validation. A standalone developer or CI command can be added later if useful, but it is not required for the interactive design.

## Configure and Submit Wiring

External-resource checks are not manage-only. The same preflight runs from:

- `workflow manage`, after committed edits and before submit;
- `workflow configure`, after schema validation and before the updated config is considered ready;
- `workflow submit`, after strict schema validation/projection and before replacing the running workflow.

`configure` uses the preflight to surface missing or malformed resources early. In interactive paths, it can offer the same declarative create flows used by manage. In non-interactive paths, it should report the same diagnostics without opening UI. `submit` treats missing Secrets, missing ConfigMaps, missing required keys, and failed required image path checks as blocking preflight failures.

## Kubernetes Inventory Model

Python maintains a short-lived inventory cache per namespace while manage is open:

- ConfigMaps: name, labels, annotations, keys, approximate text sizes.
- Secrets: name, type, keys only. Values are not decoded for list rendering.
- Issuers and ClusterIssuers: name, kind, readiness condition if the cert-manager CRDs are installed and readable.

Pickers stay compact by default. They show matching resources and the current YAML value first, sorted by fit and name, and keep near misses behind an explicit `a All` command. This keeps namespaces with many Secrets or ConfigMaps usable while still making questionable resources available when the user needs to inspect stale inventory, RBAC gaps, or near misses. The picker paginates the visible rows with `n` and `p`; pagination is local to the already fetched inventory and does not re-query Kubernetes.

- `matching`: resource type and required keys match the field.
- `warn`: the resource is missing, incomplete, unreadable, has a questionable type, has extra keys, or only weakly matches the expected format.

If the current YAML value names a resource that is not in inventory, the picker includes a synthetic `warn` row for that value and keeps it visible in the default view so the user can keep, replace, or inspect the problem in context. Manual entry remains available from the picker footer so users are not blocked by stale inventory or limited RBAC.

ConfigMap values and descriptor-owned non-sensitive Secret fields can be viewed and updated from the picker. Sensitive Secret fields are never shown. Updating a Secret preloads only non-sensitive fields; sensitive fields start blank and mean "leave unchanged" when the key already exists, or become required when the key is missing.

## Kubernetes Write Timing

Creating or updating an external Secret or ConfigMap from manage writes that Kubernetes resource immediately. The workflow YAML only stores references to these objects, and Secret contents must not be staged in pending YAML while waiting for submit.

The create/update sequence is:

1. Render the descriptor-driven form in place of the picker table.
2. Validate form fields and content checks that can run locally.
3. Create or patch the Secret or ConfigMap in Kubernetes.
4. Refresh inventory for that resource kind.
5. Apply the selected name or file reference to pending YAML.
6. Close the modal and return to the edit tree.

If the Kubernetes write fails, the YAML reference is not changed. If the Kubernetes write succeeds but the YAML edit fails, manage leaves the external resource in place, reports the failed edit, and lets the user select the newly created resource from the refreshed picker.

`workflow submit` does not create or update external resources as a side effect. It validates that referenced resources already exist and match the descriptor. Interactive `workflow configure` may use the same immediate create/update flow, but non-interactive configure and submit only report diagnostics.

## RBAC

Creating the core resources from manage should not require new RBAC in the standard installation. The `migration-console-access-role` already grants namespace-scoped `get`, `watch`, `list`, `create`, `update`, `patch`, `delete`, and `deletecollection` on core `secrets` and `configmaps`. That is the same permission surface used today by `workflow configure credentials` through `SecretStore` and by the workflow config store through ConfigMaps.

Two adjacent features may need care:

- cert-manager issuer picker: the console Role does not currently grant `get/list/watch` on cert-manager `issuers` or cluster-scoped `clusterissuers`. The picker should degrade to manual name entry when RBAC denies listing, or the chart should add read-only issuer permissions if issuer discovery is important.
- image pull/path validation: use a short-lived Pod if possible, because the console Role already has Pod create/read/delete verbs. A Job-based validator would require adding namespaced `batch/jobs` permissions to the console Role.

## Image Validation Model

Image fields stay manual-entry fields with validation support:

- image reference;
- pull policy;
- expected file or directory path;
- optional platform override later, defaulting to the workflow platform.

Validation is a separate operation because it may contact registries or start cluster pods. The validator supports three levels:

| Level | Behavior | Use |
| --- | --- | --- |
| `none` | Only schema syntax and required path checks run. | Offline editing. |
| `manifest` | Confirm the registry reference resolves for the requested platform. | Fast command-line preflight. |
| `pull-and-path` | Confirm the cluster can pull the image and the requested file/directory exists. | Submit preflight and explicit manage validation. |

The preferred `pull-and-path` implementation is a short-lived Kubernetes validation Pod in the target namespace. It uses the same image-volume mechanism the workflow uses and a migration-console container to run filesystem checks against the mounted image. That validates the exact cluster pull path instead of merely proving the local machine can pull the image.

Manage starts image validation asynchronously and attaches a cancellation token to the selected field. Moving away or editing the field cancels the stale result. Configure and submit preflight runs to completion and reports all image failures together.

## UI Model

External references render as normal edit tree rows with an additional resource status segment:

```text
authConfig: < basic > [REQ 1]
+-- secretName: source-creds  [Secret matching: username,password]
```

Missing or malformed resources propagate status upward:

```text
targetClusters [ERR 1]
+-- target [ERR 1]
    +-- authConfig: < mtls > [ERR 1]
        +-- clientSecretName: target-client-cert  [Secret missing tls.key]
```

Pressing `Enter` on an external-reference row opens the field-specific picker or editor.

The screens below are render-target examples, not bespoke widget specifications. They are the expected output of generic renderers fed by `externalRef`, inventory rows, and create descriptors:

- picker renderer: consumes `ExternalRefHint`, current value, inventory rows, and allowed actions;
- create-form renderer: consumes `ExternalResourceCreateDescriptor`;
- image editor renderer: consumes the image half of `ExternalRefHint` plus current `FILE_REF` values;
- issuer picker renderer: consumes the same picker model, with resource labels formatted as `Kind/name`.

Python should not branch on YAML paths to choose these screens. It should branch only on generic descriptor shape: Secret/ConfigMap name reference, ConfigMap-backed file ref, image-backed file ref, issuer reference, or create descriptor. A new external reference should require no Python code when it uses existing descriptor shapes and validation IDs.

Tests should assert both descriptor-to-model and model-to-rendered-target behavior:

- exported schema metadata contains the expected `externalRef` and create descriptor;
- TS edit/reference DTO includes the descriptor at the edited path;
- Python converts inventory into `matching` or `warn` picker rows from descriptor rules;
- the rendered Textual screen contains the target labels/actions shown by the model;
- selecting a row or submitting a form sends the expected generic edit operation back to TS.

Picker actions use single-key shortcuts. The footer should include the available subset of:

```text
[Enter Select] [c Create] [m Manual] [v View] [u Update] [p Prev] [n Next] [a All/Matches] [Esc Cancel]
```

`c` replaces the picker table with the descriptor-driven create form inside the same modal. `v` and `u` similarly replace the table with a view or update pane. A successful create/update closes the modal and returns directly to the edit tree after the Kubernetes write, inventory refresh, and YAML edit have completed. Cancel from a create/update pane returns to the picker when no write occurred.

### Example: Secret Picker

```text
Select HTTP Basic Auth Secret

  source-creds
  legacy-creds (current)

Keys: username, password.
1-2/2 shown. 2 hidden (a all).

[Select] [c Create] [m Manual] [v View] [u Update] [p Prev] [n Next] [a All] [Cancel]
```

Rows are one line: resource name plus `(current)` when applicable. All mode also annotates incomplete resources inline, for example `admin-creds (missing password)`. Match/warn details, required keys, and pagination state live in the bottom hint and update as the user moves through the list. Missing keys are shown on their own hint line for the selected row. Kubernetes Secret type names such as `Opaque` are inventory metadata, not picker row labels; they remain available in the view pane when they are useful for inspection. Selecting a `matching` row applies the name into YAML. Selecting a `warn` row opens a confirmation with the diagnostic and still lets the user choose it. Creating a Secret creates the Kubernetes Secret first, refreshes inventory, then applies the chosen name to the YAML only after the create succeeds.

### Example: Secret View

```text
HTTP Basic Auth Secret
Name: source-creds
Type: kubernetes.io/basic-auth

  username: admin
  password: <hidden>

[u Update] [Esc Back]
```

The view pane is generated from the create descriptor and current inventory. ConfigMap data is viewable by default. Secret data is hidden unless the descriptor says the specific field is non-sensitive.

### Example: Secret Update

```text
Update HTTP Basic Auth Secret

Secret name:  source-creds
Username:     admin
Password:     <leave unchanged>
Confirm:      <leave unchanged>

Updates type kubernetes.io/basic-auth with stringData.username and stringData.password.

[Update] [Cancel]
```

When a sensitive key is present, leaving the field blank preserves the current value. When a sensitive key is missing, the field becomes required and the hint changes from `<leave unchanged>` to `<required>`.

### Example: HTTP Basic Auth Secret Form

```text
Create HTTP Basic Auth Secret

Secret name:  source-creds
Username:     admin
Password:     ********
Confirm:      ********

Creates type kubernetes.io/basic-auth with stringData.username and stringData.password.

[Create] [Cancel]
```

Validation:

- secret name must match Kubernetes DNS naming rules;
- username and password must be non-empty;
- if the Secret exists and is not managed by the workflow CLI, the user must explicitly choose overwrite or choose another name;
- the resulting resource is listed as `matching` immediately.

### Example: TLS Secret Form

```text
Create TLS Secret

Secret name:        proxy-public-tls
Certificate PEM:    <multiline editor>
Private key PEM:    <multiline hidden editor>
Certificate chain:  optional

Creates type kubernetes.io/tls with tls.crt and tls.key.

[Create] [Validate] [Cancel]
```

This form is reused for:

- proxy server TLS (`proxyConfig.tls.mode=existingSecret.secretName`);
- HTTP mTLS client certificates (`authConfig.mtls.clientSecretName`).

Validation:

- `tls.crt` parses as one or more PEM certificates;
- `tls.key` parses as a private key;
- when local crypto support is available, the leaf certificate public key matches the private key;
- the Secret type is `kubernetes.io/tls` for resources created by manage.

### Example: Kafka SCRAM Secret Form

```text
Create Kafka SCRAM Password Secret

Secret name:      external-kafka-user
Kafka username:   migration-app
Password:         ********
Confirm:          ********

Creates an Opaque Secret with stringData.password.
Also sets kafkaUserName when the current config path supports it.

[Create] [Cancel]
```

The runtime reads only the `password` key from the Secret. The username remains a normal YAML field (`kafkaUserName`) because the schema already models it separately.

### Example: Kafka CA Secret Form

```text
Create Kafka CA Secret

Secret name:   external-kafka-ca
CA PEM:        <multiline editor>

Creates an Opaque Secret with stringData.ca.crt.

[Create] [Validate] [Cancel]
```

The workflow mounts the Secret and expects `ca.crt` at the mount root.

### Example: Single-File ConfigMap Form

```text
Create ConfigMap File

ConfigMap name:   proxy-client-ca
Key:              ca.crt
Contents:         <multiline editor>

Purpose: Trusted client CA certificate

[Create] [Validate] [Cancel]
```

This form is specialized by purpose:

| Purpose | Default key | Content validation |
| --- | --- | --- |
| Trusted client CA | `ca.crt` | PEM certificate chain |
| Log4j config | `log4j2.properties` | non-empty text, warn if it does not look like properties |
| JavaScript transform | `transform.js` | non-empty text, optional `node --check` when available |
| Python transform | `transform.py` | non-empty text, optional `python -m py_compile` when available |
| Transform context value | `<context-key>.json` or `<context-key>.txt` | non-empty text; JSON validation when the user selects JSON |

For `FILE_REF` fields, creating the ConfigMap also sets the pair:

```yaml
javascriptFile:
  configMap: my-transform
  path: transform.js
```

For scalar ConfigMap-name fields, creating the ConfigMap sets only the name:

```yaml
loggingConfigurationOverrideConfigMap: my-log4j
```

### ConfigMap Directory Form

```text
Create Transform Context Directory ConfigMap

ConfigMap name:   transform-context

Files
  mappings.json        JSON
  feature-flags.json   JSON
  tenant.txt           text

[Add File] [Create] [Cancel]
```

This is used for `context.valueDirectories[]` when the source is a ConfigMap. Each key becomes one immediate file in the mounted directory. Nested keys are not supported for ConfigMap-backed directories.

### Example: Image File Source Editor

```text
Transform File From Image

Image:        123456789012.dkr.ecr.us-east-1.amazonaws.com/transforms@sha256:...
Pull policy:  IfNotPresent
Path:         request/transform.js

Status: not checked

[Validate Pull + Path] [Apply] [Cancel]
```

Validation results update in place:

```text
Status: ok - image is pullable and request/transform.js exists
```

or:

```text
Status: error - image pulled, but request/transform.js was not found
```

The user may apply without image validation while editing, but submit preflight blocks when image validation is enabled and fails.

### Example: cert-manager Issuer Picker

```text
Select cert-manager Issuer

  matching  ClusterIssuer/letsencrypt-prod   Ready
  matching  ClusterIssuer/private-ca         Ready
  matching  Issuer/namespace-ca              Ready

[Enter Select] [m Manual] [Esc Cancel]
```

This picker is used only when `proxyConfig.tls.mode` is `certManager`. Manage does not create Issuers in this design because issuer setup is environment-specific and usually controlled by platform teams.

## Schema-Derived Reference Coverage

The reference inventory should be derived from schema metadata, not maintained by hand in Python. The source of truth is:

1. `externalRef` hints on fields or use sites;
2. the selected schema shape, including union variants and `FILE_REF` variants;
3. the declarative create descriptor, when manage can create the referenced resource;
4. named validators for content checks that cannot be expressed by JSON Schema alone.

Python should not contain a table of YAML paths. It notices `externalRef` in the edit DTO, asks the inventory service for matching Kubernetes resources, renders the picker/form from the descriptor, creates the described resource if requested, and sends the described edit operation back to TS.

The tables below are a schema coverage checklist for review. They should either be generated from the schema during tests or checked by a test that walks the exported JSON Schema and fails when a new `externalRef` is undocumented.

### Cluster HTTP Auth

| YAML path | Resource | Expected format | Picker | Create flow |
| --- | --- | --- | --- | --- |
| `sourceClusters.<source>.authConfig.basic.secretName` | Secret | `kubernetes.io/basic-auth` or `Opaque`; keys `username`, `password` | List matching Secrets with both keys | HTTP Basic Auth Secret |
| `targetClusters.<target>.authConfig.basic.secretName` | Secret | `kubernetes.io/basic-auth` or `Opaque`; keys `username`, `password` | List matching Secrets with both keys | HTTP Basic Auth Secret |
| `sourceClusters.<source>.authConfig.mtls.clientSecretName` | Secret | `kubernetes.io/tls`; keys `tls.crt`, `tls.key` | List TLS Secrets | TLS Secret |
| `targetClusters.<target>.authConfig.mtls.clientSecretName` | Secret | `kubernetes.io/tls`; keys `tls.crt`, `tls.key` | List TLS Secrets | TLS Secret |
| `sourceClusters.<source>.authConfig.mtls.caCert` | Scalar | PEM CA bundle or existing container path | No K8s picker in current schema | Paste PEM or type path |
| `targetClusters.<target>.authConfig.mtls.caCert` | Scalar | PEM CA bundle or existing container path | No K8s picker in current schema | Paste PEM or type path |

`authConfig.mtls.caCert` is the rough spot in the current schema. It overloads inline PEM and path strings, so manage cannot safely list ConfigMaps or Secrets for it. A later schema cleanup should split it into `caCertPem`, `caCertFile`, or `caSecretName`.

### Kafka Cluster Auth

| YAML path | Resource | Expected format | Picker | Create flow |
| --- | --- | --- | --- | --- |
| `kafkaClusterConfiguration.<kafka>.existing.auth.secretName` | Secret | key `password`; type `Opaque` or any Secret with the key | List Secrets with `password` | Kafka SCRAM Password Secret |
| `kafkaClusterConfiguration.<kafka>.existing.auth.caSecretName` | Secret | key `ca.crt`; PEM certificate chain | List Secrets with `ca.crt` | Kafka CA Secret |
| `kafkaClusterConfiguration.<kafka>.existing.auth.kafkaUserName` | Scalar | Kubernetes-name-like principal | Not external | Text field |

The schema keeps Kafka username separate from the Secret. The picker should offer to set `kafkaUserName` from the create form, but it should not require a `username` key in the Secret because the workflow does not read one.

### Capture Proxy TLS

| YAML path | Resource | Expected format | Picker | Create flow |
| --- | --- | --- | --- | --- |
| `traffic.proxies.<proxy>.proxyConfig.tls.secretName` when mode is `existingSecret` | Secret | `kubernetes.io/tls`; keys `tls.crt`, `tls.key` | List TLS Secrets | TLS Secret |
| `traffic.proxies.<proxy>.proxyConfig.tls.issuerRef` when mode is `certManager` | Issuer or ClusterIssuer | cert-manager issuer resource | List readable issuers | Manual name only |
| `traffic.proxies.<proxy>.proxyConfig.tls.clientAuth.trustedClientCaFile.configMap` + `path` | ConfigMap key | key such as `ca.crt` or `ca.pem`; PEM certificate chain | List ConfigMaps with matching PEM-like keys | Single-file ConfigMap |
| `traffic.proxies.<proxy>.proxyConfig.tls.clientAuth.trustedClientCaFile.image` + `path` | OCI image file | pullable image; path exists; PEM certificate chain if readable | Manual image editor | None |
| `traffic.proxies.<proxy>.proxyConfig.tls.clientAuth.trustedClientCaPem` | Scalar | inline PEM certificate chain | No K8s picker | Paste PEM |

`trustedClientCaFile` remains a `FILE_REF`, so it supports ConfigMaps and images, not Secrets. Private key material should stay in TLS Secrets; public trust roots can be ConfigMaps or images.

### Logging Overrides

| YAML path | Resource | Expected format | Picker | Create flow |
| --- | --- | --- | --- | --- |
| `traffic.proxies.<proxy>.proxyConfig.loggingConfigurationOverrideConfigMap` | ConfigMap | single key, recommended `log4j2.properties` | List ConfigMaps with one key or `log4j2.properties` | Single-file ConfigMap |
| `traffic.replayers.<replayer>.replayerConfig.loggingConfigurationOverrideConfigMap` | ConfigMap | single key, recommended `log4j2.properties` | Same | Single-file ConfigMap |
| `sourceClusters.<source>.snapshotInfo.snapshots.<snapshot>.config.createSnapshotConfig.loggingConfigurationOverrideConfigMap` | ConfigMap | single key, recommended `log4j2.properties` | Same | Single-file ConfigMap |
| `snapshotMigrationConfigs[<i>].perSnapshotConfig.<snapshot>[<j>].metadataMigrationConfig.loggingConfigurationOverrideConfigMap` | ConfigMap | single key, recommended `log4j2.properties` | Same | Single-file ConfigMap |
| `snapshotMigrationConfigs[<i>].perSnapshotConfig.<snapshot>[<j>].documentBackfillConfig.loggingConfigurationOverrideConfigMap` | ConfigMap | single key, recommended `log4j2.properties` | Same | Single-file ConfigMap |

The runtime description says the ConfigMap should have a single key whose value is Log4j2 properties content. The picker treats exactly one key as `matching`. It treats `log4j2.properties` plus extra keys as `warn` so users understand why it is not the preferred shape.

### Transform Entry Points and Context

The following pipeline fields can appear in four places:

- `traffic.replayers.<replayer>.replayerConfig.requestTransforms[]`
- `traffic.replayers.<replayer>.replayerConfig.tupleTransforms[]`
- `snapshotMigrationConfigs[<i>].perSnapshotConfig.<snapshot>[<j>].metadataMigrationConfig.metadataTransforms[]`
- `snapshotMigrationConfigs[<i>].perSnapshotConfig.<snapshot>[<j>].documentBackfillConfig.documentTransforms[]`

Within each transform item:

| Field shape | Resource | Expected format | Picker | Create flow |
| --- | --- | --- | --- | --- |
| `entryPoint.javascriptFile.configMap` + `path` | ConfigMap key | key ending `.js` or `.mjs`; non-empty JavaScript | List ConfigMaps with JS-like keys | Single-file ConfigMap |
| `entryPoint.javascriptFile.image` + `path` | OCI image file | pullable image; path exists; JS-like path | Manual image editor | None |
| `entryPoint.pythonFile.configMap` + `path` | ConfigMap key | key ending `.py`; non-empty Python | List ConfigMaps with Python-like keys | Single-file ConfigMap |
| `entryPoint.pythonFile.image` + `path` | OCI image file | pullable image; path exists; Python-like path | Manual image editor | None |
| `context.values.<key>.fromFile.configMap` + `path` | ConfigMap key | any key; optional JSON/text validator | List ConfigMaps and keys | Single-file ConfigMap |
| `context.values.<key>.fromFile.image` + `path` | OCI image file | pullable image; path exists | Manual image editor | None |
| `context.valueDirectories[].configMap` | ConfigMap directory | ConfigMap keys become immediate files | List ConfigMaps with keys | ConfigMap Directory |
| `context.valueDirectories[].image` + optional `path` | OCI image directory | pullable image; directory exists | Manual image editor | None |

Inline transform fields (`entryPoint.javascript`, `entryPoint.python`, `context.values.<key>.value`) are not external references and continue using scalar or structured editors.

### Expert Raw File Paths

These fields mention files but are not currently workflow-wired external references:

- `traffic.replayers.<replayer>.replayerConfig.kafkaTrafficPropertyFile`
- `traffic.replayers.<replayer>.replayerConfig.transformerConfigFile`
- `traffic.replayers.<replayer>.replayerConfig.tupleTransformerConfigFile`
- `snapshotMigrationConfigs[].perSnapshotConfig.*[].metadataMigrationConfig.transformerConfigFile`
- `snapshotMigrationConfigs[].perSnapshotConfig.*[].documentBackfillConfig.docTransformerConfigFile`

They are expert raw container paths. Manage should not list ConfigMaps, Secrets, or images for them until the schema converts those fields to `FILE_REF` or another explicit mounted-file type.

## Validation Rules by Resource Shape

| Shape | Required checks | Warning checks |
| --- | --- | --- |
| HTTP Basic Secret | Secret exists; keys `username` and `password`; values non-empty when created by manage | Secret type is neither `kubernetes.io/basic-auth` nor `Opaque` |
| TLS Secret | Secret exists; keys `tls.crt`, `tls.key`; PEM parse succeeds when values are available from a manage-created form | certificate/key mismatch; expired certificate |
| Kafka SCRAM Secret | Secret exists; key `password` | empty decoded password if RBAC permits read |
| Kafka CA Secret | Secret exists; key `ca.crt` | PEM parse fails if RBAC permits read |
| Log4j ConfigMap | ConfigMap exists; one data key or key `log4j2.properties`; content non-empty for created resources | multiple keys; content does not look like properties |
| CA ConfigMap | ConfigMap exists; selected key exists; PEM certificate chain for created resources | key name does not end `.crt` or `.pem` |
| Transform ConfigMap file | ConfigMap exists; selected key exists | extension does not match selected language; syntax probe fails |
| Transform ConfigMap directory | ConfigMap exists; has at least one data key | contains keys that look like nested paths unsupported by ConfigMap file refs |
| Image file | reference set; path set; image pull succeeds at requested validation level; path is a file | mutable tag without digest |
| Image directory | reference set; image pull succeeds at requested validation level; path is a directory | mutable tag without digest |
| cert-manager issuer | Issuer/ClusterIssuer exists when CRDs are readable | issuer is not Ready |

Validation reports use the same severity priority as the edit tree:

1. `error` for unusable existing references.
2. `required` for missing referenced resources or missing keys.
3. `warning` for questionable but potentially usable shapes.

## Save and Submit Flow

On save from manage:

1. TS applies the edit operation and validates schema/refinement rules.
2. Python optionally refreshes external reference inventory for changed reference paths.
3. External diagnostics are applied to the edit tree and resource view.
4. Pending YAML is saved even when external references are incomplete, matching the current incomplete-config workflow.

On submit:

1. Save pending YAML.
2. Run strict config-processor validation/projection.
3. Run the shared external-resource preflight with blocking Secret/ConfigMap checks.
4. Run image checks at the configured submit level, defaulting to `pull-and-path` for image-backed `FILE_REF`s.
5. Show all schema, policy, and external-reference failures in one confirmation/error screen.
6. Submit only if blocking checks pass or the user explicitly chooses an allowed skip mode.

## Implementation Plan

1. Add `externalRef` metadata support to schema field metadata and JSON Schema export.
2. Annotate the current Secret, ConfigMap, image, and issuer fields listed in this document.
3. Add a TS reference-plan helper using strict first and loose fallback, parallel to resource projection.
4. Add Python `ExternalResourceInventory` and `ExternalResourceValidator` services for K8s object listing and shape checks.
5. Call the shared external-resource preflight from `configure`, `submit`, and manage.
6. Add generic Textual renderers for reference pickers, declarative create/update forms, descriptor-driven view panes, image editors, and confirmation flows.
7. Add descriptor fixtures for HTTP Basic Auth Secrets, TLS Secrets, Kafka SCRAM password Secrets, Kafka CA Secrets, single-file ConfigMaps, and ConfigMap directories.
8. Wire create/update forms to immediate Kubernetes writes, inventory refresh, and then the normal TS edit operation.
9. Add cancellable image validation from manage using the same validator backend as submit preflight.
10. Add schema coverage tests that walk exported `x-external-ref` metadata and verify each reference has the selection, validation, and create descriptors expected by its purpose.
11. Add renderer tests that feed descriptor fixtures into Python and assert the rendered targets, actions, and emitted generic edit operations.
12. Replace the existing basic-auth-only secret scrape path with the general external-reference validation path once equivalent HTTP Basic coverage is in place.

## Maintainability Expectations

Adding a new external-reference field should require:

1. Add the field or use-site `externalRef` hint in `userSchemas.ts`.
2. Add a content validator only if the existing generic validators do not fit.
3. Add or reuse a declarative create descriptor if manage should create that resource shape.
4. Add a TS reference-plan test and, when a descriptor shape is new, one Python renderer/filter test.

Python should not need to learn the new YAML path by hand. It should render the field from the edit DTO, use `externalRef` to select the generic picker/create/editor renderer, and send normal edit operations back to TS. If a new reference uses existing descriptor shapes and validation IDs, adding it should be a schema-only change plus schema coverage tests.
