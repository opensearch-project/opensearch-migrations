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
  webpack(config) {
    // Configure webpack to handle Ace Editor worker files
    config.module.rules.push({
      test: /ace-builds.*\/worker-.*\.js$/,
      type: "asset/resource",
      generator: {
        filename: "static/workers/[name][ext]",
      },
    });
    
    return config;
  },
};

export default nextConfig;
