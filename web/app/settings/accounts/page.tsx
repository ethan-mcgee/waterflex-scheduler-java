import { activeClient } from "@/lib/activeClient";
import { accountSummary } from "@/lib/accountLookup";
import { PageHeader } from "@/app/portal/ui";
import AccountSearch from "./AccountSearch";
import styles from "../settingsTable.module.css";

export const dynamic = "force-dynamic";

const IMPORTED = new Intl.DateTimeFormat("en-US", { dateStyle: "medium", timeStyle: "short" });

export default async function AccountsPage() {
  const client = await activeClient();
  const summary = await accountSummary(client.id);
  return <>
    <PageHeader crumbs={["Settings", "Accounts"]} title="WaterFlex accounts"
      description={`${client.name}'s customer accounts, with the coordinates WaterFlex placed them at. A work order for an account is booked at that place without looking the address up again.`} />
    <dl className={styles.summary}>
      <div><dt>Accounts</dt><dd>{summary.total}</dd></div>
      <div><dt>Without coordinates</dt><dd>{summary.withoutLocation}</dd></div>
      <div><dt>Last import</dt><dd>{summary.lastImportedAt === null ? "Never" : IMPORTED.format(new Date(summary.lastImportedAt))}</dd></div>
    </dl>
    <p className={styles.note}>
      Accounts come from WaterFlex&apos;s account export until the portal can ask WaterFlex directly. An administrator imports the file with{" "}
      <code>npx tsx scripts/import-accounts.ts --client {client.id} --file &lt;accounts.csv&gt;</code>. An account without coordinates can only be sent
      to follow-up.
    </p>
    <AccountSearch />
  </>;
}
