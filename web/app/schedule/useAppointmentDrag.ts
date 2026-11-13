"use client";

import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import type { ScheduleAppointment } from "./types";

/** How far the pointer moves before a press on a card becomes a drag; a shorter press stays a click. */
const DRAG_THRESHOLD_PX = 5;

/** A schedule cell under the pointer, from its `data-drop-technician` and `data-drop-date` attributes. */
export interface DropCell { technicianId: string; date: string }

export interface DragState { appointment: ScheduleAppointment; x: number; y: number; over: DropCell | null }

function cellAt(x: number, y: number): DropCell | null {
  const element = document.elementFromPoint(x, y)?.closest<HTMLElement>("[data-drop-technician]");
  const technicianId = element?.dataset.dropTechnician;
  const date = element?.dataset.dropDate;
  return technicianId && date ? { technicianId, date } : null;
}

/**
 * Drags an appointment card onto another technician's cell with pointer events, so the same code serves a mouse, a
 * pen and a touch screen, and a press without movement stays a click that opens the card. `canDrop` decides which
 * cells take the card; `onDrop` receives the time of the drop, taken in the handler, for the timing study.
 */
export function useAppointmentDrag({ canDrop, onDrop }: {
  canDrop: (appointment: ScheduleAppointment, cell: DropCell) => boolean;
  onDrop: (appointment: ScheduleAppointment, cell: DropCell, droppedAt: number) => void;
}) {
  const [drag, setDrag] = useState<DragState | null>(null);
  const pressed = useRef<{ appointment: ScheduleAppointment; x: number; y: number; dragging: boolean } | null>(null);
  /** Set after a drag ends, so the click the browser may fire on release does not also open the card. */
  const suppressClick = useRef(false);
  const handlers = useRef({ canDrop, onDrop });
  useEffect(() => { handlers.current = { canDrop, onDrop }; }, [canDrop, onDrop]);

  const end = useCallback(() => { pressed.current = null; setDrag(null); }, []);

  useEffect(() => {
    function move(event: PointerEvent) {
      const press = pressed.current;
      if (press === null) return;
      if (!press.dragging && Math.hypot(event.clientX - press.x, event.clientY - press.y) < DRAG_THRESHOLD_PX) return;
      press.dragging = true;
      const cell = cellAt(event.clientX, event.clientY);
      setDrag({ appointment: press.appointment, x: event.clientX, y: event.clientY,
        over: cell !== null && handlers.current.canDrop(press.appointment, cell) ? cell : null });
    }
    function up(event: PointerEvent) {
      const press = pressed.current;
      if (press === null) return;
      const droppedAt = performance.now();
      if (press.dragging) {
        suppressClick.current = true;
        setTimeout(() => { suppressClick.current = false; }, 0);
        const cell = cellAt(event.clientX, event.clientY);
        if (cell !== null && handlers.current.canDrop(press.appointment, cell)) handlers.current.onDrop(press.appointment, cell, droppedAt);
      }
      end();
    }
    function key(event: KeyboardEvent) { if (event.key === "Escape" && pressed.current?.dragging) end(); }
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    window.addEventListener("pointercancel", end);
    window.addEventListener("keydown", key);
    return () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      window.removeEventListener("pointercancel", end);
      window.removeEventListener("keydown", key);
    };
  }, [end]);

  /** Starts watching a press on a card; only the primary button of a mouse starts a drag. */
  const press = useCallback((appointment: ScheduleAppointment, event: ReactPointerEvent) => {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    pressed.current = { appointment, x: event.clientX, y: event.clientY, dragging: false };
  }, []);

  /** True when a click right after a drag should be ignored. */
  const clickSuppressed = useCallback(() => suppressClick.current, []);

  return { drag, press, clickSuppressed };
}
