import type {
    EditDiagnostic,
    EditNode,
    EditStateV1,
} from "./schemaEditModel";
import {
    buildConfigDependencyGraph,
    downstreamConfigReferences,
} from "./configDependencies";


const EDIT_TARGET_PREFIX = "edit:";
const SNAPSHOT_SECTION_ID = "section:Snapshot Migration";
const SNAPSHOT_BASE_GROUP_ID = "group:Snapshot Migration:Backfill";
const SNAPSHOT_DYNAMIC_GROUP_PREFIX = "snapshot-navigation:";

const NAVIGATION_EDIT_TARGETS: Readonly<Record<string, string>> = {
    "section:Workflow Configuration": "edit:workflowConfiguration",
    "section:Sources": "edit:sourceClusters",
    "section:Targets": "edit:targetClusters",
    "section:Snapshot Migration": "edit:snapshotMigration",
    "section:Live Traffic Migration": "edit:traffic",
    "group:Sources:Sources": "edit:sourceClusters",
    "group:Targets:Targets": "edit:targetClusters",
    "group:Snapshot Migration:Backfill": "edit:snapshotMigrationConfigs",
    "group:Live Traffic Migration:Capture": "edit:traffic.proxies",
    "group:Live Traffic Migration:Buffer": "edit:traffic.buffer",
    "group:Live Traffic Migration:Buffer:Kafka Clusters": "edit:traffic.kafkaClusters",
    "group:Live Traffic Migration:Buffer:Previously Captured Traffic": "edit:traffic.s3Sources",
    "group:Live Traffic Migration:Replay": "edit:traffic.replayers",
};

const NAVIGATION_STATUS_RANK: Readonly<Record<string, number>> = {
    ok: 0,
    unknown: 1,
    changed: 2,
    removed: 3,
    warning: 4,
    required: 5,
    blocked: 6,
    error: 7,
};


export interface ResourceGraphDiagnostic {
    severity: string;
    message: string;
    path?: string[];
    source?: string | null;
    code?: string | null;
    title?: string | null;
    remedy?: string | null;
    technicalDetail?: string | null;
}


export interface ResourceGraphEditCapability {
    kind: "edit";
    editTargetId: string;
    label?: string | null;
    disabledReason?: string | null;
}


export interface ResourceGraphRelationship {
    kind: string;
    direction: string;
    targetId?: string | null;
    targetName: string;
    targetPlural?: string | null;
    targetPhase?: string | null;
    targetStatus: string;
}


export interface ResourceGraphNode {
    id: string;
    revision: string;
    parentId: string | null;
    childIds?: string[];
    kind: string;
    label: string;
    description: string | null;
    status: string;
    phase: string | null;
    valueSummary: string | null;
    activityAt?: string | null;
    diagnostics?: ResourceGraphDiagnostic[];
    capabilities?: Array<ResourceGraphEditCapability | {kind: string}>;
    details?: Array<{
        label: string;
        value: unknown;
        kind: string;
    }>;
    relationships?: ResourceGraphRelationship[];
    comparisons?: unknown[];
    resourcePlural: string | null;
    resourceName: string | null;
    resourceType: string | null;
    configPresence?: Record<string, boolean>;
    configState?: {
        validationErrors: number;
        validationWarnings: number;
        draftChangeCount: number;
    } | null;
    navigationKey?: string[];
}


export interface ResourceGraphSnapshot<
    TNode extends ResourceGraphNode = ResourceGraphNode,
> {
    formatVersion: 1;
    revision: string;
    rootIds: string[];
    nodes: Record<string, TNode>;
}


export interface ResourceGraphDraft {
    draftRevision: string;
    dirty: boolean;
    editState: EditStateV1;
    config?: unknown;
    repairYaml?: string | null;
}


export interface ConfigReference {
    fromTargetId: string;
    fromFieldPath: string[];
    toTargetId: string;
    reason: string;
}


export interface ConfigRemovalImpactEntry {
    path: string[];
    fieldPath: string[];
    reason: string;
    direct: boolean;
}


interface ResourcePlacement {
    collectionPath: string[];
    sectionId: string;
    sectionLabel: string;
    sectionOrder: number;
    groupId: string;
    groupLabel: string;
    groupOrder: number;
    parentGroupId?: string;
    parentGroupLabel?: string;
    parentGroupOrder?: number;
    resourcePlural: string;
    resourceType: string;
    identity: {
        kind: "named" | "indexed-config";
        prefix: string;
        suffix: string;
        firstIndex: number;
    };
}


interface DefinitionPlacement {
    collectionPath: string[];
    ownerTargetId: string;
    groupId: string;
    groupLabel: string;
    groupOrder: number;
    definitionType: string;
}


function editCapability(node: ResourceGraphNode): ResourceGraphEditCapability | undefined {
    return node.capabilities?.find(
        (capability): capability is ResourceGraphEditCapability =>
            capability.kind === "edit" && "editTargetId" in capability,
    );
}


function editTarget(node: ResourceGraphNode): string | undefined {
    return editCapability(node)?.editTargetId;
}


function withEditCapability<TNode extends ResourceGraphNode>(
    node: TNode,
    targetId: string | undefined,
): TNode {
    if (!targetId) return node;
    return {
        ...node,
        capabilities: [
            ...(node.capabilities ?? []).filter(
                (capability) => capability.kind !== "edit",
            ),
            {
                kind: "edit",
                editTargetId: targetId,
                label: `Edit ${node.label}`,
            },
        ],
    };
}


function withoutEditCapability<TNode extends ResourceGraphNode>(node: TNode): TNode {
    return {
        ...node,
        capabilities: (node.capabilities ?? []).filter(
            (capability) => capability.kind !== "edit",
        ),
    };
}


function nodeChildren(node: EditNode): EditNode[] {
    return node.children ?? [];
}


function editNodes(roots: EditNode[]): Map<string, EditNode> {
    const result = new Map<string, EditNode>();
    const visit = (nodes: EditNode[]) => {
        nodes.forEach((node) => {
            result.set(node.id, node);
            visit(nodeChildren(node));
        });
    };
    visit(roots);
    return result;
}


function resourcePlacement(node: EditNode): ResourcePlacement | null {
    const collection = node.inputHint?.resourceCollection;
    if (!collection) return null;
    const { navigation, resource } = collection;
    const identity = resource.identity;
    return {
        collectionPath: [...node.path],
        sectionId: navigation.sectionId,
        sectionLabel: navigation.sectionLabel,
        sectionOrder: navigation.sectionOrder,
        groupId: navigation.groupId,
        groupLabel: navigation.groupLabel,
        groupOrder: navigation.groupOrder,
        parentGroupId: navigation.parentGroupId,
        parentGroupLabel: navigation.parentGroupLabel,
        parentGroupOrder: navigation.parentGroupOrder,
        resourcePlural: resource.plural,
        resourceType: resource.typeLabel,
        identity: {
            kind: identity.kind,
            prefix: identity.prefix ?? "",
            suffix: identity.kind === "named" ? identity.suffix ?? "" : "",
            firstIndex: identity.kind === "indexed-config"
                ? identity.firstIndex
                : 0,
        },
    };
}


function resourcePlacements(roots: EditNode[]): ResourcePlacement[] {
    const result = new Map<string, ResourcePlacement>();
    const visit = (nodes: EditNode[]) => {
        nodes.forEach((node) => {
            const placement = resourcePlacement(node);
            if (placement) result.set(placement.collectionPath.join("."), placement);
            visit(nodeChildren(node));
        });
    };
    visit(roots);
    return [...result.values()];
}


function definitionPlacement(node: EditNode): DefinitionPlacement | null {
    const collection = node.inputHint?.definitionCollection;
    if (!collection) return null;
    const levels = collection.ownerAncestorLevels;
    if (levels <= 0 || levels >= node.path.length) return null;
    return {
        collectionPath: [...node.path],
        ownerTargetId: `${EDIT_TARGET_PREFIX}${node.path.slice(0, -levels).join(".")}`,
        groupId: collection.navigation.groupId ?? `definition-group:${node.id}`,
        groupLabel: collection.navigation.groupLabel,
        groupOrder: collection.navigation.groupOrder,
        definitionType: collection.definition.typeLabel,
    };
}


function definitionPlacements(roots: EditNode[]): DefinitionPlacement[] {
    const result = new Map<string, DefinitionPlacement>();
    const visit = (nodes: EditNode[]) => {
        nodes.forEach((node) => {
            const placement = definitionPlacement(node);
            if (placement) result.set(placement.collectionPath.join("."), placement);
            visit(nodeChildren(node));
        });
    };
    visit(roots);
    return [...result.values()];
}


function withoutWorkflowSteps<
    TNode extends ResourceGraphNode,
    TSnapshot extends ResourceGraphSnapshot<TNode>,
>(snapshot: TSnapshot): TSnapshot {
    const stepIds = new Set(
        Object.values(snapshot.nodes)
            .filter((node) => node.kind === "workflow-step")
            .map((node) => node.id),
    );
    if (stepIds.size === 0) return snapshot;
    return {
        ...snapshot,
        rootIds: snapshot.rootIds.filter((nodeId) => !stepIds.has(nodeId)),
        nodes: Object.fromEntries(
            Object.entries(snapshot.nodes).flatMap(([nodeId, node]) => (
                stepIds.has(nodeId)
                    ? []
                    : [[nodeId, {
                        ...node,
                        childIds: (node.childIds ?? []).filter(
                            (childId) => !stepIds.has(childId),
                        ),
                    }]]
            )),
        ) as Record<string, TNode>,
    };
}


function orderedIds(
    nodeIds: Iterable<string>,
    order: ReadonlyMap<string, number>,
): string[] {
    return [...nodeIds]
        .map((nodeId, index) => ({ nodeId, index }))
        .sort((left, right) => (
            (order.get(left.nodeId) ?? Number.MAX_SAFE_INTEGER)
            - (order.get(right.nodeId) ?? Number.MAX_SAFE_INTEGER)
            || left.index - right.index
        ))
        .map(({ nodeId }) => nodeId);
}


function appendOrderedChild<TNode extends ResourceGraphNode>(
    nodes: Record<string, TNode>,
    parentId: string,
    childId: string,
    revision: string,
    order: ReadonlyMap<string, number>,
): void {
    const parent = nodes[parentId];
    if (!parent || (parent.childIds ?? []).includes(childId)) return;
    nodes[parentId] = {
        ...parent,
        revision: `${parent.revision}:${revision}`,
        childIds: orderedIds([...(parent.childIds ?? []), childId], order),
    };
}


function emptyNavigationNode<TNode extends ResourceGraphNode>(
    id: string,
    revision: string,
    kind: string,
    label: string,
    parentId: string | null,
): TNode {
    return {
        id,
        revision: `${revision}:${id}`,
        parentId,
        childIds: [],
        kind,
        label,
        description: null,
        status: "ok",
        phase: null,
        valueSummary: null,
        diagnostics: [],
        capabilities: [],
        details: [],
        relationships: [],
        comparisons: [],
        resourcePlural: null,
        resourceName: null,
        resourceType: null,
        configPresence: {},
        configState: null,
    } as unknown as TNode;
}


function ensureNavigation<TNode extends ResourceGraphNode>(
    nodes: Record<string, TNode>,
    rootIds: string[],
    placements: ResourcePlacement[],
    revision: string,
): void {
    const sectionOrder = new Map(
        placements.map((placement) => [placement.sectionId, placement.sectionOrder]),
    );
    const groupOrder = new Map<string, number>();
    placements.forEach((placement) => {
        groupOrder.set(placement.groupId, placement.groupOrder);
        if (
            placement.parentGroupId
            && placement.parentGroupOrder !== undefined
        ) {
            groupOrder.set(placement.parentGroupId, placement.parentGroupOrder);
        }
    });

    placements.forEach((placement) => {
        let section = nodes[placement.sectionId] ?? emptyNavigationNode<TNode>(
            placement.sectionId,
            revision,
            "section",
            placement.sectionLabel,
            null,
        );
        section = withEditCapability(
            section,
            NAVIGATION_EDIT_TARGETS[section.id],
        );
        nodes[section.id] = section;
        if (!rootIds.includes(section.id)) rootIds.push(section.id);

        let parentId = section.id;
        if (placement.parentGroupId && placement.parentGroupLabel) {
            let parent = nodes[placement.parentGroupId]
                ?? emptyNavigationNode<TNode>(
                    placement.parentGroupId,
                    revision,
                    "group",
                    placement.parentGroupLabel,
                    section.id,
                );
            parent = withEditCapability(
                parent,
                NAVIGATION_EDIT_TARGETS[parent.id],
            );
            nodes[parent.id] = parent;
            appendOrderedChild(nodes, section.id, parent.id, revision, groupOrder);
            parentId = parent.id;
        }

        let group = nodes[placement.groupId] ?? emptyNavigationNode<TNode>(
            placement.groupId,
            revision,
            "group",
            placement.groupLabel,
            parentId,
        );
        group = withEditCapability(
            group,
            NAVIGATION_EDIT_TARGETS[group.id],
        );
        nodes[group.id] = group;
        appendOrderedChild(nodes, parentId, group.id, revision, groupOrder);
    });
    rootIds.splice(0, rootIds.length, ...orderedIds(rootIds, sectionOrder));
}


function validationIssueCounts(node: EditNode): [number, number] {
    const counts = node.statusCounts;
    let errors = (
        (counts?.errors ?? 0)
        + (counts?.required ?? 0)
        + (counts?.gated ?? 0)
        + (counts?.blocked ?? 0)
    );
    let warnings = counts?.warnings ?? 0;
    if (errors === 0 && warnings === 0) {
        if (["required", "error", "gated", "blocked"].includes(node.status ?? "")) {
            errors = 1;
        } else if (node.status === "warning") {
            warnings = 1;
        }
    }
    const childCounts = nodeChildren(node)
        .filter((child) => child.valueKind !== "command")
        .map(validationIssueCounts);
    errors = Math.max(
        errors,
        childCounts
            .filter(([childErrors]) => childErrors > 0)
            .reduce((sum, [childErrors, childWarnings]) => (
                sum + childErrors + childWarnings
            ), 0),
    );
    warnings = Math.max(
        warnings,
        childCounts
            .filter(([childErrors]) => childErrors === 0)
            .reduce((sum, [, childWarnings]) => sum + childWarnings, 0),
    );
    return [errors, warnings];
}


function configState(node: EditNode, dirty: boolean) {
    const [validationErrors, validationWarnings] = validationIssueCounts(node);
    return {
        validationErrors,
        validationWarnings,
        draftChangeCount: dirty ? node.draftChangeCount ?? (
            node.draftChange ? 1 : 0
        ) : 0,
    };
}


function graphDiagnostics(node: EditNode): ResourceGraphDiagnostic[] {
    return (node.diagnostics ?? []).map((diagnostic: EditDiagnostic) => ({
        severity: diagnostic.severity,
        message: diagnostic.message,
        path: diagnostic.path ? [...diagnostic.path] : [],
    }));
}


function isResourceTarget(
    targetId: string,
    placements: ResourcePlacement[],
): boolean {
    const path = targetId.startsWith(EDIT_TARGET_PREFIX)
        ? targetId.slice(EDIT_TARGET_PREFIX.length)
        : targetId;
    return placements.some((placement) => {
        const collectionPath = placement.collectionPath.join(".");
        return path === collectionPath || path.startsWith(`${collectionPath}.`);
    });
}


function resourceCollectionChanged(
    targetId: string,
    nodes: ReadonlyMap<string, EditNode>,
    placements: ResourcePlacement[],
): boolean {
    const path = targetId.startsWith(EDIT_TARGET_PREFIX)
        ? targetId.slice(EDIT_TARGET_PREFIX.length)
        : targetId;
    return placements.some((placement) => {
        const collectionPath = placement.collectionPath.join(".");
        if (!path.startsWith(`${collectionPath}.`)) return false;
        const collection = nodes.get(`${EDIT_TARGET_PREFIX}${collectionPath}`);
        return (collection?.draftChangeCount ?? 0) > 0;
    });
}


function snapshotMigrationKey(node: EditNode): string[] | null {
    if (
        node.path.length !== 2
        || node.path[0] !== "snapshotMigrationConfigs"
        || typeof node.value !== "object"
        || node.value === null
    ) {
        return null;
    }
    const value = node.value as Record<string, unknown>;
    const key = [
        value.fromSource,
        value.toTarget,
        value.fromSnapshot,
        value.slice,
    ].map((part) => typeof part === "string" ? part : "");
    return key.every(Boolean) ? key : null;
}


function snapshotMigrationTargets(
    nodes: ReadonlyMap<string, EditNode>,
): Map<string, string[]> {
    const result = new Map<string, string[]>();
    nodes.forEach((node, targetId) => {
        const key = snapshotMigrationKey(node);
        if (!key) return;
        const serialized = JSON.stringify(key);
        result.set(serialized, [...(result.get(serialized) ?? []), targetId]);
    });
    return result;
}


function configuredResourceTarget(
    node: ResourceGraphNode,
    nodesByTarget: ReadonlyMap<string, EditNode>,
    placements: ResourcePlacement[],
): string | undefined {
    for (const placement of placements) {
        if (placement.resourcePlural !== node.resourcePlural) continue;
        const collection = nodesByTarget.get(
            `${EDIT_TARGET_PREFIX}${placement.collectionPath.join(".")}`,
        );
        if (!collection) continue;
        const match = nodeChildren(collection).find((child, index) => (
            resourceIdentity(placement, child, index)?.[0] === node.id
        ));
        if (match) return match.id;
    }
    return undefined;
}


function projectExistingNode<TNode extends ResourceGraphNode>(
    node: TNode,
    draft: ResourceGraphDraft,
    nodesByTarget: ReadonlyMap<string, EditNode>,
    placements: ResourcePlacement[],
    migrationTargets: ReadonlyMap<string, string[]>,
): TNode {
    if (node.kind !== "resource") return node;
    let projected = withEditCapability(
        node,
        configuredResourceTarget(node, nodesByTarget, placements),
    );
    let semanticRemoval = false;
    if (
        node.resourcePlural === "snapshotmigrations"
        && node.navigationKey?.length === 4
        && node.navigationKey.every(Boolean)
        && nodesByTarget.has("edit:snapshotMigrationConfigs")
    ) {
        const matchingTargets = migrationTargets.get(
            JSON.stringify(node.navigationKey),
        ) ?? [];
        if (matchingTargets.length === 0) {
            projected = withoutEditCapability(projected);
            semanticRemoval = true;
        } else {
            const currentTarget = editTarget(projected);
            projected = withEditCapability(
                projected,
                currentTarget && matchingTargets.includes(currentTarget)
                    ? currentTarget
                    : matchingTargets[0],
            );
        }
    }

    const targetId = editTarget(projected);
    const editNode = targetId ? nodesByTarget.get(targetId) : undefined;
    const state = editNode ? configState(editNode, draft.dirty) : null;
    const presence = projected.configPresence ?? {};
    const explicitRemoval = presence.pending === false
        && (presence.deployed === true || presence.submitted === true);
    const removedFromDraft = semanticRemoval || (
        draft.dirty
        && Boolean(targetId)
        && isResourceTarget(targetId ?? "", placements)
        && !nodesByTarget.has(targetId ?? "")
        && resourceCollectionChanged(targetId ?? "", nodesByTarget, placements)
    );
    if (!explicitRemoval && !removedFromDraft) {
        return { ...projected, configState: state };
    }
    const pendingWasPresent = presence.pending !== false;
    return {
        ...projected,
        revision: `${projected.revision}:${draft.draftRevision}:removed`,
        status: "removed",
        valueSummary: pendingWasPresent && draft.dirty
            ? "Marked for removal"
            : "Removal pending submission",
        configState: state,
    };
}


function resourceIdentity(
    placement: ResourcePlacement,
    child: EditNode,
    index: number,
): [string, string] | null {
    if (child.path.length <= placement.collectionPath.length) return null;
    const authoredName = child.path[placement.collectionPath.length];
    if (placement.identity.kind === "indexed-config") {
        const resourceName = (
            `${placement.identity.prefix}${index + placement.identity.firstIndex}`
        );
        return [
            `config:${placement.collectionPath.join(".")}:${index}`,
            resourceName,
        ];
    }
    const resourceName = (
        `${placement.identity.prefix}${authoredName}${placement.identity.suffix}`
    );
    return [`resource:${placement.resourcePlural}:${resourceName}`, resourceName];
}


function snapshotMigrationDisplayName(key: string[]): string {
    const placeholders = ["<SOURCE>", "<TARGET>", "<SNAPSHOT>", "<NAME>"];
    return key.map((value, index) => value || placeholders[index]).join("-");
}


function newResourceNode<TNode extends ResourceGraphNode>(
    placement: ResourcePlacement,
    child: EditNode,
    index: number,
    draft: ResourceGraphDraft,
    existingNodeIds: ReadonlySet<string>,
): TNode | null {
    if (child.valueKind === "command") return null;
    const targetId = child.id;
    if (placement.collectionPath.join(".") === "snapshotMigrationConfigs") {
        if (child.path.length !== 2 || !/^\d+$/.test(child.path[1])) return null;
        const value = (
            typeof child.value === "object" && child.value !== null
                ? child.value as Record<string, unknown>
                : {}
        );
        const navigationKey = [
            value.fromSource,
            value.toTarget,
            value.fromSnapshot,
            value.slice,
        ].map((part) => typeof part === "string" ? part : "");
        const nodeId = `config:${targetId.slice(EDIT_TARGET_PREFIX.length).replaceAll(".", ":")}`;
        return {
            ...emptyNavigationNode<TNode>(
                nodeId,
                draft.draftRevision,
                "resource",
                snapshotMigrationDisplayName(navigationKey),
                placement.groupId,
            ),
            revision: `${draft.draftRevision}:${targetId}:added`,
            description: `${placement.resourcePlural}/${snapshotMigrationDisplayName(navigationKey)}`,
            status: child.status && child.status !== "ok" ? child.status : "changed",
            phase: "Pending Config",
            valueSummary: "Addition pending submission",
            diagnostics: graphDiagnostics(child),
            capabilities: [{
                kind: "edit",
                editTargetId: targetId,
                label: `Edit ${String(value.slice ?? "")}`,
            }],
            details: [{ label: "Phase", value: "Pending Config", kind: "phase" }],
            resourcePlural: placement.resourcePlural,
            resourceName: snapshotMigrationDisplayName(navigationKey),
            resourceType: placement.resourceType,
            configPresence: { deployed: false, pending: true },
            configState: configState(child, draft.dirty),
            navigationKey,
        };
    }

    const identity = resourceIdentity(placement, child, index);
    if (!identity || existingNodeIds.has(identity[0])) return null;
    const [nodeId, resourceName] = identity;
    const implicit = child.implicit === true;
    return {
        ...emptyNavigationNode<TNode>(
            nodeId,
            draft.draftRevision,
            "resource",
            resourceName,
            placement.groupId,
        ),
        revision: `${draft.draftRevision}:${targetId}:${implicit ? "implicit" : "added"}`,
        description: `${placement.resourcePlural}/${resourceName}`,
        status: child.status && (implicit || child.status !== "ok")
            ? child.status
            : "changed",
        phase: implicit ? "Implicit default" : "Pending Config",
        valueSummary: implicit
            ? "Available when referenced"
            : "Addition pending submission",
        diagnostics: graphDiagnostics(child),
        capabilities: [{
            kind: "edit",
            editTargetId: targetId,
            label: `Edit ${resourceName}`,
        }],
        details: [{
            label: "Phase",
            value: implicit ? "Implicit default" : "Pending Config",
            kind: "phase",
        }],
        resourcePlural: placement.resourcePlural,
        resourceName,
        resourceType: placement.resourceType,
        configPresence: { deployed: false, pending: !implicit },
        configState: configState(child, draft.dirty),
    };
}


function appendChild<TNode extends ResourceGraphNode>(
    nodes: Record<string, TNode>,
    parentId: string,
    childId: string,
    revision: string,
): void {
    const parent = nodes[parentId];
    if (!parent || (parent.childIds ?? []).includes(childId)) return;
    nodes[parentId] = {
        ...parent,
        revision: `${parent.revision}:${revision}`,
        childIds: [...(parent.childIds ?? []), childId],
    };
}


function addDraftResources<TNode extends ResourceGraphNode>(
    nodes: Record<string, TNode>,
    nodesByTarget: ReadonlyMap<string, EditNode>,
    placements: ResourcePlacement[],
    draft: ResourceGraphDraft,
): void {
    const existingTargets = new Set(
        Object.values(nodes).flatMap((node) => {
            const target = editTarget(node);
            return target ? [target] : [];
        }),
    );
    placements.forEach((placement) => {
        const collection = nodesByTarget.get(
            `${EDIT_TARGET_PREFIX}${placement.collectionPath.join(".")}`,
        );
        if (!collection) return;
        nodeChildren(collection).forEach((child, index) => {
            if (existingTargets.has(child.id)) return;
            const candidate = newResourceNode<TNode>(
                placement,
                child,
                index,
                draft,
                new Set(Object.keys(nodes)),
            );
            if (!candidate) return;
            const existing = nodes[candidate.id];
            if (existing && placement.resourcePlural === "snapshotmigrations") {
                nodes[candidate.id] = {
                    ...existing,
                    capabilities: [
                        ...(existing.capabilities ?? []).filter(
                            (capability) => capability.kind !== "edit",
                        ),
                        ...(candidate.capabilities ?? []),
                    ],
                    configState: candidate.configState,
                    navigationKey: candidate.navigationKey,
                };
            } else {
                nodes[candidate.id] = candidate;
            }
            existingTargets.add(child.id);
            appendChild(nodes, placement.groupId, candidate.id, draft.draftRevision);
        });
    });
}


function newDefinitionNode<TNode extends ResourceGraphNode>(
    child: EditNode,
    placement: DefinitionPlacement,
    draft: ResourceGraphDraft,
): TNode | null {
    if (
        child.valueKind === "command"
        || child.path.length <= placement.collectionPath.length
    ) {
        return null;
    }
    const label = child.path[placement.collectionPath.length];
    return {
        ...emptyNavigationNode<TNode>(
            `definition:${child.id}`,
            draft.draftRevision,
            "config-definition",
            label,
            placement.groupId,
        ),
        revision: `${draft.draftRevision}:${child.id}`,
        description: placement.definitionType,
        status: child.status ?? "ok",
        diagnostics: graphDiagnostics(child),
        capabilities: [{
            kind: "edit",
            editTargetId: child.id,
            label: `Edit ${label}`,
        }],
        resourceType: placement.definitionType,
        configState: configState(child, draft.dirty),
    };
}


function addDraftDefinitions<TNode extends ResourceGraphNode>(
    nodes: Record<string, TNode>,
    nodesByTarget: ReadonlyMap<string, EditNode>,
    placements: DefinitionPlacement[],
    draft: ResourceGraphDraft,
): void {
    const ownersByTarget = new Map(
        Object.values(nodes).flatMap((node) => {
            const target = editTarget(node);
            return target ? [[target, node] as const] : [];
        }),
    );
    const placementsByOwner = new Map<string, DefinitionPlacement[]>();
    placements.forEach((placement) => placementsByOwner.set(
        placement.ownerTargetId,
        [...(placementsByOwner.get(placement.ownerTargetId) ?? []), placement],
    ));
    placementsByOwner.forEach((ownerPlacements, ownerTargetId) => {
        const owner = ownersByTarget.get(ownerTargetId);
        if (!owner) return;
        const groupOrder = new Map<string, number>();
        const childIds = [...(owner.childIds ?? [])];
        ownerPlacements.forEach((placement) => {
            const collection = nodesByTarget.get(
                `${EDIT_TARGET_PREFIX}${placement.collectionPath.join(".")}`,
            );
            if (!collection) return;
            const definitions = nodeChildren(collection).flatMap((child) => {
                const definition = newDefinitionNode<TNode>(child, placement, draft);
                return definition ? [definition] : [];
            });
            const group = {
                ...emptyNavigationNode<TNode>(
                    placement.groupId,
                    draft.draftRevision,
                    "group",
                    placement.groupLabel,
                    owner.id,
                ),
                revision: `${draft.draftRevision}:${collection.id}`,
                childIds: definitions.map((definition) => definition.id),
            };
            groupOrder.set(group.id, placement.groupOrder);
            nodes[group.id] = group;
            definitions.forEach((definition) => {
                nodes[definition.id] = definition;
            });
            childIds.push(group.id);
        });
        nodes[owner.id] = {
            ...owner,
            revision: `${owner.revision}:${draft.draftRevision}:definitions`,
            childIds: orderedIds(childIds, groupOrder),
        };
    });
}


function preferResourceSurfaces<TNode extends ResourceGraphNode>(
    nodes: Record<string, TNode>,
): void {
    Object.values(nodes)
        .filter((node) => node.kind === "config-definition")
        .forEach((definition) => {
            if (!nodes[definition.id]) return;
            const target = editTarget(definition);
            if (!target) return;
            const owner = Object.values(nodes).find((node) => {
                const ownerTarget = editTarget(node);
                return node.kind === "resource"
                    && Boolean(ownerTarget)
                    && (
                        ownerTarget === target
                        || ownerTarget?.startsWith(`${target}.`)
                    );
            });
            if (!owner || !definition.parentId || !nodes[definition.parentId]) return;
            const previousParentId = owner.parentId;
            const definitionParent = nodes[definition.parentId];
            nodes[definition.parentId] = {
                ...definitionParent,
                childIds: (definitionParent.childIds ?? [])
                    .filter((childId) => childId !== owner.id)
                    .map((childId) => childId === definition.id ? owner.id : childId),
            };
            if (
                previousParentId
                && previousParentId !== definition.parentId
                && nodes[previousParentId]
            ) {
                const previousParent = nodes[previousParentId];
                nodes[previousParentId] = {
                    ...previousParent,
                    childIds: (previousParent.childIds ?? []).filter(
                        (childId) => childId !== owner.id,
                    ),
                };
            }
            nodes[owner.id] = withEditCapability({
                ...owner,
                parentId: definition.parentId,
            }, target);
            delete nodes[definition.id];
        });
}


function naturalParts(value: string): Array<string | number> {
    return value.split(/(\d+)/).filter(Boolean).map(
        (part) => /^\d+$/.test(part) ? Number(part) : part.toLocaleLowerCase(),
    );
}


function naturalCompare(left: string, right: string): number {
    const leftParts = naturalParts(left);
    const rightParts = naturalParts(right);
    for (let index = 0; index < Math.max(leftParts.length, rightParts.length); index += 1) {
        const leftPart = leftParts[index];
        const rightPart = rightParts[index];
        if (leftPart === undefined) return -1;
        if (rightPart === undefined) return 1;
        if (leftPart === rightPart) continue;
        if (typeof leftPart === "number" && typeof rightPart === "number") {
            return leftPart - rightPart;
        }
        return String(leftPart).localeCompare(String(rightPart));
    }
    return 0;
}


function snapshotNodeCompare(
    left: ResourceGraphNode,
    right: ResourceGraphNode,
): number {
    const leftKey = left.navigationKey ?? [];
    const rightKey = right.navigationKey ?? [];
    for (let index = 0; index < Math.max(leftKey.length, rightKey.length); index += 1) {
        const compared = naturalCompare(leftKey[index] ?? "", rightKey[index] ?? "");
        if (compared !== 0) return compared;
    }
    return 0;
}


function snapshotGroupId(prefix: string[]): string {
    return `${SNAPSHOT_DYNAMIC_GROUP_PREFIX}${prefix.length}:${prefix.join(":")}`;
}


function snapshotGroupDepth(nodeId: string | null): number {
    if (!nodeId?.startsWith(SNAPSHOT_DYNAMIC_GROUP_PREFIX)) return 0;
    return Number(nodeId.split(":", 3)[1]) || 0;
}


function normalizedResourceName(parts: string[]): string {
    return parts.join("-").toLocaleLowerCase()
        .replaceAll(/[^a-z0-9.]+/g, "-")
        .replaceAll(/^[.-]+|[.-]+$/g, "");
}


function snapshotGroupStatus<TNode extends ResourceGraphNode>(
    nodes: Readonly<Record<string, TNode>>,
    childIds: string[],
): string {
    const statuses = childIds.flatMap((childId) => (
        nodes[childId] ? [nodes[childId].status] : []
    ));
    if (statuses.length === 0) return "ok";
    if (statuses.every((status) => status === "removed")) return "removed";
    return statuses
        .map((status) => status === "removed" ? "changed" : status)
        .sort((left, right) => (
            (NAVIGATION_STATUS_RANK[right] ?? 0)
            - (NAVIGATION_STATUS_RANK[left] ?? 0)
        ))[0];
}


function buildSnapshotRepresentations<TNode extends ResourceGraphNode>(
    nodes: Record<string, TNode>,
    revision: string,
    items: TNode[],
    level: number,
    parentId: string,
): string[] {
    if (level >= 3) {
        items.forEach((item) => {
            nodes[item.id] = { ...item, parentId };
        });
        return items.map((item) => item.id);
    }
    const buckets = new Map<string, TNode[]>();
    items.forEach((item) => {
        const value = item.navigationKey?.[level] ?? "";
        buckets.set(value, [...(buckets.get(value) ?? []), item]);
    });
    const result: string[] = [];
    [...buckets.keys()].sort(naturalCompare).forEach((value) => {
        const bucket = [...(buckets.get(value) ?? [])].sort(snapshotNodeCompare);
        const prefix = (bucket[0].navigationKey ?? []).slice(0, level + 1);
        const prospectiveParent = snapshotGroupId(prefix);
        const children = buildSnapshotRepresentations(
            nodes,
            revision,
            bucket,
            level + 1,
            prospectiveParent,
        );
        if (bucket.length >= 3 && children.length >= 2) {
            const parentDepth = snapshotGroupDepth(parentId);
            nodes[prospectiveParent] = {
                ...emptyNavigationNode<TNode>(
                    prospectiveParent,
                    revision,
                    "group",
                    normalizedResourceName(prefix.slice(parentDepth)),
                    parentId,
                ),
                childIds: children,
                status: snapshotGroupStatus(nodes, children),
            };
            result.push(prospectiveParent);
        } else {
            children.forEach((childId) => {
                nodes[childId] = { ...nodes[childId], parentId };
            });
            result.push(...children);
        }
    });
    return result;
}


export function groupSnapshotMigrationNavigation<
    TNode extends ResourceGraphNode,
    TSnapshot extends ResourceGraphSnapshot<TNode>,
>(snapshot: TSnapshot): TSnapshot {
    const nodes = Object.fromEntries(
        Object.entries(snapshot.nodes).filter(
            ([nodeId]) => !nodeId.startsWith(SNAPSHOT_DYNAMIC_GROUP_PREFIX),
        ),
    ) as Record<string, TNode>;
    const baseGroup = nodes[SNAPSHOT_BASE_GROUP_ID];
    if (!baseGroup) return snapshot;
    const resources = Object.values(nodes)
        .filter((node) => (
            node.kind === "resource"
            && node.resourcePlural === "snapshotmigrations"
            && node.navigationKey?.length === 4
            && node.navigationKey.every(Boolean)
        ))
        .sort(snapshotNodeCompare);
    const resourceIds = new Set(resources.map((node) => node.id));
    nodes[baseGroup.id] = {
        ...baseGroup,
        label: "Snapshot migrations",
        childIds: (baseGroup.childIds ?? []).filter((childId) => (
            !resourceIds.has(childId)
            && !childId.startsWith(SNAPSHOT_DYNAMIC_GROUP_PREFIX)
        )),
    };
    resources.forEach((resource) => {
        nodes[resource.id] = {
            ...resource,
            parentId: baseGroup.id,
            label: normalizedResourceName(resource.navigationKey ?? []),
        };
    });
    const groupedIds = buildSnapshotRepresentations(
        nodes,
        snapshot.revision,
        resources,
        0,
        baseGroup.id,
    );
    nodes[baseGroup.id] = {
        ...nodes[baseGroup.id],
        childIds: [...(nodes[baseGroup.id].childIds ?? []), ...groupedIds],
        status: groupedIds.length > 0
            ? snapshotGroupStatus(nodes, groupedIds)
            : nodes[baseGroup.id].status,
    };

    Object.values(nodes).forEach((node) => {
        if (
            node.kind === "group"
            && node.id.startsWith(SNAPSHOT_DYNAMIC_GROUP_PREFIX)
        ) {
            const prefix = node.id.split(":").slice(2);
            nodes[node.id] = {
                ...node,
                label: normalizedResourceName(
                    prefix.slice(snapshotGroupDepth(node.parentId)),
                ),
            };
        }
    });
    resources.forEach((resource) => {
        const grouped = nodes[resource.id];
        nodes[resource.id] = {
            ...grouped,
            label: normalizedResourceName(
                (grouped.navigationKey ?? []).slice(
                    snapshotGroupDepth(grouped.parentId),
                ),
            ),
        };
    });

    const section = nodes[SNAPSHOT_SECTION_ID];
    const currentBase = nodes[SNAPSHOT_BASE_GROUP_ID];
    if (section && currentBase?.parentId === section.id) {
        const flattened = (section.childIds ?? []).flatMap((childId) => (
            childId === currentBase.id ? currentBase.childIds ?? [] : [childId]
        ));
        (currentBase.childIds ?? []).forEach((childId) => {
            if (nodes[childId]) nodes[childId] = { ...nodes[childId], parentId: section.id };
        });
        nodes[section.id] = {
            ...section,
            childIds: [...new Set(flattened)].filter((childId) => (
                !nodes[childId] || nodes[childId].parentId === section.id
            )),
        };
        delete nodes[currentBase.id];
    }
    return { ...snapshot, nodes };
}


function surfaceForTarget<TNode extends ResourceGraphNode>(
    nodes: Readonly<Record<string, TNode>>,
    targetId: string,
): TNode | undefined {
    return Object.values(nodes)
        .filter((node) => (
            ["resource", "config-definition"].includes(node.kind)
            && editTarget(node) === targetId
        ))
        .sort((left, right) => (
            (right.kind === "resource" ? 1 : 0)
            - (left.kind === "resource" ? 1 : 0)
        ))[0];
}


export function configReferences(config: unknown): ConfigReference[] {
    return buildConfigDependencyGraph(config).map((reference) => ({
        fromTargetId: `${EDIT_TARGET_PREFIX}${reference.fromPath.join(".")}`,
        fromFieldPath: [...reference.fromFieldPath],
        toTargetId: `${EDIT_TARGET_PREFIX}${reference.toPath.join(".")}`,
        reason: reference.reason,
    }));
}


function overlayConfigRelationships<TNode extends ResourceGraphNode>(
    nodes: Record<string, TNode>,
    config: unknown,
): void {
    const configuredRelationships = new Map<string, ResourceGraphRelationship[]>();
    configReferences(config).forEach((reference) => {
        const from = surfaceForTarget(nodes, reference.fromTargetId);
        const to = surfaceForTarget(nodes, reference.toTargetId);
        if (!from || !to || from.id === to.id) return;
        configuredRelationships.set(from.id, [
            ...(configuredRelationships.get(from.id) ?? []),
            {
                kind: "runtime-dependency",
                direction: "requires",
                targetId: to.id,
                targetName: to.label,
                targetPlural: to.resourcePlural,
                targetPhase: to.phase,
                targetStatus: to.status,
            },
        ]);
        configuredRelationships.set(to.id, [
            ...(configuredRelationships.get(to.id) ?? []),
            {
                kind: "runtime-dependency",
                direction: "required-by",
                targetId: from.id,
                targetName: from.label,
                targetPlural: from.resourcePlural,
                targetPhase: from.phase,
                targetStatus: from.status,
            },
        ]);
    });
    Object.values(nodes)
        .filter((node) => (
            node.kind === "resource"
            || node.kind === "config-definition"
        ))
        .forEach((node) => {
            const nonDependencyRelationships = (node.relationships ?? [])
                .filter((relationship) => (
                    relationship.kind !== "runtime-dependency"
                ));
            nodes[node.id] = {
                ...node,
                relationships: [
                    ...nonDependencyRelationships,
                    ...(configuredRelationships.get(node.id) ?? []),
                ],
            };
        });
}


export function configRemovalImpact(
    config: unknown,
    path: string[],
): ConfigRemovalImpactEntry[] {
    const directPaths = new Set(
        buildConfigDependencyGraph(config)
            .filter((reference) => (
                reference.toPath.length >= path.length
                && path.every(
                    (part, index) => reference.toPath[index] === part,
                )
            ))
            .map((reference) => reference.fromPath.join("\0")),
    );
    const seen = new Set<string>();
    return downstreamConfigReferences(config, path).flatMap((reference) => {
        const fromTargetId = `${EDIT_TARGET_PREFIX}${reference.fromPath.join(".")}`;
        if (seen.has(fromTargetId)) return [];
        seen.add(fromTargetId);
        return [{
            path: [...reference.fromPath],
            fieldPath: [...reference.fromFieldPath],
            reason: reference.reason,
            direct: directPaths.has(reference.fromPath.join("\0")),
        }];
    });
}


export function projectConfigResourceGraph<
    TNode extends ResourceGraphNode,
    TSnapshot extends ResourceGraphSnapshot<TNode>,
>(
    snapshot: TSnapshot,
    draft: ResourceGraphDraft,
): TSnapshot {
    const configuration = withoutWorkflowSteps(snapshot);
    if (
        draft.editState.provenance.mode === "raw"
        || draft.repairYaml !== null && draft.repairYaml !== undefined
    ) {
        return configuration;
    }
    const nodesByTarget = editNodes(draft.editState.nodes);
    const placements = resourcePlacements(draft.editState.nodes);
    const definitions = definitionPlacements(draft.editState.nodes);
    const migrationTargets = snapshotMigrationTargets(nodesByTarget);
    const nodes = Object.fromEntries(
        Object.entries(configuration.nodes).map(([nodeId, node]) => [
            nodeId,
            projectExistingNode(
                node,
                draft,
                nodesByTarget,
                placements,
                migrationTargets,
            ),
        ]),
    ) as Record<string, TNode>;
    const rootIds = [...configuration.rootIds];
    ensureNavigation(nodes, rootIds, placements, draft.draftRevision);
    addDraftResources(nodes, nodesByTarget, placements, draft);
    addDraftDefinitions(nodes, nodesByTarget, definitions, draft);
    preferResourceSurfaces(nodes);
    overlayConfigRelationships(nodes, draft.config);
    return groupSnapshotMigrationNavigation({
        ...configuration,
        revision: `${snapshot.revision}:${draft.draftRevision}:configuration`,
        rootIds,
        nodes,
    });
}
