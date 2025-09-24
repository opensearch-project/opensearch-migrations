"use client";

import React, { useState } from "react";
import {
  SpaceBetween,
  Header,
  Container,
  FormField,
  Input,
  Button,
  Spinner,
  Alert,
} from "@cloudscape-design/components";
import { sessionCreate } from "@/generated/api";
import DebugCommands from "@/components/debug/DebugCommands";

export default function CreateSessionPage() {
  const [name, setName] = useState("");
  const [updating, setUpdating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<boolean>(false);

  const handleAddSession = async () => {
    if (!name) return;

    setUpdating(true);
    setError(null);
    setSuccess(false);

    try {
      const res = await sessionCreate({ body: { name } });
      if (res.response.status === 201) {
        setSuccess(true);
      } else {
        setError(JSON.stringify(res.error, null, 2));
      }
    } catch (err) {
      console.log(err);
      setError(JSON.stringify(err, null, 2) || "Failed to create session");
    } finally {
      setUpdating(false);
    }
  };

  return (
    <SpaceBetween size="m">
      <Header variant="h1">Create Migration Session</Header>

      <Container>
        <SpaceBetween size="l">
          {success && (
            <Alert type="success" data-testid="alert-success">
              Session created successfully!
            </Alert>
          )}

          {error && (
            <Alert type="error" data-testid="alert-error">
              {error}
            </Alert>
          )}

          <FormField label="Migration Session Name">
            <Input
              value={name}
              placeholder="Enter a session name"
              onChange={({ detail }) => setName(detail.value)}
              disabled={updating}
            />
          </FormField>

          <SpaceBetween size="m" direction="horizontal">
            <Button
              variant="primary"
              disabled={!name || updating}
              disabledReason={!name ? "Name is required" : undefined}
              onClick={handleAddSession}
              data-testid="create-session-button"
            >
              Create Session
            </Button>

            {updating && <Spinner size="big" data-testid="session-spinner" />}
          </SpaceBetween>
        </SpaceBetween>
      </Container>
      <DebugCommands>
        <SpaceBetween size="xs" direction="horizontal">
          <Button onClick={() => setSuccess(true)}>Simulate Success</Button>
          <Button onClick={() => setError("Simulated error occurred")}>
            Simulate Error
          </Button>
          <Button onClick={() => setUpdating(true)}>Simulate Loading</Button>
          <Button
            onClick={() => {
              setSuccess(false);
              setError(null);
              setUpdating(false);
            }}
          >
            Reset
          </Button>
        </SpaceBetween>
      </DebugCommands>
    </SpaceBetween>
  );
}
