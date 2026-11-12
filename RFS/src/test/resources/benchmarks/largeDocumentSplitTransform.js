function main(context) {
  const configuredModulo = Number(context.get("sampleModulo"));
  const sampleModulo = Number.isFinite(configuredModulo) && configuredModulo > 0
    ? configuredModulo
    : 1;
  const selectedIdValues = context.get("selectedIds");
  const selectedIds = new Set(
    selectedIdValues == null ? [] : Array.from(selectedIdValues, value => String(value))
  );
  const configuredMaxExpanded = Number(context.get("maxExpandedPerInvocation"));
  const maxExpandedPerInvocation = Number.isFinite(configuredMaxExpanded) && configuredMaxExpanded > 0
    ? Math.floor(configuredMaxExpanded)
    : Number.POSITIVE_INFINITY;
  const configuredForceCreate = context.get("forceCreate");
  const forceCreate = configuredForceCreate == null ? true : Boolean(configuredForceCreate);
  const targetBytes = Number(context.get("targetBytes"));
  const valueBytes = Number(context.get("valueBytes"));
  const containerCount = Number(context.get("containerCount"));
  const nestingDepth = Number(context.get("nestingDepth"));
  const profile = String(context.get("profile"));
  const targetIndex = String(context.get("targetIndex"));
  const leafValue = "x".repeat(valueBytes);

  function hashId(id) {
    let hash = 0x811c9dc5;
    for (let i = 0; i < id.length; ++i) {
      hash ^= id.charCodeAt(i);
      hash = Math.imul(hash, 0x01000193);
    }
    return hash >>> 0;
  }

  function padded(prefix, value, width) {
    return prefix + String(value).padStart(width, "0");
  }

  function makePayload(idHash, payloadBytes) {
    const payload = {
      profile: profile,
      sample_hash: idHash,
      target_bytes: payloadBytes
    };
    if (payloadBytes <= 0) {
      return payload;
    }

    // Each entry costs valueBytes plus approximately 25 bytes of JSON syntax and key text.
    // Calibration tests measure the final serialized size and the benchmark run supplies the
    // adjusted targetBytes when tighter sizing is needed.
    const leafCount = Math.floor(payloadBytes / (valueBytes + 25));
    const groups = {};
    payload.groups = groups;
    let leaf = 0;
    for (let group = 0; group < containerCount && leaf < leafCount; ++group) {
      let cursor = {};
      groups[padded("group_", group, 4)] = cursor;
      for (let depth = 0; depth < nestingDepth; ++depth) {
        const child = {};
        cursor[padded("level_", depth, 2)] = child;
        cursor = child;
      }
      const groupEnd = Math.ceil((group + 1) * leafCount / containerCount);
      while (leaf < groupEnd) {
        cursor[padded("key_", leaf, 7)] = leafValue;
        ++leaf;
      }
    }
    return payload;
  }

  return documents => {
    let expandedDocuments = 0;
    return documents.map(document => {
      if (document.operation_type !== "index") {
        return document;
      }
      const id = String(document.operation._id);
      const idHash = hashId(id);

      // Target the benchmark index.  Verb changes are optional so expansion benchmarks can retain
      // idempotent index semantics while a separate benchmark exercises create-only behavior.
      document.operation._index = targetIndex;
      if (forceCreate) {
        document.operation.op_type = "create";
        delete document.operation.version;
        delete document.operation.version_type;
        delete document.operation.if_seq_no;
        delete document.operation.if_primary_term;
      }

      const existingPayload = document.document.benchmark_payload;
      const alreadyExpanded = existingPayload != null
        && Number(existingPayload.target_bytes) > 0;
      const selectedById = targetBytes > 0 && (
        selectedIds.size > 0 ? selectedIds.has(id) : idHash % sampleModulo === 0
      );
      const selected = selectedById && expandedDocuments < maxExpandedPerInvocation;
      if (!alreadyExpanded) {
        document.document.benchmark_payload = makePayload(
          selected ? idHash : -1,
          selected ? targetBytes : 0
        );
        if (selected) {
          ++expandedDocuments;
        }
      }
      return document;
    });
  };
}

(() => main)();
