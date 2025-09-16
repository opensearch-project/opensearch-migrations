"use client";

import { useState, useEffect } from "react";
import { ClusterDebugControls } from "./debug/ClusterDebugControls";
import { CLUSTER_SCENARIOS } from "./mockData/clusterScenarios";
import FormField from "@cloudscape-design/components/form-field";
import Input from "@cloudscape-design/components/input";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import RadioGroup from "@cloudscape-design/components/radio-group";
import { AuthModelType, ClusterInfo, SigV4Auth } from "@/generated/api";
import {
  Alert,
  Checkbox,
  StatusIndicator,
} from "@cloudscape-design/components";

// Define BasicAuthArn interface since it's not exported from the API
interface BasicAuthArn {
  type: string;
  user_secret_arn: string;
}

interface RemoteClusterProps {
  readonly clusterType: "source" | "target";
  readonly isLoading: boolean;
  readonly cluster: ClusterInfo | null | undefined;
  readonly error: Error | string | null | undefined;
  readonly alwaysDisplayVersionOverride: boolean;
}

export default function RemoteCluster({
  clusterType,
  isLoading: apiLoading,
  cluster: apiClusterData,
  error: apiError,
  alwaysDisplayVersionOverride = false,
}: RemoteClusterProps) {
  const [selectedAuthType, setSelectedAuthType] =
    useState<AuthModelType | null>(null);
  const [displayVersionOverride, setDisplayVersionOverride] =
    useState<boolean>(false);

  const [debugData, setDebugData] = useState<ClusterInfo | null>(null);
  const [isLoading, setIsLoading] = useState(apiLoading);
  const [cluster, setCluster] = useState<ClusterInfo | null | undefined>(
    apiClusterData,
  );
  const [error, setError] = useState(apiError);

  // Update data when API data changes, unless we're using debug data
  useEffect(() => {
    if (!debugData) {
      if (apiClusterData) {
        setCluster(apiClusterData);
      } else {
        setCluster(null);
      }
      setIsLoading(apiLoading);
      setError(apiError);
    }
  }, [apiClusterData, apiLoading, apiError, debugData]);

  // Update the selected auth type when the cluster data is loaded
  useEffect(() => {
    if (cluster?.auth?.type) {
      setSelectedAuthType(cluster.auth.type);
    }

    setDisplayVersionOverride(!!cluster?.version_override);
  }, [cluster]);

  // Debug scenario handlers
  const applyDebugScenario = (scenario: keyof typeof CLUSTER_SCENARIOS) => {
    setDebugData(CLUSTER_SCENARIOS[scenario]);
    setCluster(CLUSTER_SCENARIOS[scenario]);
    setIsLoading(false);
    setError(null);
  };

  const resetToApiData = () => {
    setDebugData(null);
    setCluster(apiClusterData);
    setIsLoading(apiLoading);
    setError(apiError);
  };

  if (isLoading) {
    return (
      <Box padding="xl">
        <StatusIndicator type="loading">
          Loading {clusterType} cluster…
        </StatusIndicator>
      </Box>
    );
  }

  if (error) {
    return (
      <Alert type="error" header={`Failed to load ${clusterType} cluster`}>
        {error instanceof Error ? error.message : String(error)}
      </Alert>
    );
  }

  const capitalizedType =
    clusterType.charAt(0).toUpperCase() + clusterType.slice(1);

  return (
    <SpaceBetween size="l">
      <FormField
        label={`${capitalizedType} Endpoint`}
        description={`Enter the endpoint URL for your ${clusterType} cluster`}
      >
        <Input
          placeholder={`https://${clusterType}-cluster-endpoint:9200`}
          value={cluster?.endpoint ?? ""}
          onChange={() => {}}
          disabled
        />
      </FormField>

      <FormField
        label={`${capitalizedType} Certification Validation`}
        description={`Select if publicly trusted certificates are required`}
      >
        <Checkbox
          checked={!!cluster?.enable_tls_verification}
          onChange={() => {}}
          disabled
        >
          Certification validation enabled
        </Checkbox>
      </FormField>

      <FormField
        label="Authentication Type"
        description={`Select the authentication method for your ${clusterType} cluster`}
      >
        <RadioGroup
          items={
            [
              { value: "no_auth", label: "No Authentication" },
              { value: "basic_auth_arn", label: "Basic Authentication" },
              { value: "sig_v4_auth", label: "AWS SigV4" },
            ] satisfies { value: AuthModelType; label: string }[]
          }
          value={selectedAuthType}
          onChange={() => {}}
        />
      </FormField>

      {selectedAuthType === "basic_auth_arn" && (
        <SpaceBetween size="m">
          <FormField
            label="Secret ARN"
            description="ARN of the AWS Secrets Manager secret containing username and password"
          >
            <Input
              placeholder="arn:aws:secretsmanager:region:account-id:secret:secret-name"
              value={(cluster?.auth as BasicAuthArn)?.user_secret_arn ?? ""}
              onChange={() => {}}
              disabled
            />
          </FormField>
        </SpaceBetween>
      )}

      {selectedAuthType === "sig_v4_auth" && (
        <SpaceBetween size="m">
          <FormField label="Region" description="AWS Region for SigV4 signing">
            <Input
              placeholder="us-east-1"
              value={(cluster?.auth as SigV4Auth)?.region ?? ""}
              onChange={() => {}}
              disabled
            />
          </FormField>

          <FormField
            label="Service"
            description="AWS Service name for SigV4 signing"
          >
            <Input
              placeholder="es"
              value={(cluster?.auth as SigV4Auth)?.service ?? ""}
              onChange={() => {}}
              disabled
            />
          </FormField>
        </SpaceBetween>
      )}

      {!alwaysDisplayVersionOverride && (
        <FormField
          label="Override cluster version"
          description={`Allow overriding the version for ${clusterType} cluster, disabling automatic detection.`}
        >
          <Checkbox
            checked={displayVersionOverride}
            onChange={({ detail }) => {
              setDisplayVersionOverride(detail.checked);
            }}
            disabled
          >
            Override cluster version
          </Checkbox>
        </FormField>
      )}

      {(alwaysDisplayVersionOverride || displayVersionOverride) && (
        <SpaceBetween size="m">
          <FormField
            label="Cluster version"
            description={`The version for ${clusterType} cluster.`}
          >
            <Input
              placeholder="OpenSearch 2.11.1"
              value={cluster?.version_override ?? ""}
              onChange={() => {}}
              disabled
            />
          </FormField>
        </SpaceBetween>
      )}
      <ClusterDebugControls
        onScenarioSelect={applyDebugScenario}
        onReset={resetToApiData}
      />
    </SpaceBetween>
  );
}
