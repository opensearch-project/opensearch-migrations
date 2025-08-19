"use client";

import { useState, useEffect } from "react";
import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import FormField from "@cloudscape-design/components/form-field";
import Input from "@cloudscape-design/components/input";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import RadioGroup from "@cloudscape-design/components/radio-group";
import { useTargetCluster } from "../session/apiHooks";
import { BasicAuth, SigV4Auth } from "@/generated/api";
import { Alert, StatusIndicator } from "@cloudscape-design/components";

type AuthType = "NO_AUTH" | "BASIC_AUTH" | "SIGV4";

interface TargetConfigureProps {
  readonly sessionName: string;
}

export default function TargetConfigure({ sessionName }: TargetConfigureProps) {
  const { isLoading, data: cluster, error } = useTargetCluster(sessionName);
  const [selectedAuthType, setSelectedAuthType] = useState<AuthType | null>(null);
  
  // Update the selected auth type when the cluster data is loaded
  useEffect(() => {
    if (cluster?.auth?.type) {
      setSelectedAuthType(cluster.auth.type.toUpperCase() as AuthType);
    }
  }, [cluster]);
 
  if (isLoading) {
    return (
      <Box padding="xl">
        <StatusIndicator type="loading">Loading target cluster…</StatusIndicator>
      </Box>
    );
  }

  if (error) {
    return (
      <Alert type="error" header="Failed to load target cluster">
        {String((error as any)?.message ?? error)}
      </Alert>
    );
  }

  return (
      <SpaceBetween size="l">
        <FormField label="Target Endpoint" description="Enter the endpoint URL for your target cluster">
          <Input 
            placeholder="https://target-cluster-endpoint:9200"
            value={cluster?.endpoint || ""}
            onChange={() => {}}
          />
        </FormField>
        
        <FormField label="Authentication Type" description="Select the authentication method for your target cluster">
          <RadioGroup
            items={[
              { value: "NO_AUTH", label: "No Authentication" },
              { value: "BASIC_AUTH", label: "Basic Authentication" },
              { value: "SIGV4", label: "AWS SigV4" }
            ] satisfies { value: AuthType; label: string }[]}
            value={selectedAuthType}
            onChange={({ detail }) => setSelectedAuthType(detail.value as AuthType)}
          />
        </FormField>
        
        {selectedAuthType === "BASIC_AUTH" && (
          <SpaceBetween size="m">
            <FormField label="Username" description="Enter the username for Basic Authentication">
              <Input 
                placeholder="Username"
                value={(cluster?.auth as BasicAuth)?.username || ""}
                onChange={() => {}}
              />
            </FormField>
            
            <FormField label="Password" description="Enter the password for Basic Authentication">
              <Input 
                placeholder="Password"
                type="password"
                value="********"
                onChange={() => {}}
              />
            </FormField>
          </SpaceBetween>
        )}
        
        {selectedAuthType === "SIGV4" && (
          <SpaceBetween size="m">
            <FormField label="Region" description="AWS Region for SigV4 signing">
              <Input 
                placeholder="us-east-1"
                value={(cluster?.auth as SigV4Auth)?.region || ""}
                onChange={() => {}}
              />
            </FormField>
            
            <FormField label="Service" description="AWS Service name for SigV4 signing">
              <Input 
                placeholder="es"
                value={(cluster?.auth as SigV4Auth)?.service || ""}
                onChange={() => {}}
              />
            </FormField>
          </SpaceBetween>
        )}
      </SpaceBetween>
  );
};
