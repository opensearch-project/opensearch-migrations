/**
 * ConfigMap State Store
 *
 * Helpers for reading/writing observation and checksum data to ConfigMaps.
 * These are used by the outer test workflow's assert steps to persist
 * observations between runs and compare prior vs current state.
 *
 * At compile time (in the scenario compiler), these produce the kubectl
 * commands that will be embedded in the outer workflow's container steps.
 * At runtime (inside the Argo container), the actual kubectl calls happen.
 *
 * ConfigMap naming convention:
 *   e2e-<scenario>-obs-<runIndex>     — observation after run N
 *   e2e-<scenario>-checksums-<runIndex> — expected checksum report for run N
 */
import type { ChecksumReport, ScenarioObservation } from './types';

/** Generate a deterministic ConfigMap name for a scenario's observation at a given run. */
export function observationConfigMapName(scenario: string, runIndex: number): string {
    return `e2e-${sanitize(scenario)}-obs-${runIndex}`;
}

/** Generate a deterministic ConfigMap name for a scenario's checksum report at a given run. */
export function checksumReportConfigMapName(scenario: string, runIndex: number): string {
    return `e2e-${sanitize(scenario)}-checksums-${runIndex}`;
}

/** List all ConfigMap names a scenario will create, for teardown. */
export function allConfigMapNames(scenario: string, runCount: number): string[] {
    const names: string[] = [];
    for (let i = 0; i < runCount; i++) {
        names.push(observationConfigMapName(scenario, i));
        names.push(checksumReportConfigMapName(scenario, i));
    }
    return names;
}

/**
 * Generate a kubectl command to write a JSON value to a ConfigMap.
 * Used in the outer workflow's container steps.
 */
export function writeConfigMapCommand(name: string, key: string, jsonValue: string): string {
    // Create-or-update pattern: try create, fall back to patch
    const escaped = jsonValue.replace(/'/g, "'\\''");
    return [
        `kubectl create configmap ${name} --from-literal='${key}=${escaped}' 2>/dev/null`,
        `|| kubectl patch configmap ${name} -p '{"data":{"${key}":${JSON.stringify(escaped)}}}'`,
    ].join(' ');
}

/**
 * Generate a kubectl command to read a JSON value from a ConfigMap.
 * Returns the shell expression that outputs the value to stdout.
 */
export function readConfigMapCommand(name: string, key: string): string {
    return `kubectl get configmap ${name} -o jsonpath='{.data.${key}}'`;
}

/**
 * Generate kubectl commands to delete all scenario ConfigMaps.
 */
export function deleteConfigMapsCommand(scenario: string, runCount: number): string {
    const names = allConfigMapNames(scenario, runCount);
    return `kubectl delete configmap ${names.join(' ')} --ignore-not-found=true`;
}

/** Sanitize a scenario name for use in K8s resource names. */
function sanitize(name: string): string {
    return name.replace(/[^a-z0-9-]/g, '-').replace(/-+/g, '-').substring(0, 50);
}
