if (!process.env.COMMIT_RECENT_TAG) {
  throw new Error("Missing COMMIT_RECENT_TAG");
}
if (!process.env.COMMIT_SHA) {
  throw new Error("Missing COMMIT_SHA");
}
if (!process.env.COMMIT_DATE) {
  throw new Error("Missing COMMIT_DATE");
}

export const COMMIT_RECENT_TAG = process.env.COMMIT_RECENT_TAG;
export const COMMIT_SHA = process.env.COMMIT_SHA;
export const COMMIT_DATE = process.env.COMMIT_DATE;
