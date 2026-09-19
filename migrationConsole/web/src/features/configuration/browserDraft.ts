import {
  annotateDraftChanges,
  applyEditOperationToObject,
  projectConfigYaml,
  type EditOperation,
  type EditStateV1,
  type JsonSchema,
} from "@opensearch-migrations/config-edit-core";
import { parse } from "yaml";

import type {
  ConfigurationDocument,
  ManageSnapshot,
} from "../../api/client";


export interface BrowserConfigDraft {
  baseRevision: string;
  draftRevision: string;
  dirty: boolean;
  editState: EditStateV1;
  navigation: ManageSnapshot | null;
  rawYaml?: string;
  notices: string[];
  baseStale: boolean;
  baseEditState: EditStateV1;
  config: unknown;
  persistedRevision: string;
  rawDocument: string;
  remotePersistedRevision?: string;
  savedRawDocument: string;
  unifiedSchema?: JsonSchema;
}


export const BROWSER_CONFIG_DRAFT_QUERY_KEY = ["browser-config-draft"] as const;


let localRevision = 0;


function nextRevision(persistedRevision: string): string {
  localRevision += 1;
  return `browser:${persistedRevision}:${localRevision}`;
}


function parseYaml(rawYaml: string): unknown {
  return parse(rawYaml) as unknown;
}


function projectedDraft(
  document: ConfigurationDocument,
  navigation?: ManageSnapshot | null,
  unifiedSchema?: JsonSchema,
): BrowserConfigDraft {
  const projection = projectConfigYaml(document.rawYaml, { unifiedSchema });
  const editState = projection.editState;
  return {
    baseRevision: document.persistedRevision,
    draftRevision: nextRevision(document.persistedRevision),
    dirty: false,
    editState,
    navigation: navigation ?? null,
    rawYaml: projection.editState.provenance.mode === "raw"
      ? document.rawYaml
      : undefined,
    notices: [],
    baseStale: false,
    baseEditState: structuredClone(editState),
    config: projection.config,
    persistedRevision: document.persistedRevision,
    rawDocument: document.rawYaml,
    savedRawDocument: document.rawYaml,
    unifiedSchema,
  };
}


export function createBrowserConfigDraft(
  document: ConfigurationDocument,
  navigation?: ManageSnapshot | null,
  unifiedSchema?: JsonSchema,
): BrowserConfigDraft {
  return projectedDraft(document, navigation, unifiedSchema);
}


export function applyBrowserEditOperation(
  draft: BrowserConfigDraft,
  operation: EditOperation,
): BrowserConfigDraft {
  if (draft.config === null) {
    throw new Error(
      "Repair the YAML before applying structured configuration changes.",
    );
  }
  const result = applyEditOperationToObject(
    draft.config,
    operation,
    { unifiedSchema: draft.unifiedSchema },
  );
  const config = result.yaml.trim() === "" ? {} : parseYaml(result.yaml);
  const editState = annotateDraftChanges(
    result.editState,
    draft.baseEditState,
  );
  return {
    ...draft,
    draftRevision: nextRevision(draft.persistedRevision),
    dirty: result.yaml !== draft.savedRawDocument,
    editState,
    rawYaml: undefined,
    notices: [],
    config,
    rawDocument: result.yaml,
  };
}


export function replaceBrowserConfigYaml(
  draft: BrowserConfigDraft,
  rawYaml: string,
): BrowserConfigDraft {
  const projection = projectConfigYaml(
    rawYaml,
    { unifiedSchema: draft.unifiedSchema },
  );
  const projectedEditState = projection.editState.provenance.mode === "raw"
    ? projection.editState
    : annotateDraftChanges(
      projection.editState,
      draft.baseEditState,
    );
  return {
    ...draft,
    draftRevision: nextRevision(draft.persistedRevision),
    dirty: rawYaml !== draft.savedRawDocument,
    editState: projectedEditState,
    rawYaml: projection.editState.provenance.mode === "raw"
      ? rawYaml
      : undefined,
    notices: [],
    config: projection.config,
    rawDocument: rawYaml,
  };
}


export function savedBrowserConfigDraft(
  document: ConfigurationDocument,
  previous: BrowserConfigDraft,
  navigation?: ManageSnapshot | null,
): BrowserConfigDraft {
  return projectedDraft(
    document,
    navigation ?? previous.navigation,
    previous.unifiedSchema,
  );
}


export function acknowledgeSavedBrowserConfigDraft(
  document: ConfigurationDocument,
  savedSnapshot: BrowserConfigDraft,
  current: BrowserConfigDraft,
): BrowserConfigDraft {
  if (current.draftRevision === savedSnapshot.draftRevision) {
    return savedBrowserConfigDraft(document, current);
  }
  const saved = projectedDraft(document, current.navigation);
  return replaceBrowserConfigYaml(saved, current.rawDocument);
}


export function revertedBrowserConfigDraft(
  draft: BrowserConfigDraft,
): BrowserConfigDraft {
  return projectedDraft({
    modelVersion: "1",
    persistedRevision: draft.persistedRevision,
    rawYaml: draft.savedRawDocument,
  }, draft.navigation, draft.unifiedSchema);
}


export function withBrowserDraftNavigation(
  draft: BrowserConfigDraft,
  navigation: ManageSnapshot | null | undefined,
): BrowserConfigDraft {
  return navigation === undefined || navigation === draft.navigation
    ? draft
    : { ...draft, navigation };
}


export function markBrowserConfigDraftStale(
  draft: BrowserConfigDraft,
  remotePersistedRevision: string,
): BrowserConfigDraft {
  if (
    draft.persistedRevision === remotePersistedRevision
    || (
      draft.baseStale
      && draft.remotePersistedRevision === remotePersistedRevision
    )
  ) {
    return draft;
  }
  return {
    ...draft,
    baseStale: true,
    remotePersistedRevision,
  };
}
