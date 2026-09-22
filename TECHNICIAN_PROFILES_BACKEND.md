# Technician profiles and weekly availability

## Data model

`Technician` stores nullable email, phone, bio, and legacy home address components, plus a required hex color. The migration assigns each existing technician the same palette color that the previous ID hash displayed. New technician writes supply a color explicitly.

`technician_availability_version` stores the date when a seven-day template takes effect. Each version has seven `technician_availability_day` rows, one for each Sunday through Saturday. Available days require a valid start and end minute; off days store null hours. The migration gives existing technicians a Monday through Friday template using their flat shift hours. The flat shift fields remain populated for compatibility and do not govern scheduling when a template exists.

Missing versions, missing days, and invalid available hours are errors. Date-specific `technician_shift_override` entries take precedence over the selected weekly day. Those exceptions still use the Java guard endpoint and its day locks.

## API and editing

- `POST /api/technicians` validates profile fields, metro, qualifications, a complete seven-day template, and a confirmed geocoder pin. The technician, qualifications, and initial template are created in one Prisma write.
- `PATCH /api/technicians/[id]` saves name, nullable contacts, bio, and color immediately. Staff can leave legacy contacts null while changing another field.
- `PUT /api/technicians/[id]/standard-availability` validates all seven days. It replaces any future version and schedules the new version for the calendar day after the booking horizon's tenth weekday. The server calculates that date in `America/Chicago`.

The availability write locks the technician, checks active appointments and holds against the proposed hours, and rejects an edit that would invalidate a commitment. Date exceptions remain in force. The roster's weekly grid is read only; Edit profile shows current and pending versions and provides the save action. A pending edit can be replaced.

## Scheduling and display

Java booking and optimization resolve the latest version effective on each service date, then apply a date exception. Booking examines every calendar day through the tenth weekday, including available weekends inside that span. Two-hour booking windows start at the day's shift start and repeat hourly while they fit within the shift. Optimization includes the effective day in its configuration fingerprint. The technician week strip and Weekly Schedule show resolved daily hours and off days. Dispatch Board, Dispatch Map, roster avatars, and Weekly Schedule use the stored color.

## Verification

The isolated `waterflex_test` database received the migration and Prisma client generation completed. The schema contract, web lint and typecheck, availability unit tests, targeted Playwright tests, Java tests, and strict Java nullability gate were run. The migration backfilled seven days and a non-null color for each existing test technician.
