export type LogSeverity = "error" | "warning" | null;


const STRUCTURED_LEVEL = /(?:^|[\s,{])["']?(?:level|lvl|severity)["']?\s*[:=]\s*["']?([a-z]+)/i;
const ERROR_PREFIX = /^\s*(?:\[[^\]]+\]\s*)?(?:error|err|fatal|critical|panic|timed\s+out|timeout)(?:\b|\s*:)/i;
const WARNING_PREFIX = /^\s*(?:\[[^\]]+\]\s*)?(?:warning|warn)(?:\b|\s*:)/i;


export function classifyLogSeverity(
  message: string,
  eventKind = "log",
): LogSeverity {
  if (eventKind === "error") return "error";

  const structured = STRUCTURED_LEVEL.exec(message)?.[1]?.toLowerCase();
  if (structured) {
    if (["error", "err", "fatal", "critical", "panic"].includes(structured)) {
      return "error";
    }
    if (["warning", "warn"].includes(structured)) return "warning";
    // An explicit INFO/DEBUG/TRACE level outranks words in message fields.
    return null;
  }

  if (ERROR_PREFIX.test(message)) return "error";
  if (WARNING_PREFIX.test(message)) return "warning";
  return null;
}
