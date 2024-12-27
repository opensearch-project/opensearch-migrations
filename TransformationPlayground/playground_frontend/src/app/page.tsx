"use client";

import React, { useState } from "react";
import "@cloudscape-design/global-styles/index.css";
import { Box, Button, Container, FormField, Grid, Input, Modal, Select,
  SelectProps, SpaceBetween, Spinner, Textarea } from "@cloudscape-design/components";

import { Configuration, TransformsApi, TransformsIndexCreateRequest, TransformsIndexTestRequest,
  SourceVersionEnum, TargetVersionEnum, TransformLanguageEnum
 } from '../generated-api-client';


const TransformationPage = () => {
  // States for page elements
  const [inputShape, setInputShape] = useState("");
  const [transformLogic, setTransformLogic] = useState("");
  const [outputShape, setOutputShape] = useState("");
  const [validationReport, setValidationReport] = useState("");
  const [testTargetUrl, setTestTargetUrl] = useState("");
  const [isRecommending, setIsRecommending] = useState(false);
  const [isTesting, setIsTesting] = useState(false);
  const [userGuidanceVisible, setUserGuidanceVisible] = useState(false);
  const [userGuidance, setUserGuidance] = useState("");
  const [userGuidanceTemp, setUserGuidanceTemp] = useState("");
  
  // Select options for dropdowns using enumerated types
  const sourceVersionOptions: SelectProps.Options = Object.values(SourceVersionEnum).map((value) => ({
    label: value,
    value,
  }));
  const targetVersionOptions: SelectProps.Options = Object.values(TargetVersionEnum).map((value) => ({
    label: value,
    value,
  }));
  const transformLanguageOptions: SelectProps.Options = Object.values(TransformLanguageEnum).map((value) => ({
    label: value,
    value,
  }));
  const transformTypeOptions: SelectProps.Options = [{ label: "Index", value: "Index" }];

  const [sourceVersion, setSourceVersion] = useState<SelectProps.Option>(sourceVersionOptions[0]);
  const [targetVersion, setTargetVersion] = useState<SelectProps.Option>(targetVersionOptions[0]);
  const [transformType, setTransformType] = useState<SelectProps.Option>(transformTypeOptions[0]);
  const [transformLanguage, setTransformLanguage] = useState<SelectProps.Option>(
    transformLanguageOptions[0]
  );

  // API Configuration
  const apiConfig = new Configuration({ basePath: "http://localhost:8000" });
  const apiClient = new TransformsApi(apiConfig);

  // Handle Get Recommendation Request
  const handleGetRecommendation = async () => {
    setIsRecommending(true); // Start visual spinner

    try {
      const parsedInputShape = JSON.parse(inputShape);

      const payload = {
        input_shape: {
          index_name: parsedInputShape.index_name,
          index_json: parsedInputShape.index_json,
        },
        source_version: sourceVersion.value as SourceVersionEnum,
        target_version: targetVersion.value as TargetVersionEnum,
        transform_language: transformLanguage.value as TransformLanguageEnum,
        // Add optional fields
        ...(transformLogic && { transform_logic: transformLogic }),
        ...(testTargetUrl && { test_target_url: testTargetUrl }),
        ...(userGuidance && { user_guidance: userGuidance }),
      };

      const response = await apiClient.transformsIndexCreateCreate(payload);

      // Update state with response data
      setTransformLogic(response.data.transform_logic || "");
      setOutputShape(JSON.stringify(response.data.output_shape, null, 4) || ""); // Pretty print JSON
      setValidationReport((response.data.validation_report || []).join("\n\n"));
    } catch (error) {
      console.error("Error while fetching recommendation:", error);
      alert("Failed to fetch recommendation. Please check the input.");
    } finally {
      setIsRecommending(false); // Stop visual spinner
    }
  };

  // Handle Get Recommendation Request
  const handleTestTransform = async () => {
    setIsTesting(true); // Start visual spinner

    try {
      const parsedInputShape = JSON.parse(inputShape);

      const payload: TransformsIndexTestRequest = {
        input_shape: {
          index_name: parsedInputShape.index_name,
          index_json: parsedInputShape.index_json,
        },
        source_version: sourceVersion.value as SourceVersionEnum,
        target_version: targetVersion.value as TargetVersionEnum,
        transform_language: transformLanguage.value as TransformLanguageEnum,
        transform_logic: transformLogic,
        ...(testTargetUrl && { test_target_url: testTargetUrl }), // Add optional field
      };

      const response = await apiClient.transformsIndexTestCreate(payload);

      // Update state with response data
      setOutputShape(JSON.stringify(response.data.output_shape, null, 4) || ""); // Pretty print JSON
      setValidationReport((response.data.validation_report || []).join("\n\n"));
    } catch (error) {
      console.error("Error while testing transformation:", error);
      alert("Failed to test transformation. Please check the input.");
    } finally {
      setIsTesting(false); // Stop visual spinner
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
          {isRecommending ? <Spinner/> : "Get GenAI Recommendation"}
        </Button>
        <Button onClick={() => {
          setUserGuidanceVisible(true);
          setUserGuidanceTemp(userGuidance);
        }}>
          Set User Guidance
        </Button>
        <Modal
          onDismiss={() => setUserGuidanceVisible(false)}
          visible={userGuidanceVisible}
          footer={
            <Box float="right">
              <SpaceBetween direction="horizontal" size="xs">
                <Button variant="link" onClick={() => {
                  setUserGuidanceVisible(false);
                }}>
                  Cancel
                </Button>
                <Button variant="primary" onClick={() => {
                  setUserGuidanceVisible(false);
                  setUserGuidance(userGuidanceTemp);
                }}>
                  Set
                </Button>
              </SpaceBetween>
            </Box>
          }
          header="Modal title"
        >
          <FormField
            label="Form field label"
            description="This is a description"
          >
            <Textarea
              value={userGuidanceTemp}
              onChange={({ detail }) => setUserGuidanceTemp(detail.value)}
              placeholder='Add any special instructions or guidance for the LLM here...'
              rows={25}
            />
          </FormField>
        </Modal>
        <Button onClick={() => {
          setTransformLogic("");
          setOutputShape("");
          setValidationReport("");
        }}>
          Clear Transform
        </Button>
        <FormField label="Transformation Logic">
          <Textarea
            value={transformLogic}
            onChange={({ detail }) => setTransformLogic(detail.value)}
            placeholder="View/edit Transformation Logic in here."
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
        <Button onClick={handleTestTransform}>
          {isTesting ? <Spinner/> : "Test Transform"}
        </Button>
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
