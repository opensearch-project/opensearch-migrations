import type { NextConfig } from "next";

const { execSync } = require('node:child_process');

function getGitMostRecentTag() {
  return execOrUnknown('git describe --tags --abbrev=0 HEAD');
}

function getGitCommitHash() {
  return execOrUnknown('git rev-parse --short HEAD');
}

function getGitCommitDate() {
  return execOrUnknown('git show -s --format=%ci HEAD');
}

function execOrUnknown(command: string) {
  try {
    return execSync(command).toString().trim();
  } catch (e) {
    console.error(`Unexpected error running command:'${command}', result was ${e}`);
    return 'unknown';
  }
}

const nextConfig: NextConfig = {
  env: {
    COMMIT_RECENT_TAG: getGitMostRecentTag(),
    COMMIT_SHA: getGitCommitHash(),
    COMMIT_DATE: getGitCommitDate()
  },
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
  eslint: {
    dirs: ["src/app", "src/components", "src/context", "src/hooks", "src/lib","src/types", "src/utils"]
  },
  trailingSlash: true,
  output: 'export'
};

export default nextConfig;
