import * as fs from "fs";
import * as path from "path";
import { WorkflowResult } from "./workflowRunner.js";

const RESULTS_DIR = "/tmp/parity-results";

export interface ParitySpec {
  category: string;
  name: string;
  inputs?: Record<string, string>;
  argoExpression: string;
  expectedResult?: string;
  expectedPhase?: string;
}

export interface BuilderVariant {
  name: string;
  code: string;
}

export interface WhenParitySpec extends ParitySpec {
  whenCondition: string;
  expectedNodePhase: string;
}

export interface LoopParitySpec extends ParitySpec {
  expectedIterations: number;
  expectedItems: string[];
}

function resultFilePath(spec: ParitySpec): string {
  const key = `${spec.category}::${spec.name}`.replace(/[^a-zA-Z0-9_-]/g, "_");
  return path.join(RESULTS_DIR, `${key}.json`);
}

function readOrCreate(spec: ParitySpec): any {
  const filePath = resultFilePath(spec);
  fs.mkdirSync(RESULTS_DIR, { recursive: true });
  if (fs.existsSync(filePath)) {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
  }
  return { spec, parityVariants: [] };
}

function writeResult(spec: ParitySpec, data: any) {
  fs.writeFileSync(resultFilePath(spec), JSON.stringify(data, null, 2));
}

export function reportContractResult(spec: ParitySpec, result: WorkflowResult) {
  const data = readOrCreate(spec);
  data.contract = {
    phase: result.phase,
    result: result.globalOutputs?.result ?? null,
    message: result.message ?? null,
  };
  writeResult(spec, data);
}

export function reportParityResult(
  spec: ParitySpec,
  variant: BuilderVariant,
  result: WorkflowResult
) {
  const data = readOrCreate(spec);
  if (!data.parityVariants) {
    data.parityVariants = [];
  }
  data.parityVariants.push({
    variant,
    result: {
      phase: result.phase,
      result: result.globalOutputs?.result ?? null,
      message: result.message ?? null,
    },
  });
  writeResult(spec, data);
}
