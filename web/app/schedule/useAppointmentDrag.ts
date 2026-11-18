"use client";

import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";

/** How far the pointer moves before a press on a card becomes a drag; a shorter press stays a click. */
const DRAG_THRESHOLD_PX = 5;

/**
 * A schedule cell under the pointer, from its `data-drop-technician` and `data-drop-date` attributes, and the minute of
 * the day the pointer is at, from the cell's `data-drop-start-minute` and `data-drop-hour-px`.
 */
export interface DropCell { technicianId: string; date: string; minute: number | null }

export interface DragState<T> { item: T; x: number; y: number; over: DropCell | null }

function cellAt(x: number, y: number): DropCell | null {
  const element = document.elementFromPoint(x, y)?.closest<HTMLElement>("[data-drop-technician]");
  const technicianId = element?.dataset.dropTechnician;
  const date = element?.dataset.dropDate;
  if (element == null || !technicianId || !date) return null;
  const start = Number(element.dataset.dropStartMinute), hourPx = Number(element.dataset.dropHourPx);
  const minute = Number.isFinite(start) && Number.isFinite(hourPx) && hourPx > 0
    ? Math.round(start + ((y - element.getBoundingClientRect().top) / hourPx) * 60) : null;
  return { technicianId, date, minute };
}

/**
 * Drags a card (an appointment, or a job waiting to be placed) onto a technician's cell with pointer events, so the
 * same code serves a mouse, a pen and a touch screen, and a press without movement stays a click. `onPress` runs when
 * a mouse or pen goes down on a card, before it is known whether the press is a click or a drag (a touch is often
 * the start of a scroll, so it does not count); `onSettle` runs after every press ends, once the click it may fire is
 * handled. `onStart` runs once when a press becomes a drag; `canDrop` decides which cells take the card; `onDrop`
 * receives the time of the drop, taken in the handler, for timing.
 */
export function useAppointmentDrag<T>({ canDrop, onDrop, onStart, onPress, onSettle }: {
  canDrop: (item: T, cell: DropCell) => boolean;
  onDrop: (item: T, cell: DropCell, droppedAt: number) => void;
  onStart?: (item: T) => void;
  onPress?: (item: T) => void;
  onSettle?: () => void;
}) {
  const [drag, setDrag] = useState<DragState<T> | null>(null);
  const pressed = useRef<{ item: T; x: number; y: number; dragging: boolean } | null>(null);
  /** Set after a drag ends, so the click the browser may fire on release does not also open the card. */
  const suppressClick = useRef(false);
  const handlers = useRef({ canDrop, onDrop, onStart, onPress, onSettle });
  useEffect(() => { handlers.current = { canDrop, onDrop, onStart, onPress, onSettle }; }, [canDrop, onDrop, onStart, onPress, onSettle]);

  const end = useCallback(() => {
    const wasPressed = pressed.current !== null;
    pressed.current = null;
    setDrag(null);
    // After the click the release may fire, which runs before this timeout, as the click suppression below relies on.
    if (wasPressed) setTimeout(() => handlers.current.onSettle?.(), 0);
  }, []);

  useEffect(() => {
    function move(event: PointerEvent) {
      const press = pressed.current;
      if (press === null) return;
      if (!press.dragging && Math.hypot(event.clientX - press.x, event.clientY - press.y) < DRAG_THRESHOLD_PX) return;
      if (!press.dragging) { press.dragging = true; handlers.current.onStart?.(press.item); }
      const cell = cellAt(event.clientX, event.clientY);
      setDrag({ item: press.item, x: event.clientX, y: event.clientY,
        over: cell !== null && handlers.current.canDrop(press.item, cell) ? cell : null });
    }
    function up(event: PointerEvent) {
      const press = pressed.current;
      if (press === null) return;
      const droppedAt = performance.now();
      if (press.dragging) {
        suppressClick.current = true;
        setTimeout(() => { suppressClick.current = false; }, 0);
        const cell = cellAt(event.clientX, event.clientY);
        if (cell !== null && handlers.current.canDrop(press.item, cell)) handlers.current.onDrop(press.item, cell, droppedAt);
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
  const press = useCallback((item: T, event: ReactPointerEvent) => {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    pressed.current = { item, x: event.clientX, y: event.clientY, dragging: false };
    if (event.pointerType !== "touch") handlers.current.onPress?.(item);
  }, []);

  /** True when a click right after a drag should be ignored. */
  const clickSuppressed = useCallback(() => suppressClick.current, []);

  return { drag, press, clickSuppressed };
}
