import { headers } from "next/headers";
import { notFound } from "next/navigation";
import { localTestRequestAllowed } from "@/lib/bookingTestAccess";
import TestingPage from "./TestingPage";

export const dynamic = "force-dynamic";
export default function Page() {
  const requestHeaders = headers();
  const host = requestHeaders.get("host") ?? "";
  // Cross-site top-level navigation is harmless; API calls still enforce same origin.
  if (!localTestRequestAllowed(new Request(`http://${host || "invalid"}/dispatch/testing`, { headers: { host } }))) notFound();
  return <TestingPage />;
}
