import * as fs from "fs";
import * as path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RESULTS_DIR = "/tmp/parity-results";
const OUTPUT_PATH = path.join(__dirname, "../artifacts/parity-catalog.md");

export default class ParityReporter {
  constructor() {
    this.builderSpecTitles = new Set();
    this.builderFailureText = "";
  }

  onRunStart() {
    if (fs.existsSync(RESULTS_DIR)) {
      fs.rmSync(RESULTS_DIR, { recursive: true });
    }
    fs.mkdirSync(RESULTS_DIR, { recursive: true });
  }

  onRunComplete(_contexts, _results) {
    this.builderSpecTitles = this.collectBuilderSpecTitles(_results);
    this.builderFailureText = this.collectBuilderFailureText(_results);
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
    md += `- Total Builder Variants: ${stats.totalVariants}\n`;
    md += `- Errors Expected: ${stats.errorsExpected}\n\n`;

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
    const testCase = spec.name;
    const argoExpr = `\`${spec.argoExpression}\``;
    const inputs = spec.inputs ? `\`${JSON.stringify(spec.inputs)}\`` : "-";
    const expected = spec.expectedPhase === "Error"
      ? "Error"
      : spec.expectedResult ? `\`"${spec.expectedResult}"\`` : "-";

    let argoCol = "-";
    if (entry.contract) {
      const passed = spec.expectedPhase === "Error"
        ? entry.contract.phase === "Error"
        : entry.contract.phase === "Succeeded" &&
          entry.contract.result === spec.expectedResult;
      argoCol = passed
        ? (spec.expectedPhase === "Error" ? "✅ Error" : `✅ \`"${entry.contract.result}"\``)
        : `❌ \`"${entry.contract.result}"\` (${entry.contract.phase})`;
    }

    let builderCol = "-";
    const variants = entry.parityVariants || [];
    const hasBuilderAssertion = this.hasBuilderAssertionForSpec(spec);
    if (variants.length === 0) {
      builderCol = hasBuilderAssertion
        ? "❌ Builder test exists but failed before result capture"
        : "⚠️ No builder support";
    } else {
      const variantLines = variants.map(v => {
        const passed = spec.expectedPhase === "Error"
          ? v.result.phase === "Error"
          : v.result.phase === "Succeeded" &&
            v.result.result === spec.expectedResult;
        const status = passed ? "✅" : "❌";
        const resultStr = spec.expectedPhase === "Error"
          ? "Error"
          : `\`"${v.result.result}"\``;
        return `${status} **${v.variant.name}**: \`${v.variant.code}\` → ${resultStr}`;
      });
      builderCol = variantLines.join("<br>");
    }

    let parityCol = "-";
    if (entry.contract && variants.length > 0) {
      const allPass = variants.every(v =>
        v.result.result === entry.contract.result &&
        v.result.phase === entry.contract.phase
      );
      const somePass = variants.some(v =>
        v.result.result === entry.contract.result &&
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
      parityCol = hasBuilderAssertion ? "❌" : "⚠️";
    }

    return `| ${testCase} | ${argoExpr} | ${inputs} | ${expected} | ${argoCol} | ${builderCol} | ${parityCol} |`;
  }

  collectBuilderSpecTitles(results) {
    const titles = new Set();
    for (const suite of results?.testResults || []) {
      for (const assertion of suite.assertionResults || []) {
        const ancestors = assertion?.ancestorTitles || [];
        if (ancestors.length >= 2 && String(ancestors[1]).startsWith("Builder - ")) {
          titles.add(String(ancestors[0]));
        }
      }
    }
    return titles;
  }

  hasBuilderAssertionForSpec(spec) {
    const title = `${spec.category} - ${spec.name}`;
    if (this.builderSpecTitles.has(title)) return true;
    return this.builderFailureText.includes(`${title} › Builder -`);
  }

  collectBuilderFailureText(results) {
    return (results?.testResults || [])
      .map(suite => suite?.failureMessage || "")
      .join("\n");
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
    let total = 0, fullParity = 0, partialParity = 0, contractOnly = 0, brokenParity = 0, errorsExpected = 0, totalVariants = 0;
    for (const entry of entries) {
      total++;
      if (entry.spec.expectedPhase === "Error") errorsExpected++;
      
      const variants = entry.parityVariants || [];
      totalVariants += variants.length;
      
      if (entry.contract && variants.length > 0) {
        const allPass = variants.every(v =>
          v.result.result === entry.contract.result &&
          v.result.phase === entry.contract.phase
        );
        const somePass = variants.some(v =>
          v.result.result === entry.contract.result &&
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
        contractOnly++;
      }
    }
    return { total, fullParity, partialParity, contractOnly, brokenParity, errorsExpected, totalVariants };
  }
}
