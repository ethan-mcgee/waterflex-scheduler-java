import Link from "next/link";
import { tomorrowInTz } from "@/lib/date";
import { OMAHA_TIMEZONE } from "@/lib/fakeDataCore";
import FakeDataForm from "./FakeDataForm";
import styles from "./fakeData.module.css";

export const dynamic = "force-dynamic";

export default function FakeDataPage() {
  const tomorrow = tomorrowInTz(OMAHA_TIMEZONE);
  return (
    <main className={styles.wrap}>
      <div className={styles.headingRow}>
        <div>
          <h1>Omaha fake appointments</h1>
          <p>Create scheduler-valid test calls without running nightly optimization.</p>
        </div>
        <Link href="/">Home</Link>
      </div>
      <FakeDataForm defaultDate={tomorrow} />
    </main>
  );
}
