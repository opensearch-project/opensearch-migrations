import { expect, test } from "@playwright/test";

test("renders the production application shell", async ({ page }) => {
  await page.goto("/");

  await expect(
    page.getByRole("heading", { name: "Workflow Manage" }),
  ).toBeVisible();
});
