"use client";

import React, { useState } from "react";
import "@cloudscape-design/global-styles/index.css";
import { Grid, Container, FormField, Textarea, Button, Input, Select, SelectProps, Spinner } from "@cloudscape-design/components";

const TransformationPage = () => {
  // States for user inputs
  const [inputShape, setInputShape] = useState("");
  const [transformLogic, setTransformLogic] = useState("");
  const [outputShape, setOutputShape] = useState("");
  const [validationReport, setValidationReport] = useState("");
  const [testTargetUrl, setTestTargetUrl] = useState("");
  const [isLoading, setIsLoading] = useState(false);

  // Select options for dropdowns
  const sourceVersionOptions: SelectProps.Options = [{ label: "Elasticsearch 6.8", value: "Elasticsearch 6.8" }];
  const targetVersionOptions: SelectProps.Options = [{ label: "OpenSearch 2.17", value: "OpenSearch 2.17" }];
  const transformTypeOptions: SelectProps.Options = [{ label: "Index", value: "Index" }];
  const transformLanguageOptions: SelectProps.Options = [{ label: "Python", value: "Python" }];

  const [sourceVersion, setSourceVersion] = useState<SelectProps.Option>(sourceVersionOptions[0]);
  const [targetVersion, setTargetVersion] = useState<SelectProps.Option>(targetVersionOptions[0]);
  const [transformType, setTransformType] = useState<SelectProps.Option>(transformTypeOptions[0]);
  const [transformLanguage, setTransformLanguage] = useState<SelectProps.Option>(
    transformLanguageOptions[0]
  );

  // Define the request body for GenAI Recommendations
  interface GetRecommendationBody {
    input_shape: any;
    source_version: string;
    target_version: string;
    transform_language: string;
    test_target_url?: string;
  }

  // Handle Get Recommendation Request
  const handleGetRecommendation = async () => {
    setIsLoading(true); // Start visual spinner

    // Parse the input_shape string into JSON
    const parsedInputShape = JSON.parse(inputShape);

    const payload: GetRecommendationBody = {
      input_shape: parsedInputShape,
      source_version: sourceVersion.value!,
      target_version: targetVersion.value!,
      transform_language: transformLanguage.value!
    };

    // Add optional fields to payload
    if (testTargetUrl && testTargetUrl.length > 0) {
      payload.test_target_url = testTargetUrl;
    }

    try {
      const response = await fetch("http://localhost:8000/transforms/index/", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      if (response.ok) {
        const data = await response.json();

        // Pretty-print JSON for output_shape
        const prettyOutputShape = JSON.stringify(data.output_shape, null, 4);

        // Join validation_report with new lines
        const formattedValidationReport = (data.validation_report || []).join("\n\n");

        setTransformLogic(data.transform_logic || "");
        setOutputShape(prettyOutputShape || "");
        setValidationReport(formattedValidationReport || "");
      } else {
        console.error("Failed to fetch recommendation");
      }
    } catch (error) {
      console.error("Error:", error);

      if (error instanceof SyntaxError) {
        alert("Invalid JSON: Please check your input and try again.");
      }
    } finally {
      setIsLoading(false); // Stop visual spinner
    }
  };

  return (
    <Grid
      gridDefinition={[
        { colspan: 4 }, // Left Column
        { colspan: 4 }, // Middle Column
        { colspan: 4 }, // Right Column
      ]}
    >
      {/* User Input Column */}
      <Container header="User Input Panel">
        <FormField
          label="Original JSON Input"
          description="Paste or edit the JSON blob that represents the Elasticsearch 6.8 Index JSON."
        >
          <Textarea
            value={inputShape}
            onChange={({ detail }) => setInputShape(detail.value)}
            placeholder='{"index_name": "my-index", "index_json": { index settings and mappings }}'
            rows={30}
          />
        </FormField>
      </Container>

      {/* Transformation Column */}
      <Container header="Transformation Panel">
        <FormField label="Transformation Settings">
          <Grid
            gridDefinition={[
              { colspan: 6 }, // Source Version Dropdown
              { colspan: 6 }, // Target Version Dropdown
              { colspan: 6 }, // Transform Type Dropdown
              { colspan: 6 }, // Transform Language Dropdown
            ]}
          >
            <Select
              selectedOption={sourceVersion}
              onChange={({ detail }) => setSourceVersion(detail.selectedOption!)} 
              options={sourceVersionOptions}
              placeholder="Select Source Version"
            />
            <Select
              selectedOption={targetVersion}
              onChange={({ detail }) => setTargetVersion(detail.selectedOption!)}
              options={targetVersionOptions}
              placeholder="Select Target Version"
            />
            <Select
              selectedOption={transformType}
              onChange={({ detail }) => setTransformType(detail.selectedOption!)}
              options={transformTypeOptions}
              placeholder="Select Transform Type"
            />
            <Select
              selectedOption={transformLanguage}
              onChange={({ detail }) => setTransformLanguage(detail.selectedOption!)}
              options={transformLanguageOptions}
              placeholder="Select Language"
            />
          </Grid>
        </FormField>
        <br />
        <Button onClick={handleGetRecommendation}>
          {isLoading ? <Spinner/> : "Get GenAI Recommendation"}
        </Button>
        <Button onClick={() => {
          setTransformLogic("");
          setOutputShape("");
          setValidationReport("");
        }}>
          Clear Transform
        </Button>
        <FormField label="Generated Transformation Logic">
          <Textarea
            value={transformLogic}
            readOnly
            placeholder="The Python code for the transformation will appear here."
            rows={30}
          />
        </FormField>
      </Container>

      {/* Testing/Output Column */}
      <Container header="Testing & Output Panel">
        <FormField
          label="Test Target URL"
          description="Enter the URL of an OpenSearch cluster for testing (optional)."
        >
          <Input
            value={testTargetUrl}
            onChange={({ detail }) => setTestTargetUrl(detail.value)}
            placeholder="https://example.com"
          />
        </FormField>
        <br />
        <FormField label="Transformed JSON Output">
          <Textarea
            value={outputShape}
            readOnly
            placeholder="The transformed JSON output will appear here."
            rows={30}
          />
        </FormField>
        <br />
        <FormField label="Validation Logs">
          <Textarea
            value={validationReport}
            readOnly
            placeholder="Validation logs will appear here."
            rows={15}
          />
        </FormField>
      </Container>
    </Grid>
  );
};

export default TransformationPage;
