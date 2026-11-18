import { redirect } from "next/navigation";

/** Settings opens on its first page. */
export default function SettingsIndex() {
  redirect("/settings/solver");
}
