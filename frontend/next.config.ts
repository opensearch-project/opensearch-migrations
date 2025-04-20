import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  /* config options here */
  reactStrictMode: true,
  transpilePackages: [
    "@cloudscape-design/components",
    "@cloudscape-design/board-components",
    "@cloudscape-design/global-styles",
    "@cloudscape-design/component-toolkit"
  ],
};

export default nextConfig;
