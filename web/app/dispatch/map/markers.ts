import styles from "@/app/dispatch/dispatch.module.css";

/** DOM elements for the board's map markers; MapLibre positions them, and CSS-module classes style them. */

const HOME_ICON = '<svg aria-hidden="true" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 10.5 12 3l9 7.5V21H3z"></path><path d="M9 21v-6h6v6"></path></svg>';
const DEPOT_ICON = '<svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 8.35V20a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8.35A2 2 0 0 1 3.26 6.5l8-3.2a2 2 0 0 1 1.48 0l8 3.2A2 2 0 0 1 22 8.35Z"></path><path d="M6 18h12M6 14h12"></path><rect x="6" y="10" width="12" height="12"></rect></svg>';

function className(...names: Array<string | false | undefined>): string {
  return names.filter((name): name is string => typeof name === "string" && name.length > 0).join(" ");
}

/** A visit: white circle, technician-color ring and the 1-based stop number; a changed visit gets a brand ring. */
export function stopMarkerElement(stopNumber: number, color: string, label: string, options: { changed: boolean; dimmed: boolean; onSelect?: () => void }): HTMLElement {
  const element = document.createElement(options.onSelect ? "button" : "span");
  if (element instanceof HTMLButtonElement) element.type = "button";
  element.className = className(styles.stopMarker, options.changed && styles.stopMarkerChanged, options.dimmed && styles.markerDimmed);
  element.style.borderColor = color;
  element.textContent = String(stopNumber);
  element.setAttribute("aria-label", label);
  element.title = label;
  const select = options.onSelect;
  if (select) element.addEventListener("click", event => { event.stopPropagation(); select(); });
  return element;
}

/** A technician's home: navy circle with a white house. */
export function homeMarkerElement(label: string, dimmed: boolean): HTMLElement {
  const element = document.createElement("span");
  element.className = className(styles.homeMarker, dimmed && styles.markerDimmed);
  element.innerHTML = HOME_ICON;
  element.setAttribute("role", "img");
  element.setAttribute("aria-label", label);
  element.title = label;
  return element;
}

/** A depot: navy square with a white warehouse. */
export function depotMarkerElement(label: string): HTMLElement {
  const element = document.createElement("span");
  element.className = styles.depotMarker ?? "";
  element.innerHTML = DEPOT_ICON;
  element.setAttribute("role", "img");
  element.setAttribute("aria-label", label);
  element.title = label;
  return element;
}
