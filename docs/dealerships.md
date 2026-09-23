# Dealerships and depots

A dealership owns any number of depots across metros. Each depot has one owner, one metro, a verified map pin, and a dated departure and return policy. A technician's dealership and metro come from the depot assignment effective on the service date. Assignments and policies begin at midnight UTC date keys, interpreted as America/Chicago service calendar dates.

## Deployment owner audit

Before applying `20260923120000_multi_depot_dealerships`, run this query on the target database:

```sql
SELECT p.id AS depot_id, p.name, count(d.id) AS owner_count,
       array_agg(d.id) FILTER (WHERE d.id IS NOT NULL) AS dealership_ids
FROM depot p LEFT JOIN dealership d ON d."depotId" = p.id
GROUP BY p.id, p.name
HAVING count(d.id) <> 1;
```

Every result needs an explicit owner decision. For a depot with no owner, create a dealership with that depot's ID and the approved organization name in the old schema before deploying. For a depot with several dealerships, resolve the organization owner and technician relationships explicitly. The migration refuses either state. It does not create an LLC or guess an owner. Run the audit again and require zero rows before deployment. Existing technician assignments and every dated dealership endpoint policy are copied to the corresponding depot. The migration preserves optimization endpoint snapshots.

## Setup and moves

Create the dealership on `/dealerships`, then add each depot with a metro, full street address, confirmed pin, and departure and return policy. The address lookup is canceled when the address changes. The pin may be adjusted within 250 meters of a fresh geocoder candidate. The server checks that distance again before saving the address, geocoder coordinates and precision, final pin coordinates, and confirmation time. Older depot rows keep nullable address and provenance fields. When the tile archive does not cover the candidate, the page shows the candidate coordinates and allows creation without a map. A geocoder candidate is still required. Failed creation keeps all entered values and displays the error in the form.

The list counts active technicians whose latest assignment effective today points to each depot. It shows the policy effective today separately from the next scheduled policy. Saving a policy replaces that next scheduled date when present. Otherwise it takes effect today before the 6:00 a.m. America/Chicago cutoff or tomorrow after it. The API returns the saved effective date. Booked routes and active holds remain protected by the scheduling service.

Add a technician on `/technicians` with a depot; their first assignment starts at `1900-01-01`. The dated move control calls `POST /api/technicians/{id}/depot-assignments` with `{ "depotId": "...", "effectiveDate": "YYYY-MM-DD" }`. Its destination choices use the assignment effective on the proposed move date. A depot policy edit calls `PUT /api/depots/{id}/policy`.

Moves stay within one dealership. A same-metro move checks active holds and replans booked routes without changing customer windows. A cross-metro move starts after the last date in the current ten-weekday booking horizon and rejects any booked appointment or active hold on or after the move date. A prior service date keeps its original depot, route policy, and metro. A new optimization preview uses the effective assignment and policy; applying an older preview fails when its configuration fingerprint has changed. Saved before and after geometry keeps its endpoint snapshots.
