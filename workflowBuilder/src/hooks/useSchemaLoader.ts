/**
 * useSchemaLoader Hook
 * 
 * Loads a JSON Schema from a URL with caching, loading states, and error handling.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import type { JSONSchema7 } from '../types';
import type { UseSchemaLoaderReturn } from '../types/schema-selector.types';
import { fetchSchema, SchemaLoadError } from '../lib/schema-loader';

/**
 * Cache for loaded schemas to avoid redundant fetches
 */
const schemaCache = new Map<string, JSONSchema7>();

/**
 * Hook for loading a JSON Schema from a URL.
 * 
 * Features:
 * - Automatic loading when URL changes
 * - Caching to avoid redundant fetches
 * - Loading and error states
 * - Manual reload capability
 * 
 * @param url - The URL to load the schema from (empty string skips loading)
 * @returns Schema loading state and control functions
 */
export function useSchemaLoader(url: string): UseSchemaLoaderReturn {
  const [schema, setSchema] = useState<JSONSchema7 | null>(null);
  // Start loading if we have a URL
  const [isLoading, setIsLoading] = useState<boolean>(!!url);
  const [error, setError] = useState<string | null>(null);
  
  // Track the current URL to handle race conditions
  const currentUrlRef = useRef<string>(url);
  // Track if this is the initial load
  const isInitialLoadRef = useRef<boolean>(true);

  /**
   * Load schema from the given URL
   */
  const loadSchema = useCallback(async (targetUrl: string, useCache: boolean = true) => {
    console.log('[useSchemaLoader] loadSchema called:', { targetUrl, useCache });
    
    // Skip loading if URL is empty
    if (!targetUrl) {
      console.log('[useSchemaLoader] Empty URL, skipping load');
      setSchema(null);
      setError(null);
      setIsLoading(false);
      return;
    }

    // Check cache first
    if (useCache && schemaCache.has(targetUrl)) {
      const cachedSchema = schemaCache.get(targetUrl)!;
      console.log('[useSchemaLoader] Using cached schema:', { targetUrl, schemaKeys: Object.keys(cachedSchema) });
      setSchema(cachedSchema);
      setError(null);
      setIsLoading(false);
      return;
    }

    setIsLoading(true);
    setError(null);

    try {
      console.log('[useSchemaLoader] Fetching schema from:', targetUrl);
      const loadedSchema = await fetchSchema(targetUrl);
      console.log('[useSchemaLoader] Schema loaded:', { targetUrl, schemaKeys: Object.keys(loadedSchema), schemaType: loadedSchema.type });
      
      // Only update state if this is still the current URL
      if (currentUrlRef.current === targetUrl) {
        // Cache the schema
        schemaCache.set(targetUrl, loadedSchema);
        setSchema(loadedSchema);
        setError(null);
        console.log('[useSchemaLoader] Schema state updated');
      } else {
        console.log('[useSchemaLoader] URL changed during fetch, ignoring result');
      }
    } catch (err) {
      // Only update state if this is still the current URL
      if (currentUrlRef.current === targetUrl) {
        setSchema(null);
        if (err instanceof SchemaLoadError) {
          setError(err.message);
        } else if (err instanceof Error) {
          setError(`Failed to load schema: ${err.message}`);
        } else {
          setError('Failed to load schema: Unknown error');
        }
      }
    } finally {
      // Only update loading state if this is still the current URL
      if (currentUrlRef.current === targetUrl) {
        setIsLoading(false);
      }
    }
  }, []);

  /**
   * Reload the schema, bypassing cache
   */
  const reload = useCallback(() => {
    if (url) {
      // Remove from cache to force reload
      schemaCache.delete(url);
      loadSchema(url, false);
    }
  }, [url, loadSchema]);

  // Load schema when URL changes
  useEffect(() => {
    currentUrlRef.current = url;
    
    // On initial load, try to use cache
    // On subsequent URL changes, also use cache
    loadSchema(url, true);
    
    isInitialLoadRef.current = false;
  }, [url, loadSchema]);

  return {
    schema,
    isLoading,
    error,
    reload,
  };
}

/**
 * Clear the schema cache (useful for testing)
 */
export function clearSchemaCache(): void {
  schemaCache.clear();
}

export default useSchemaLoader;
