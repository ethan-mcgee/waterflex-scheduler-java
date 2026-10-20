import { test, expect, type Page } from "@playwright/test";

async function fill(page: Page, method: string, cost: string, fairness: string) {
  await page.getByLabel("Search method").selectOption(method);
  await page.getByLabel("Cost search time").fill(cost);
  await page.getByLabel("Fairness search time").fill(fairness);
  await page.getByRole("button", { name: "Save settings" }).click();
}

test("a client chooses its daily search method and times, and the page keeps them", async ({ page }) => {
  await page.goto("/settings");
  // Existing clients start on Tabu + K opt with the earlier fixed 10 and 5 seconds.
  await expect(page.getByLabel("Search method")).toHaveValue("TABU_KOPT");
  await expect(page.getByLabel("Cost search time")).toHaveValue("10");
  await expect(page.getByLabel("Fairness search time")).toHaveValue("5");
  try {
    await fill(page, "TABU", "60", "0");
    await expect(page.getByRole("status").filter({ hasText: "Settings saved." })).toBeVisible();
    await page.reload();
    await expect(page.getByLabel("Search method")).toHaveValue("TABU");
    await expect(page.getByLabel("Cost search time")).toHaveValue("60");
    await expect(page.getByLabel("Fairness search time")).toHaveValue("0");

    // More than 240 seconds together is refused, and nothing changes.
    await fill(page, "TABU_KOPT", "200", "41");
    // Scoped to the page body: Next's route announcer is also an alert.
    await expect(page.getByRole("main").getByRole("alert")).toBeVisible();
    await page.reload();
    await expect(page.getByLabel("Cost search time")).toHaveValue("60");

    // The optional unimproved limit starts empty (every search spends its whole time) and is kept once set.
    await expect(page.getByLabel("Stop when unimproved")).toHaveValue("");
    await page.getByLabel("Stop when unimproved").fill("8");
    await fill(page, "TABU_KOPT_SIZE_3", "30", "10");
    await expect(page.getByRole("status").filter({ hasText: "Settings saved." })).toBeVisible();
    await page.reload();
    await expect(page.getByLabel("Search method")).toHaveValue("TABU_KOPT_SIZE_3");
    await expect(page.getByLabel("Stop when unimproved")).toHaveValue("8");

    // A limit longer than the cost search is refused, and nothing changes.
    await page.getByLabel("Stop when unimproved").fill("31");
    await fill(page, "TABU_KOPT_SIZE_3", "30", "10");
    await expect(page.getByRole("main").getByRole("alert")).toBeVisible();
    await page.reload();
    await expect(page.getByLabel("Stop when unimproved")).toHaveValue("8");
  } finally {
    await page.goto("/settings");
    await page.getByLabel("Stop when unimproved").fill("");
    await fill(page, "TABU_KOPT", "10", "5");
    await expect(page.getByRole("status").filter({ hasText: "Settings saved." })).toBeVisible();
  }
});
