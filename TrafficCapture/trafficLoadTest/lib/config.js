/**
 * Load-profile presets, read from the k6-config/*.env files baked into the image next to the
 * scenarios.
 *
 * A run picks one by name with K6_PRESET (e.g. K6_PRESET=ingest-steady). Every value it sets is
 * still overridable per run by a real environment variable (`k6 run -e KEY=VALUE`, or a TestRun's
 * runner.env), because __ENV is merged last. Scenarios therefore read CFG.X instead of __ENV.X;
 * their `|| default` fallbacks still apply when neither the preset nor the environment sets a key.
 *
 * Every preset is open()ed unconditionally and by literal path. k6 resolves open() at init time and
 * the k6-operator's initializer bundles whatever was opened into the archive its runner pods
 * execute — a computed path would bake in only the preset the *initializer* happened to see, and
 * the runners could never select another one. Ten small files cost nothing in the archive.
 */

const PRESET_FILES = {
  'ingest-steady':      open('../k6-config/ingest-steady.env'),
  'ingest-ramp':        open('../k6-config/ingest-ramp.env'),
  'ingest-burst':       open('../k6-config/ingest-burst.env'),
  'search-steady':      open('../k6-config/search-steady.env'),
  'search-deep-paging': open('../k6-config/search-deep-paging.env'),
  'search-ramp':        open('../k6-config/search-ramp.env'),
  'search-burst':       open('../k6-config/search-burst.env'),
  'mixed-steady':       open('../k6-config/mixed-steady.env'),
  'mixed-ramp':         open('../k6-config/mixed-ramp.env'),
  'mixed-burst':        open('../k6-config/mixed-burst.env'),
};

/** KEY=VALUE lines. Blank lines and '#' comments are skipped; only the first '=' splits, so the
 * JSON in RAMP_STAGES survives intact. Values are taken verbatim (no quote stripping). */
function parseEnvFile(text) {
  const vars = {};
  for (const line of text.split('\n')) {
    const trimmed = line.trim();
    const eq = trimmed.indexOf('=');
    if (!trimmed || trimmed.startsWith('#') || eq < 1) continue;
    vars[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim();
  }
  return vars;
}

function presetVars(name) {
  if (!name) return {};  // no preset selected — scenario defaults only
  const text = PRESET_FILES[name];
  if (text === undefined) {
    throw new Error(`unknown K6_PRESET '${name}'; available: ` +
                    Object.keys(PRESET_FILES).sort().join(', '));
  }
  return parseEnvFile(text);
}

/** Effective run configuration: the selected preset, with environment variables winning over it. */
export const CFG = Object.freeze({ ...presetVars(__ENV.K6_PRESET), ...__ENV });

/** Preset names this image ships — the source of truth for what K6_PRESET accepts. */
export const PRESET_NAMES = Object.keys(PRESET_FILES).sort();
