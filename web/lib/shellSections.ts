/**
 * Sections of the portal that draw their own frame instead of the classic sidebar shell. Every other path keeps the
 * classic shell exactly as it was.
 */
export const STANDALONE_SECTIONS: readonly string[] = ["/work-orders"];

/** Whether a path belongs to a section with its own frame: the section's root or anything below it. */
export function isStandaloneSection(pathname: string | null): boolean {
  if (pathname === null) return false;
  return STANDALONE_SECTIONS.some(section => pathname === section || pathname.startsWith(`${section}/`));
}
