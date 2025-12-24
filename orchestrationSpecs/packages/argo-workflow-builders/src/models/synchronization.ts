import { ConfigMapKeySelector } from "./parameterSchemas";
import { LowercaseOnly } from "./workflowTypes";

/**
 * DNS-1123 subdomain name constraint for K8s resource names
 * - contain no more than 253 characters
 * - contain only lowercase alphanumeric characters, '-' or '.'
 * - start with an alphanumeric character
 * - end with an alphanumeric character
 */
export type K8sSubdomainName<S extends string> = 
  S extends LowercaseOnly<S> ? S : never;

/**
 * DNS-1123 label name constraint for K8s labels/keys
 * - contain at most 63 characters
 * - contain only lowercase alphanumeric characters or '-'
 * - start with an alphabetic character
 * - end with an alphanumeric character
 */
export type K8sLabelName<S extends string> = 
  S extends LowercaseOnly<S> ? S : never;

export type SemaphoreConfig = {
  configMapKeyRef: ConfigMapKeySelector;
  namespace?: string;
} | {
  database: { key: string };
  namespace?: string;
};

export type MutexConfig = {
  name: string;
  namespace?: string;
  database?: boolean;
};

export type SynchronizationConfig = {
  semaphores?: SemaphoreConfig[];
  mutexes?: MutexConfig[];
};

export function localSemaphore<
  ConfigMapName extends string,
  Key extends string
>(
  configMapName: K8sSubdomainName<ConfigMapName>, 
  key: K8sLabelName<Key>
): SemaphoreConfig {
  return {
    configMapKeyRef: { name: configMapName, key }
  };
}

export function databaseSemaphore<
  Key extends string
>(
  key: K8sLabelName<Key>
): SemaphoreConfig {
  return {
    database: { key }
  };
}

export function localMutex<
  Name extends string
>(
  name: K8sLabelName<Name>
): MutexConfig {
  return {
    name
  };
}

export function databaseMutex<
  Name extends string
>(
  name: K8sLabelName<Name>
): MutexConfig {
  return {
    name,
    database: true
  };
}
