import * as fs from "fs";
import * as path from "path";
import { fileURLToPath } from "url";
import { isDeepStrictEqual } from "util";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RESULTS_DIR = "/tmp/parity-results";
const OUTPUT_PATH = path.join(__dirname, "../artifacts/parity-catalog.md");

export default class ParityReporter {
  constructor() {
    this.builderMetaBySpec = new Map();
  }

  onRunStart() {
    if (fs.existsSync(RESULTS_DIR)) {
      fs.rmSync(RESULTS_DIR, { recursive: true, force: true });
    }
    fs.mkdirSync(RESULTS_DIR, { recursive: true });
  }

  onRunComplete(_contexts, _results) {
    this.builderMetaBySpec = this.collectBuilderMeta(_results);
    const entries = this.readAllResults();
    if (entries.length === 0) return;

    const markdown = this.generateMarkdown(entries);
    fs.mkdirSync(path.dirname(OUTPUT_PATH), { recursive: true });
    fs.writeFileSync(OUTPUT_PATH, markdown);
    console.log(`\nParity catalog written to ${OUTPUT_PATH}`);
  }

  readAllResults() {
    if (!fs.existsSync(RESULTS_DIR)) return [];
    const files = fs.readdirSync(RESULTS_DIR).filter(f => f.endsWith(".json"));
    return files.map(f =>
      JSON.parse(fs.readFileSync(path.join(RESULTS_DIR, f), "utf8"))
    );
  }

  generateMarkdown(entries) {
    const grouped = this.groupByCategory(entries);
    const stats = this.computeStats(entries);

    let md = `# Argo Workflow Builder Catalog\n`;
    md += `Generated: ${new Date().toISOString()}\n\n`;
    md += `## Summary\n`;
    md += `- Total Test Cases: ${stats.total}\n`;
    md += `- Full Parity: ${stats.fullParity} ✅\n`;
    md += `- Partial Parity: ${stats.partialParity} ⚠️\n`;
    md += `- Contract Only: ${stats.contractOnly} ⚠️\n`;
    md += `- Broken Parity: ${stats.brokenParity} ❌\n`;
    md += `- Known Broken (Skipped): ${stats.knownBrokenSkipped} 🚧\n`;
    md += `- Total Builder Variants: ${stats.totalVariants}\n`;
    md += `- Errors Expected: ${stats.errorsExpected}\n\n`;
    md += `- Report Mode: ${process.env.PARITY_INCLUDE_BROKEN === "1" ? "include broken tests" : "default (broken tests skipped)"}\n\n`;

    for (const [category, items] of Object.entries(grouped)) {
      md += `## ${category}\n\n`;
      md += `| Test Case | Argo Expression | Inputs | Expected | Argo | Builder Variants | Parity |\n`;
      md += `|-----------|----------------|--------|----------|------|------------------|--------|\n`;

      for (const entry of items) {
        md += this.renderRow(entry) + "\n";
      }
      md += "\n";
    }

    md += `## Legend\n`;
    md += `- ✅ Pass — test passed, result matches expected\n`;
    md += `- ❌ Fail — test failed or result doesn't match\n`;
    md += `- ⚠️ No builder support — Argo feature has no builder API equivalent\n`;
    md += `- ⚠️ Partial — some builder variants pass, others fail\n`;
    md += `- \\- Not tested\n`;

    return md;
  }

  renderRow(entry) {
    const spec = entry.spec;
    const testCase = this.formatCell(spec.name, 80, false);
    const argoExpr = this.formatCodeCell(spec.argoExpression, 120);
    const inputs = spec.inputs ? this.formatCodeCell(JSON.stringify(spec.inputs), 140) : "-";
    const expected = spec.expectedPhase === "Error"
      ? "Error"
      : spec.expectedResult ? this.formatCodeCell(`"${spec.expectedResult}"`, 90) : "-";

    const isPhaseOnlyExpectation = spec.expectedPhase !== "Error" && spec.expectedResult === undefined;

    let argoCol = "-";
    if (entry.contract) {
      const passed = spec.expectedPhase === "Error"
        ? entry.contract.phase === "Error"
        : isPhaseOnlyExpectation
          ? entry.contract.phase === "Succeeded"
          : entry.contract.phase === "Succeeded" &&
            this.valuesEquivalent(entry.contract.result, spec.expectedResult);
      if (passed) {
        argoCol = spec.expectedPhase === "Error"
          ? "✅ Error"
          : isPhaseOnlyExpectation
            ? `✅ (${entry.contract.phase})`
            : `✅ ${this.formatCodeCell(`"${entry.contract.result}"`, 90)}`;
      } else {
        const reason = this.expectedVsActualReason(spec, entry.contract);
        const msg = entry.contract.message ? `<br><sub>${this.formatCell(`message: ${entry.contract.message}`, 140, false)}</sub>` : "";
        argoCol = `❌ ${this.formatCodeCell(`"${entry.contract.result}"`, 90)} (${entry.contract.phase})<br><sub>${reason}</sub>${msg}`;
      }
    }

    let builderCol = "-";
    const variants = entry.parityVariants || [];
    const knownBrokenVariants = entry.knownBrokenVariants || [];
    const builderMeta = this.getBuilderMeta(spec);
    const hasBuilderAssertion = builderMeta.hasBuilder;
    if (variants.length === 0) {
      if (knownBrokenVariants.length > 0) {
        builderCol = knownBrokenVariants
          .map(v => `🚧 **${this.formatCell(v.variant.name, 40, false)}** (skipped by default): ${this.formatCell(v.reason, 140, false)}`)
          .join("<br>");
      } else if (builderMeta.hasKnownBrokenSkip) {
        builderCol = "🚧 Known broken builder test (skipped). Run with `PARITY_INCLUDE_BROKEN=1`.";
      } else if (builderMeta.hasFailed) {
        builderCol = builderMeta.failedDetails
          ? `❌ Builder test failed before result capture<br><sub>${this.formatCell(builderMeta.failedDetails, 160, false)}</sub>`
          : "❌ Builder test failed before result capture";
      } else if (hasBuilderAssertion) {
        builderCol = "⚠️ Builder test exists but no parity result was captured";
      } else {
        builderCol = "⚠️ No builder support";
      }
    } else {
      const variantLines = variants.map(v => {
        const passed = spec.expectedPhase === "Error"
          ? v.result.phase === "Error"
          : isPhaseOnlyExpectation
            ? v.result.phase === "Succeeded"
            : v.result.phase === "Succeeded" &&
              this.valuesEquivalent(v.result.result, spec.expectedResult);
        const status = passed ? "✅" : "❌";
        const resultStr = spec.expectedPhase === "Error"
          ? "Error"
          : isPhaseOnlyExpectation
            ? `(${v.result.phase})`
            : this.formatCodeCell(`"${v.result.result}"`, 90);
        const reason = passed ? "" : `<br><sub>${this.expectedVsActualReason(spec, v.result)}</sub>${
          v.result.message ? `<br><sub>${this.formatCell(`message: ${v.result.message}`, 140, false)}</sub>` : ""
        }`;
        return `${status} **${this.formatCell(v.variant.name, 40, false)}**: ${this.formatCodeCell(v.variant.code, 120)} → ${resultStr}${reason}`;
      });
      builderCol = variantLines.join("<br>");
    }

    let parityCol = "-";
    if (entry.contract && variants.length > 0) {
      const allPass = variants.every(v =>
        this.valuesEquivalent(v.result.result, entry.contract.result) &&
        v.result.phase === entry.contract.phase
      );
      const somePass = variants.some(v =>
        this.valuesEquivalent(v.result.result, entry.contract.result) &&
        v.result.phase === entry.contract.phase
      );
      if (allPass) {
        parityCol = "✅";
      } else if (somePass) {
        parityCol = "⚠️ Partial";
      } else {
        parityCol = "❌";
      }
    } else if (entry.contract && variants.length === 0) {
      if (knownBrokenVariants.length > 0 || builderMeta.hasKnownBrokenSkip) {
        parityCol = "🚧 Skipped";
      } else {
        parityCol = (hasBuilderAssertion ? "❌" : "⚠️");
      }
    }

    return `| ${testCase} | ${argoExpr} | ${inputs} | ${expected} | ${argoCol} | ${builderCol} | ${parityCol} |`;
  }

  collectBuilderMeta(results) {
    const bySpec = new Map();
    for (const suite of results?.testResults || []) {
      for (const assertion of suite.assertionResults || []) {
        const ancestors = assertion?.ancestorTitles || [];
        if (ancestors.length >= 2 && String(ancestors[1]).includes("Builder -")) {
          const specTitle = String(ancestors[0]);
          const existing = bySpec.get(specTitle) || {
            hasBuilder: false,
            hasFailed: false,
            hasKnownBrokenSkip: false,
            failedDetails: "",
          };
          existing.hasBuilder = true;
          if (assertion.status === "failed") {
            existing.hasFailed = true;
            if (!existing.failedDetails && Array.isArray(assertion.failureMessages) && assertion.failureMessages.length > 0) {
              const first = String(assertion.failureMessages[0]).split("\n").slice(0, 2).join(" ");
              existing.failedDetails = first;
            }
          }
          const isBrokenTagged =
            ancestors.some(a => String(a).includes("[broken]")) ||
            String(assertion.title || "").includes("[broken]");
          if (assertion.status === "pending" && isBrokenTagged) {
            existing.hasKnownBrokenSkip = true;
          }
          bySpec.set(specTitle, existing);
        }
      }
    }
    return bySpec;
  }

  getBuilderMeta(spec) {
    const title = `${spec.category} - ${spec.name}`;
    return this.builderMetaBySpec.get(title) || {
      hasBuilder: false,
      hasFailed: false,
      hasKnownBrokenSkip: false,
      failedDetails: "",
    };
  }

  groupByCategory(entries) {
    const grouped = {};
    for (const entry of entries) {
      const cat = entry.spec.category;
      if (!grouped[cat]) grouped[cat] = [];
      grouped[cat].push(entry);
    }
    return grouped;
  }

  computeStats(entries) {
    let total = 0, fullParity = 0, partialParity = 0, contractOnly = 0, brokenParity = 0, knownBrokenSkipped = 0, errorsExpected = 0, totalVariants = 0;
    for (const entry of entries) {
      total++;
      if (entry.spec.expectedPhase === "Error") errorsExpected++;
      
      const variants = entry.parityVariants || [];
      totalVariants += variants.length;
      
      if (entry.contract && variants.length > 0) {
        const allPass = variants.every(v =>
          this.valuesEquivalent(v.result.result, entry.contract.result) &&
          v.result.phase === entry.contract.phase
        );
        const somePass = variants.some(v =>
          this.valuesEquivalent(v.result.result, entry.contract.result) &&
          v.result.phase === entry.contract.phase
        );
        
        if (allPass) {
          fullParity++;
        } else if (somePass) {
          partialParity++;
        } else {
          brokenParity++;
        }
      } else if (entry.contract && variants.length === 0) {
        const knownBrokenVariants = entry.knownBrokenVariants || [];
        const meta = this.getBuilderMeta(entry.spec);
        if (knownBrokenVariants.length > 0) {
          knownBrokenSkipped++;
        } else if (meta.hasKnownBrokenSkip) {
          knownBrokenSkipped++;
        } else if (meta.hasFailed || meta.hasBuilder) {
          brokenParity++;
        } else {
          contractOnly++;
        }
      }
    }
    return { total, fullParity, partialParity, contractOnly, brokenParity, knownBrokenSkipped, errorsExpected, totalVariants };
  }

  valuesEquivalent(a, b) {
    if (a === b) return true;
    if (typeof a !== "string" || typeof b !== "string") return false;
    try {
      return isDeepStrictEqual(JSON.parse(a), JSON.parse(b));
    } catch {
      return false;
    }
  }

  expectedVsActualReason(spec, result) {
    if (spec.expectedPhase === "Error") {
      return `expected phase Error, got ${result.phase}`;
    }
    if (spec.expectedResult === undefined) {
      return `expected phase Succeeded, got ${result.phase}`;
    }
    if (result.phase !== "Succeeded") {
      return `expected phase Succeeded, got ${result.phase}`;
    }
    return `expected ${this.formatCell(String(spec.expectedResult), 60, false)}, got ${this.formatCell(String(result.result), 60, false)}`;
  }

  formatCodeCell(text, limit = 120) {
    return `\`${this.wrapForCell(this.truncate(this.escapePipes(String(text)), limit))}\``;
  }

  formatCell(text, limit = 120, wrap = true) {
    const t = this.truncate(this.escapePipes(String(text)), limit);
    return wrap ? this.wrapForCell(t) : t;
  }

  truncate(text, limit) {
    return text.length <= limit ? text : `${text.slice(0, Math.max(0, limit - 1))}…`;
  }

  wrapForCell(text) {
    return text.replace(/(.{48})/g, "$1<wbr>").replace(/\n/g, "<br>");
  }

  escapePipes(text) {
    return text.replace(/\|/g, "\\|");
  }
}
