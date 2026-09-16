import Link from "next/link";

export default function HomePage() {
  return (
    <main style={{ padding: "3rem", fontFamily: "system-ui, sans-serif" }}>
      <h1>WaterFlex Scheduler</h1>
      <p>
        <Link href="/book">Book a service visit &rarr;</Link>
      </p>
      <p>
        <Link href="/schedule">View weekly schedule &rarr;</Link>
      </p>
      <p>
        <Link href="/dispatch">Open dispatch board &rarr;</Link>
      </p>
    </main>
  );
}
