/**
 * Debug logging utility for the workflowBuilder focus feature.
 *
 * This module provides conditional debug logging that only outputs when running
 * in development mode (npm run dev / ./gradlew :workflowBuilder:npmDev).
 * Logs are completely silent in production builds.
 */

/**
 * Log levels for debug output
 */
export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

/**
 * Configuration for the debug logger
 */
export interface DebugLoggerConfig {
  /** Whether logging is enabled (auto-detected from NODE_ENV) */
  enabled: boolean;
  /** Minimum log level to output */
  minLevel: LogLevel;
  /** Prefix for all log messages */
  prefix: string;
  /** Whether to include timestamps */
  includeTimestamp: boolean;
}

/**
 * Logger instance interface
 */
export interface DebugLogger {
  debug: (message: string, ...args: unknown[]) => void;
  info: (message: string, ...args: unknown[]) => void;
  warn: (message: string, ...args: unknown[]) => void;
  error: (message: string, ...args: unknown[]) => void;
  group: (label: string) => void;
  groupEnd: () => void;
  /** Create a child logger with a sub-prefix */
  child: (subPrefix: string) => DebugLogger;
}

/**
 * Numeric values for log levels to enable comparison
 */
const LOG_LEVEL_VALUES: Record<LogLevel, number> = {
  debug: 0,
  info: 1,
  warn: 2,
  error: 3,
};

/**
 * Check if running in development mode.
 * Uses Vite's import.meta.env.DEV for environment detection.
 *
 * @returns true if running in development mode, false otherwise
 */
export function isDevMode(): boolean {
  // Vite sets import.meta.env.DEV to true during development
  // and false during production builds
  try {
    return import.meta.env.DEV === true;
  } catch {
    // Fallback for non-Vite environments (e.g., tests)
    return process.env.NODE_ENV === 'development';
  }
}

/**
 * Format a log message with optional prefix and timestamp.
 *
 * @param prefix - The prefix to prepend to the message
 * @param message - The log message
 * @param includeTimestamp - Whether to include a timestamp
 * @returns The formatted message string
 */
export function formatLogMessage(
  prefix: string,
  message: string,
  includeTimestamp: boolean
): string {
  const parts: string[] = [];

  if (includeTimestamp) {
    const now = new Date();
    const timestamp = now.toISOString().split('T')[1].slice(0, -1); // HH:MM:SS.mmm
    parts.push(`[${timestamp}]`);
  }

  if (prefix) {
    parts.push(prefix);
  }

  parts.push(message);

  return parts.join(' ');
}

/**
 * Default configuration for the debug logger
 */
const DEFAULT_CONFIG: DebugLoggerConfig = {
  enabled: isDevMode(),
  minLevel: 'debug',
  prefix: '',
  includeTimestamp: false,
};

/**
 * Create a debug logger instance.
 *
 * @param config - Optional partial configuration to override defaults
 * @returns A DebugLogger instance
 *
 * @example
 * ```typescript
 * const log = createDebugLogger({ prefix: '[FocusUtils]' });
 * log.debug('Finding element for path:', path);
 * log.group('Search strategies');
 * log.info('Trying data-field-path attribute');
 * log.groupEnd();
 * ```
 */
export function createDebugLogger(
  config?: Partial<DebugLoggerConfig>
): DebugLogger {
  const finalConfig: DebugLoggerConfig = {
    ...DEFAULT_CONFIG,
    ...config,
  };

  /**
   * Check if a log level should be output based on the minimum level
   */
  const shouldLog = (level: LogLevel): boolean => {
    if (!finalConfig.enabled) {
      return false;
    }
    return LOG_LEVEL_VALUES[level] >= LOG_LEVEL_VALUES[finalConfig.minLevel];
  };

  /**
   * Create a log function for a specific level
   */
  const createLogFn =
    (level: LogLevel, consoleFn: (...args: unknown[]) => void) =>
    (message: string, ...args: unknown[]): void => {
      if (!shouldLog(level)) {
        return;
      }
      const formattedMessage = formatLogMessage(
        finalConfig.prefix,
        message,
        finalConfig.includeTimestamp
      );
      consoleFn(formattedMessage, ...args);
    };

  const logger: DebugLogger = {
    debug: createLogFn('debug', console.log),
    info: createLogFn('info', console.info),
    warn: createLogFn('warn', console.warn),
    error: createLogFn('error', console.error),

    group: (label: string): void => {
      if (!finalConfig.enabled) {
        return;
      }
      const formattedLabel = formatLogMessage(
        finalConfig.prefix,
        label,
        finalConfig.includeTimestamp
      );
      console.group(formattedLabel);
    },

    groupEnd: (): void => {
      if (!finalConfig.enabled) {
        return;
      }
      console.groupEnd();
    },

    child: (subPrefix: string): DebugLogger => {
      const newPrefix = finalConfig.prefix
        ? `${finalConfig.prefix}${subPrefix}`
        : subPrefix;
      return createDebugLogger({
        ...finalConfig,
        prefix: newPrefix,
      });
    },
  };

  return logger;
}

/**
 * A no-op logger that can be used when logging is disabled.
 * All methods are no-ops that do nothing.
 */
export const noopLogger: DebugLogger = {
  debug: () => {},
  info: () => {},
  warn: () => {},
  error: () => {},
  group: () => {},
  groupEnd: () => {},
  child: () => noopLogger,
};
