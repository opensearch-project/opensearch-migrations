import {
  useEffect,
  useMemo,
  useState,
} from "react";

import {
  type EditNode,
} from "@opensearch-migrations/config-edit-core";
import {
  diagnoseConfigurationEnvironment,
  type ConfigEnvironmentDiagnostics,
} from "../../api/client";
import type { BrowserConfigDraft } from "./browserDraft";


export type EnvironmentDiagnosticLifecycle =
  | "not-checked"
  | "checking"
  | "valid"
  | "warning"
  | "error"
  | "stale";


export interface EnvironmentDiagnosticState {
  diagnostics: ConfigEnvironmentDiagnostics["diagnostics"];
  nonce: string | null;
  lifecycle: EnvironmentDiagnosticLifecycle;
}


export type EnvironmentReferenceCategory =
  | "secret"
  | "config-map"
  | "image"
  | "issuer"
  | "kubernetes-resource";


export interface EnvironmentReference {
  id: string;
  category: EnvironmentReferenceCategory;
  displayName: string;
  name: string;
  path: string[];
}


export interface EnvironmentReferenceGroup {
  id: string;
  label: string;
  references: EnvironmentReference[];
  diagnostics: ConfigEnvironmentDiagnostics["diagnostics"];
  status: EnvironmentDiagnosticLifecycle;
}


const NOT_CHECKED: EnvironmentDiagnosticState = {
  diagnostics: [],
  nonce: null,
  lifecycle: "not-checked",
};


function stableValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stableValue);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, nested]) => [key, stableValue(nested)]),
  );
}


function externalReferenceSignature(nodes: EditNode[]): string {
  const references: unknown[] = [];
  const visit = (node: EditNode) => {
    if (node.externalRef && Object.keys(node.externalRef).length > 0) {
      references.push({
        externalRef: stableValue(node.externalRef),
        path: node.path,
        value: stableValue(node.value),
      });
    }
    if (Array.isArray(node.children)) node.children.forEach(visit);
  };
  nodes.forEach(visit);
  return references.length > 0 ? JSON.stringify(references) : "";
}


function pathsOverlap(left: string[], right: string[]): boolean {
  return left.every((part, index) => right[index] === part)
    || right.every((part, index) => left[index] === part);
}


function referenceCategory(node: EditNode): EnvironmentReferenceCategory {
  const externalRef = node.externalRef;
  if (externalRef?.kind === "image") return "image";
  if (externalRef?.kind === "secret") return "secret";
  if (externalRef?.kind === "configMap") return "config-map";
  if (externalRef?.kind === "certManagerIssuer") return "issuer";
  const kinds = externalRef?.k8s?.resourceTypes?.map(({ kind }) => kind) ?? [];
  if (kinds.includes("Secret")) return "secret";
  if (kinds.includes("ConfigMap")) return "config-map";
  if (kinds.some((kind) => kind === "Issuer" || kind === "ClusterIssuer")) {
    return "issuer";
  }
  return "kubernetes-resource";
}


function referenceName(node: EditNode): string {
  if (typeof node.value === "string") return node.value.trim();
  if (!node.value || typeof node.value !== "object") return "";
  const value = node.value as Record<string, unknown>;
  const selection = node.externalRef?.selection;
  const nameField = selection && "nameField" in selection
    ? selection.nameField ?? "name"
    : "name";
  const name = value[nameField];
  return typeof name === "string" ? name.trim() : "";
}


function categoryLabel(category: EnvironmentReferenceCategory): string {
  switch (category) {
    case "secret": return "Kubernetes Secrets";
    case "config-map": return "Kubernetes ConfigMaps";
    case "image": return "Transform Images";
    case "issuer": return "Certificate Issuers";
    case "kubernetes-resource": return "Kubernetes Resources";
  }
}


function groupStatus(
  lifecycle: EnvironmentDiagnosticLifecycle,
  diagnostics: ConfigEnvironmentDiagnostics["diagnostics"],
): EnvironmentDiagnosticLifecycle {
  if (
    lifecycle === "not-checked"
    || lifecycle === "checking"
    || lifecycle === "stale"
  ) {
    return lifecycle;
  }
  if (diagnostics.some(({ severity }) => severity === "error")) return "error";
  if (diagnostics.some(({ severity }) => severity === "warning")) {
    return "warning";
  }
  return "valid";
}


export function environmentReferenceGroups(
  nodes: EditNode[],
  scopePath: string[] | null,
  diagnostics: ConfigEnvironmentDiagnostics["diagnostics"],
  lifecycle: EnvironmentDiagnosticLifecycle,
): EnvironmentReferenceGroup[] {
  const references = new Map<string, EnvironmentReference>();
  const visit = (node: EditNode) => {
    const name = referenceName(node);
    if (
      node.externalRef
      && name
      && (!scopePath || pathsOverlap(scopePath, node.path))
    ) {
      const category = referenceCategory(node);
      const id = `${category}:${name}`;
      if (!references.has(id)) {
        references.set(id, {
          id,
          category,
          displayName: node.externalRef.displayName || node.label,
          name,
          path: node.path,
        });
      }
    }
    node.children?.forEach(visit);
  };
  nodes.forEach(visit);

  const byCategory = new Map<
    EnvironmentReferenceCategory,
    EnvironmentReference[]
  >();
  references.forEach((reference) => {
    byCategory.set(reference.category, [
      ...(byCategory.get(reference.category) ?? []),
      reference,
    ]);
  });
  return [...byCategory.entries()]
    .map(([category, categoryReferences]) => {
      const categoryDiagnostics = diagnostics.filter((diagnostic) => (
        diagnostic.path.length === 0
        || categoryReferences.some((reference) => (
          pathsOverlap(reference.path, diagnostic.path)
        ))
      ));
      return {
        id: `environment:${category}`,
        label: categoryLabel(category),
        references: categoryReferences.sort(
          (left, right) => left.name.localeCompare(right.name),
        ),
        diagnostics: categoryDiagnostics,
        status: groupStatus(lifecycle, categoryDiagnostics),
      };
    })
    .sort((left, right) => left.label.localeCompare(right.label));
}


function hashSignature(signature: string): string {
  let first = 0x811c9dc5;
  let second = 0x9e3779b9;
  for (const character of signature) {
    const code = character.codePointAt(0) ?? 0;
    first = Math.imul(first ^ code, 0x01000193);
    second = Math.imul(second ^ code, 0x85ebca6b);
  }
  return [
    "external",
    signature.length.toString(36),
    (first >>> 0).toString(36),
    (second >>> 0).toString(36),
  ].join(":");
}


export function environmentDiagnosticNonce(
  nodes: EditNode[],
): string | null {
  const signature = externalReferenceSignature(nodes);
  return signature ? hashSignature(signature) : null;
}


export function diagnosticsForScope(
  diagnostics: ConfigEnvironmentDiagnostics["diagnostics"],
  scopePath: string[] | null,
): ConfigEnvironmentDiagnostics["diagnostics"] {
  if (!scopePath || scopePath.length === 0) return diagnostics;
  return diagnostics.filter(
    (diagnostic) => diagnostic.path.length === 0
      || pathsOverlap(scopePath, diagnostic.path),
  );
}


export function useEnvironmentDiagnostics(
  draft: BrowserConfigDraft | undefined,
  debounceMs = 350,
): EnvironmentDiagnosticState {
  const nodes = draft?.editState.nodes;
  const rawDocument = draft?.rawDocument;
  const nonce = useMemo(
    () => nodes
      ? environmentDiagnosticNonce(nodes)
      : null,
    [nodes],
  );
  const [state, setState] = useState<EnvironmentDiagnosticState>(NOT_CHECKED);
  const [request, setRequest] = useState<{
    nonce: string;
    rawYaml: string;
  } | null>(null);

  useEffect(() => {
    setRequest((current) => {
      if (!rawDocument || !nonce) return null;
      if (current?.nonce === nonce) return current;
      return {
        nonce,
        rawYaml: rawDocument,
      };
    });
  }, [nonce, rawDocument]);

  useEffect(() => {
    if (!request) {
      setState(NOT_CHECKED);
      return;
    }
    const { nonce: requestNonce, rawYaml } = request;
    const controller = new AbortController();
    setState((current) => ({
      diagnostics: current.diagnostics,
      nonce: requestNonce,
      lifecycle: current.lifecycle === "not-checked"
        ? "not-checked"
        : "stale",
    }));
    const timer = globalThis.setTimeout(() => {
      setState((current) => ({
        diagnostics: current.nonce === requestNonce
          ? current.diagnostics
          : [],
        nonce: requestNonce,
        lifecycle: "checking",
      }));
      void diagnoseConfigurationEnvironment(
        rawYaml,
        requestNonce,
        controller.signal,
      ).then((result) => {
        if (result.draftNonce !== requestNonce) return;
        setState({
          diagnostics: result.diagnostics,
          nonce: requestNonce,
          lifecycle: result.status,
        });
      }).catch((error: unknown) => {
        if (
          controller.signal.aborted
          || (error instanceof DOMException && error.name === "AbortError")
        ) {
          return;
        }
        setState({
          diagnostics: [{
            severity: "warning",
            message: error instanceof Error ? error.message : String(error),
            path: [],
          }],
          nonce: requestNonce,
          lifecycle: "warning",
        });
      });
    }, debounceMs);
    return () => {
      globalThis.clearTimeout(timer);
      controller.abort();
    };
  }, [debounceMs, request]);

  return state;
}
