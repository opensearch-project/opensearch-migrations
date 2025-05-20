"use client";

import Header from "@cloudscape-design/components/header";
import { COMMIT_RECENT_TAG, COMMIT_SHA, COMMIT_DATE } from "@/lib/env";
import {
  Box,
  Container,
  Icon,
  KeyValuePairs,
  Link,
  SpaceBetween,
} from "@cloudscape-design/components";

export default function Page() {
  return (
    <SpaceBetween size="l">
      <Header variant="h1">About Migration Assistant</Header>
      <Container>
        <SpaceBetween size="l">
          <KeyValuePairs
            columns={3}
            items={[
              {
                label: "Version",
                value: COMMIT_RECENT_TAG,
                info: (
                  <Link
                    external
                    variant="info"
                    href={`https://github.com/opensearch-project/opensearch-migrations/releases/tag/${COMMIT_RECENT_TAG}`}
                  ></Link>
                ),
              },
              {
                label: "Commit SHA",
                value: COMMIT_SHA,
                info: (
                  <Link
                    external
                    variant="info"
                    href={`https://github.com/opensearch-project/opensearch-migrations/commit/${COMMIT_SHA}`}
                  ></Link>
                ),
              },
              {
                label: "Build Date",
                value: new Date(COMMIT_DATE).toLocaleDateString(),
              },
            ]}
          ></KeyValuePairs>
          <Box>
            OpenSearch Migration Assistant is a comprehensive set of tools
            designed to facilitate upgrades, migrations, and comparisons for
            OpenSearch and Elasticsearch clusters. This project aims to simplify
            the process of moving between different versions and platforms while
            ensuring data integrity and performance.
          </Box>
          <Box>
            Learn more at{" "}
            <Link
              external
              href="https://github.com/opensearch-project/opensearch-migrations"
            >
              <Icon url="/github-mark.svg"></Icon>{" "}
              opensearch-project/opensearch-migration
            </Link>
            .
          </Box>
        </SpaceBetween>
      </Container>
    </SpaceBetween>
  );
}
