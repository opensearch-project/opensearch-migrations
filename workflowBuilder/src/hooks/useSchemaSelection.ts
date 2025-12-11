/**
 * useSchemaSelection Hook
 * 
 * Manages schema selection state with localStorage persistence.
 * Allows users to switch between the default bundled schema and custom URLs.
 */

import { useState, useCallback, useEffect, useMemo } from 'react';
import type { SchemaSourceType, UseSchemaSelectionReturn } from '../types/schema-selector.types';
import { SCHEMA_SELECTION_STORAGE_KEY } from '../types/schema-selector.types';
import { getDefaultSchemaUrl, resolveSchemaUrl } from '../lib/schema-loader';

/**
 * Persisted state structure for localStorage
 */
interface PersistedState {
  sourceType: SchemaSourceType;
  customUrl: string;
}

/**
 * Loads persisted state from localStorage
 */
function loadPersistedState(): PersistedState | null {
  try {
    const stored = localStorage.getItem(SCHEMA_SELECTION_STORAGE_KEY);
    if (!stored) {
      return null;
    }
    const parsed = JSON.parse(stored) as PersistedState;
    // Validate the parsed data
    if (
      typeof parsed === 'object' &&
      parsed !== null &&
      (parsed.sourceType === 'default' || parsed.sourceType === 'custom') &&
      typeof parsed.customUrl === 'string'
    ) {
      return parsed;
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * Saves state to localStorage
 */
function savePersistedState(state: PersistedState): void {
  try {
    localStorage.setItem(SCHEMA_SELECTION_STORAGE_KEY, JSON.stringify(state));
  } catch {
    // Ignore localStorage errors (e.g., quota exceeded, private browsing)
    console.warn('Failed to persist schema selection to localStorage');
  }
}

/**
 * Hook for managing schema selection state with localStorage persistence.
 * 
 * @returns Schema selection state and control functions
 */
export function useSchemaSelection(): UseSchemaSelectionReturn {
  // Load initial state from localStorage or use defaults
  const [sourceType, setSourceTypeState] = useState<SchemaSourceType>(() => {
    const persisted = loadPersistedState();
    return persisted?.sourceType ?? 'default';
  });

  const [customUrl, setCustomUrlState] = useState<string>(() => {
    const persisted = loadPersistedState();
    return persisted?.customUrl ?? '';
  });

  // Persist state changes to localStorage
  useEffect(() => {
    savePersistedState({ sourceType, customUrl });
  }, [sourceType, customUrl]);

  // Calculate the resolved URL based on current selection
  const resolvedUrl = useMemo(() => {
    let url = '';
    if (sourceType === 'default') {
      url = getDefaultSchemaUrl();
    } else if (customUrl.trim()) {
      url = resolveSchemaUrl(customUrl.trim());
    }
    console.log('[useSchemaSelection] resolvedUrl:', { sourceType, customUrl, resolvedUrl: url });
    return url;
  }, [sourceType, customUrl]);

  // Set source type with validation
  const setSourceType = useCallback((type: SchemaSourceType) => {
    setSourceTypeState(type);
  }, []);

  // Set custom URL
  const setCustomUrl = useCallback((url: string) => {
    setCustomUrlState(url);
  }, []);

  // Reset to default schema
  const resetToDefault = useCallback(() => {
    setSourceTypeState('default');
    setCustomUrlState('');
  }, []);

  return {
    sourceType,
    customUrl,
    resolvedUrl,
    setSourceType,
    setCustomUrl,
    resetToDefault,
  };
}

export default useSchemaSelection;
