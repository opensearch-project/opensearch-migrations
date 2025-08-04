"use client";

import { systemHealth } from "@/generated/api";
import { Box } from "@cloudscape-design/components";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { getSiteReadiness, setSiteReadiness } from "@/lib/site-readiness";
import { withTimeLimit } from "@/utils/async";

export default function DefaultPage() {
  const router = useRouter();

  useEffect(() => {
    const checkHealth = async () => {
      if (getSiteReadiness()) {
        return;
      }

      try {
        const res = await withTimeLimit(systemHealth(), 5000);
        if (res.data?.status === "ok") {
          setSiteReadiness(true);
          return;
        }
        router.replace("/loading");
      } catch {
        router.replace("/loading");
      }
    };

    checkHealth();
  }, [router]);

  return <Box>Welcome to Migration Assistant</Box>;
}
