#!/usr/bin/env bash
# Walks through the main flows against a running stack (docker compose up --build).
# Needs: curl, jq, python3.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
ACME=11111111-1111-1111-1111-111111111111
GLOBEX=22222222-2222-2222-2222-222222222222
DIR="$(cd "$(dirname "$0")" && pwd)"
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)

step() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
tok() { curl -sf "$BASE/dev/token" -H 'Content-Type: application/json' \
          -d "{\"tenantId\":\"$1\",\"roles\":[\"$2\"],\"subject\":\"$3\"${4:+,\"employeeId\":\"$4\"}}" | jq -r .token; }
events() { curl -s "$BASE/api/v1/roster/events" -H "Authorization: Bearer $HR" -H 'Content-Type: application/json' -d "$1"; }
get_emp() { curl -s "$BASE/api/v1/employees/$1" -H "Authorization: Bearer $ADMIN"; }
ev() { # type ext version office shift phone
  echo "{\"type\":\"$1\",\"externalId\":\"$2\",\"sourceVersion\":$3,\"occurredAt\":\"$NOW\",\"hr\":{\"name\":\"Asha Rao\",\"email\":\"asha.rao@acme.test\",\"phone\":\"$6\",\"officeCode\":\"$4\",\"shiftCode\":\"$5\"}}"; }

step "Tokens (demo issuer): HR system, transport admin, operator, admin of another tenant"
HR=$(tok $ACME HR_SYSTEM hr-feed); ADMIN=$(tok $ACME TRANSPORT_ADMIN admin-1); OPS=$(tok $ACME OPERATOR oncall-1)
OTHER=$(tok $GLOBEX TRANSPORT_ADMIN globex-admin)
echo "ok"

step "1. Batch with one malformed phone and one unknown office: per-record report, others applied"
events "{\"batchId\":\"$(uuidgen 2>/dev/null || python3 -c 'import uuid;print(uuid.uuid4())')\",\"events\":[
  $(ev CREATE A100 5 BLR-01 S1 +919876543210), $(ev CREATE A101 1 BLR-01 S1 12-bad), $(ev CREATE A102 1 MARS-9 S1 +919876543210)]}" | jq '{summary, results: [.results[] | {index, externalId, outcome, errors}]}'

step "2. Out of order: transfer v7 arrives, then shift change v6 (stale)"
B2="{\"batchId\":\"$(python3 -c 'import uuid;print(uuid.uuid4())')\",\"events\":[$(ev UPDATE A100 7 BLR-02 S3 +919876543210), $(ev UPDATE A100 6 BLR-01 S2 +919876543210)]}"
events "$B2" | jq -c '[.results[] | {externalId, v: .index, outcome}]'
get_emp A100 | jq '{externalId, officeCode, shiftCode, hrVersion}'

step "3. Replay the same batch: all duplicate/stale, nothing changes"
events "$B2" | jq -c .summary

step "4. Transport admin sets pickup; an HR update carrying a pickup field cannot overwrite it"
curl -s -X PATCH "$BASE/api/v1/employees/A100/transport" -H "Authorization: Bearer $ADMIN" -H 'If-Match: "0"' \
  -H 'Content-Type: application/json' -d '{"pickupPointId":"PP-KORAMANGALA-3","specialAssistance":"WHEELCHAIR"}' | jq -c '{pickupPointId, specialAssistance, adminVersion}'
events "{\"batchId\":\"$(python3 -c 'import uuid;print(uuid.uuid4())')\",\"events\":[{\"type\":\"UPDATE\",\"externalId\":\"A100\",\"sourceVersion\":8,\"occurredAt\":\"$NOW\",\"hr\":{\"name\":\"Asha Rao\",\"email\":\"asha.rao@acme.test\",\"officeCode\":\"HYD-01\",\"shiftCode\":\"S3\",\"pickupPointId\":\"HR-WRONG\"}}]}" \
  | jq -c '.results[0] | {outcome, warnings}'
get_emp A100 | jq -c '{officeCode, pickupPointId, pickupNeedsReview}'

step "5. Deactivation cancels future bookings and frees seats"
curl -s "$BASE/api/v1/bookings" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"externalId\":\"A100\",\"pickupAt\":\"$(date -u -d '+1 day' +%Y-%m-%dT%H:%M:%SZ)\"}" | jq -c '{bookingId, status}'
events "{\"batchId\":\"$(python3 -c 'import uuid;print(uuid.uuid4())')\",\"events\":[{\"type\":\"DEACTIVATE\",\"externalId\":\"A100\",\"sourceVersion\":9,\"occurredAt\":\"$NOW\"}]}" | jq -c .summary
curl -s "$BASE/api/v1/employees/A100/bookings" -H "Authorization: Bearer $ADMIN" | jq -c '[.[] | {status, cancelReason}]'

step "6. Seed 1,000 employees for the nightly run"
for s in 0 250 500 750; do python3 "$DIR/demo_data.py" batch $s 250 | curl -s "$BASE/api/v1/roster/events" \
  -H "Authorization: Bearer $HR" -H 'Content-Type: application/json' -d @- | jq -c .summary; done

step "7. FAILURE: nightly export cut off at 600 of 1,000 rows -> safety brake, nobody deactivated"
RUN=$(python3 "$DIR/demo_data.py" snapshot 1000 600 --cut | curl -s "$BASE/api/v1/roster/snapshots" \
  -H "Authorization: Bearer $HR" -H 'Content-Type: text/csv' --data-binary @- | jq -r .runId)
sleep 2
curl -s "$BASE/api/v1/reconciliation/runs/$RUN" -H "Authorization: Bearer $OPS" \
  | jq '{status, brakeReasons, rowsRead, expectedRows, fileAppearsShortBy, appearsMissing, missingRatio, threshold, deactivated, sampleMissingEmployeeIds: .sampleMissingEmployeeIds[:5]}'
curl -s "$BASE/actuator/prometheus" | grep -E '^roster_reconciliation_(held_runs|brake_tripped_total)' || true
echo "operator rejects the broken file:"
curl -s -X POST "$BASE/api/v1/reconciliation/runs/$RUN/reject" -H "Authorization: Bearer $OPS" | jq -c '{status, decidedBy}'

step "8. Full export next night (2 leavers missing): applied normally"
RUN2=$(python3 "$DIR/demo_data.py" snapshot 1000 998 | curl -s "$BASE/api/v1/roster/snapshots" \
  -H "Authorization: Bearer $HR" -H 'Content-Type: text/csv' --data-binary @- | jq -r .runId)
sleep 3
curl -s "$BASE/api/v1/reconciliation/runs/$RUN2" -H "Authorization: Bearer $OPS" | jq -c '{status, appearsMissing, deactivated, diffMillis}'

step "9. Tenant isolation: another tenant's admin gets 404"
curl -s "$BASE/api/v1/employees/A100" -H "Authorization: Bearer $OTHER" | jq -c '{status, code}'

step "10. On-call counters"
curl -s "$BASE/actuator/prometheus" | grep -E '^roster_(ingest_events_total|outbox_pending|outbox_oldest_age_seconds|ingest_rejections_total)' | head -20
