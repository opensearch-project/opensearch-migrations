import { startCluster } from "./argoCluster";

export default async function globalSetup() {
  await startCluster();
}
