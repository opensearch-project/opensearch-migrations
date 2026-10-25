import { describe, expect, it } from "vitest";

import { humanizeFieldLabel } from "./fieldLabels";


describe("humanizeFieldLabel", () => {
  it("turns camel-case field names into title-case labels", () => {
    expect(humanizeFieldLabel("endpoint")).toBe("Endpoint");
    expect(humanizeFieldLabel("fromSnapshot")).toBe("From Snapshot");
    expect(humanizeFieldLabel("metadataMigrationConfig"))
      .toBe("Metadata Migration Config");
  });

  it("preserves familiar technical terms", () => {
    expect(humanizeFieldLabel("awsRegion")).toBe("AWS Region");
    expect(humanizeFieldLabel("repoPathUri")).toBe("Repo Path URI");
    expect(humanizeFieldLabel("sourceAuthSigv4Service"))
      .toBe("Source Auth SigV4 Service");
  });

  it("preserves authored labels and normalizes snake case", () => {
    expect(humanizeFieldLabel("From snapshot")).toBe("From snapshot");
    expect(humanizeFieldLabel("logging_configuration_override"))
      .toBe("Logging Configuration Override");
  });
});
