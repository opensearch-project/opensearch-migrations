import { stopCluster } from "./argoCluster";

export default async function globalTeardown() {
  await stopCluster();
}
