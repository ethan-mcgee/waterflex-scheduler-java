import { notFound } from "next/navigation";
import OptimizationTestDashboard from "./OptimizationTestDashboard";

export const dynamic = "force-dynamic";

function enabled() {
  const configured = process.env.OPTIMIZATION_TEST_ENABLED;
  return configured === "true" || (configured === undefined && process.env.NODE_ENV !== "production");
}

export default function OptimizationTestPage() {
  if (!enabled()) notFound();
  return <OptimizationTestDashboard />;
}
