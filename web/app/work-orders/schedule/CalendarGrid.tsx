"use client";

import { useState, useSyncExternalStore } from "react";
import { ChevronLeft, ChevronRight, MapPin } from "lucide-react";
import {
  calendarHours, clockLabel, durationLabel, hourLabel, minuteOfDay,
  type WaitingWorkOrder, type WorkOrderCard, type WorkOrderTechnician, type WorkOrderType,
} from "@/lib/workOrdersDayCore";
import { StatusIcon } from "../ui";
import styles from "./schedule.module.css";

const HOUR_PX = 54;
const MIN_CARD_PX = 24;

/** The current minute, ticking once a minute; null while rendering on the server, so the page hydrates cleanly. */
function subscribeMinute(callback: () => void): () => void {
  const timer = window.setInterval(callback, 30_000);
  return () => window.clearInterval(timer);
}
const currentMinute = () => Math.floor(Date.now() / 60_000);
const noMinute = () => null;

function WorkOrderCardView({ card, background, startHour }: { card: WorkOrderCard; background: string; startHour: number }) {
  const top = (card.startMin - startHour * 60) / 60 * HOUR_PX;
  const height = Math.max(MIN_CARD_PX, (card.endMin - card.startMin) / 60 * HOUR_PX);
  return (
    <article className={styles.card} style={{ top, height, background }} data-work-order={card.appointmentId}
      aria-label={`${clockLabel(card.startMin)} ${card.typeName} for ${card.customerName}, ${card.place}`}>
      <div className={styles.cardLine}>
        <strong>{clockLabel(card.startMin)}</strong>
        <MapPin size={12} aria-hidden className={styles.cardPin} />
        <span className={styles.cardPlace}>{card.place}</span>
        {card.status && <StatusIcon status={card.status} />}
      </div>
      <div className={styles.cardType}>{card.typeName}</div>
      <div className={styles.cardMeta}>{card.customerName}</div>
      <div className={styles.cardMeta}>{card.street}</div>
    </article>
  );
}

function WaitingCard({ order, background }: { order: WaitingWorkOrder; background: string }) {
  return (
    <article className={styles.waitingCard} style={{ background }} data-waiting-order={order.jobId}
      aria-label={`Waiting: ${order.typeName} for ${order.customerName}, ${order.place}`}>
      <div className={styles.cardLine}>
        <MapPin size={12} aria-hidden className={styles.cardPin} />
        <span className={styles.cardPlace}>{order.place}</span>
      </div>
      <div className={styles.cardType}>{order.typeName}</div>
      <div className={styles.cardMeta}>{order.customerName} | {durationLabel(order.durationMin)}{order.located ? "" : " | address not located"}</div>
    </article>
  );
}

/**
 * The design's day calendar: an hour gutter, an Unassigned column that folds away, and one column per technician.
 * Booked visits sit at their planned times. Waiting work orders have no time yet, so they stack in Unassigned.
 */
export default function CalendarGrid({ technicians, cards, waiting, types, timezone, isToday, compact }: {
  technicians: readonly WorkOrderTechnician[]; cards: readonly WorkOrderCard[]; waiting: readonly WaitingWorkOrder[];
  types: readonly WorkOrderType[]; timezone: string; isToday: boolean; compact: boolean;
}) {
  const [unassignedOpen, setUnassignedOpen] = useState(true);
  const minute = useSyncExternalStore(subscribeMinute, currentMinute, noMinute);
  const shownCards = cards.filter(card => technicians.some(tech => tech.id === card.technicianId));
  const hours = calendarHours(technicians, shownCards);
  const hourList = Array.from({ length: hours.end - hours.start }, (_, index) => hours.start + index);
  const height = hourList.length * HOUR_PX;
  const backgroundOf = (code: string) => {
    const type = types.find(item => item.code === code);
    if (type === undefined) throw new Error(`Work order type ${code} is missing from the day's types`);
    return type.background;
  };
  const nowMin = isToday && minute !== null ? minuteOfDay(new Date(minute * 60_000).toISOString(), timezone) : null;
  const nowTop = nowMin !== null && nowMin >= hours.start * 60 && nowMin <= hours.end * 60 ? (nowMin - hours.start * 60) / 60 * HOUR_PX : null;

  const columns = [unassignedOpen ? "minmax(180px, 1fr)" : "0px", ...technicians.map(() => `minmax(${compact ? 150 : 170}px, 1fr)`)].join(" ");
  return (
    <div className={styles.calendarScroll}>
      <div className={styles.calendar} style={{ gridTemplateColumns: `48px ${columns}` }} data-work-orders-calendar>
        <div className={styles.corner} />
        <div className={`${styles.columnHead} ${styles.unassignedHead} ${unassignedOpen ? "" : styles.folded}`} aria-hidden={!unassignedOpen}>Unassigned{waiting.length > 0 ? ` · ${waiting.length}` : ""}</div>
        {technicians.map(tech => <div key={tech.id} className={styles.columnHead} data-technician-column={tech.id}>
          {tech.name}{tech.shift === null && <small> · off</small>}
        </div>)}

        <div className={styles.gutter} style={{ height }}>
          {hourList.map((hour, index) => {
            const label = hourLabel(hour, index === 0);
            return <span key={hour} style={{ top: index * HOUR_PX }}>{label.hour}<sup>{label.suffix}</sup></span>;
          })}
        </div>

        <div className={`${styles.unassigned} ${unassignedOpen ? "" : styles.folded}`} style={{ height }} aria-hidden={!unassignedOpen}
          aria-label="Unassigned work orders" data-unassigned-column>
          {waiting.map(order => <WaitingCard key={order.jobId} order={order} background={backgroundOf(order.typeCode)} />)}
          {waiting.length === 0 && <p className={styles.quiet}>Nothing waiting</p>}
        </div>

        {technicians.map((tech, index) => <div key={tech.id} className={styles.column} style={{ height, backgroundSize: `100% ${HOUR_PX}px` }}
          aria-label={`${tech.name}'s work orders`}>
          {tech.shift && <>
            <div className={styles.offShift} style={{ top: 0, height: Math.max(0, (tech.shift.startMin - hours.start * 60) / 60 * HOUR_PX) }} />
            <div className={styles.offShift} style={{ top: (tech.shift.endMin - hours.start * 60) / 60 * HOUR_PX, bottom: 0 }} />
          </>}
          {tech.shift === null && <div className={styles.offShift} style={{ top: 0, bottom: 0 }} />}
          {index === 0 && <button type="button" className={styles.foldButton} onClick={() => setUnassignedOpen(open => !open)}
            aria-label={unassignedOpen ? "Collapse unassigned" : "Expand unassigned"} title={unassignedOpen ? "Collapse unassigned" : "Expand unassigned"}>
            {unassignedOpen ? <ChevronLeft size={14} aria-hidden /> : <ChevronRight size={14} aria-hidden />}
          </button>}
          {shownCards.filter(card => card.technicianId === tech.id).map(card =>
            <WorkOrderCardView key={card.appointmentId} card={card} background={backgroundOf(card.typeCode)} startHour={hours.start} />)}
        </div>)}

        {nowTop !== null && <div className={styles.nowLine} style={{ top: 34 + nowTop }} aria-hidden />}
      </div>
    </div>
  );
}
