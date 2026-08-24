#!/usr/bin/env bash
set -euo pipefail

FIQ_URL="${FIQ_URL:-http://localhost:9091}"
FIQ_WORKSPACE="${FIQ_WORKSPACE:-00000000-0000-0000-0000-000000000001}"
API="$FIQ_URL/api/v1"
HEADER=(-H "Content-Type: application/json" -H "X-FIQ-Workspace: $FIQ_WORKSPACE")

command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }

api() {
  curl --fail --silent --show-error "${HEADER[@]}" "$@"
}

echo "Waiting for FIQ and automatic sample discovery..."
deadline=$((SECONDS + 900))
tables=''
while (( SECONDS < deadline )); do
  if api "$API/bootstrap" >/dev/null 2>&1; then
    tables="$(api "$API/tables?limit=100")"
    path_id="$(jq -r '.items[] | select(.sample and .executionTarget.type == "PATH" and (.qualifiedName | contains("small_files"))) | .id' <<<"$tables" | head -1)"
    hms_id="$(jq -r '.items[] | select(.sample and .executionTarget.type == "CATALOG" and (.qualifiedName | contains("small_files"))) | .id' <<<"$tables" | head -1)"
    vacuum_id="$(jq -r '.items[] | select(.sample and .executionTarget.type == "PATH" and (.qualifiedName | contains("vacuum"))) | .id' <<<"$tables" | head -1)"
    if [[ -n "$path_id" && -n "$hms_id" && -n "$vacuum_id" ]]; then break; fi
  fi
  sleep 5
done

[[ -n "${path_id:-}" ]] || { echo "PATH sample was not discovered" >&2; exit 1; }
[[ -n "${hms_id:-}" ]] || { echo "HMS sample was not discovered" >&2; exit 1; }
[[ -n "${vacuum_id:-}" ]] || { echo "VACUUM sample was not discovered" >&2; exit 1; }
echo "Discovery proved PATH, HMS catalog, and isolated VACUUM sample targets."

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
optimize_policy="$(jq -n --arg name "Acceptance optimize $stamp" '{
  name:$name, description:"Compose acceptance policy", enabled:true,
  selector:{environmentGlob:"*",catalogGlob:"*",namespaceGlob:"*",tableGlob:"*",requiredTags:{}},
  cron:"0 0 2 * * ?", timezone:"UTC",
  maintenanceWindow:{days:["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"],start:"00:00",end:"23:59:59"},
  operations:["OPTIMIZE_BINPACK"],
  operationConfigs:{OPTIMIZE_BINPACK:{retentionHours:168,zOrderColumns:[],predicate:"",inventoryTable:"",automatic:false,approvalRequired:false,
    fileLayoutPolicy:{smallFileThresholdBytes:134217728,minimumSmallFileCount:20,minimumSmallFileRatio:0.30,minimumRewriteBytes:0,targetFileSizeBytes:1073741824,minimumExpectedReductionRatio:0},
    vacuumPolicy:{minimumCandidateCount:1,minimumReclaimableBytes:0}}},
  maxBytesPerRun:0,maxConcurrentOperations:1,requireApprovalAboveBudget:true
}')"
optimize_policy_id="$(api -X POST --data "$optimize_policy" "$API/policies" | jq -r .id)"

wait_operation() {
  local operation_id="$1" response state
  local operation_deadline=$((SECONDS + 1200))
  while (( SECONDS < operation_deadline )); do
    response="$(api "$API/operations/$operation_id")"
    state="$(jq -r .state <<<"$response")"
    case "$state" in
      SUCCEEDED) printf '%s' "$response"; return 0 ;;
      FAILED|SKIPPED|CANCELLED) jq . <<<"$response" >&2; return 1 ;;
    esac
    sleep 5
  done
  echo "Operation $operation_id timed out" >&2
  return 1
}

run_optimize() {
  local table_id="$1" expected_target="$2" plan operation_id result
  api -X POST "$API/tables/$table_id/health/refresh" >/dev/null
  plan="$(api -X POST --data "$(jq -n --arg t "$table_id" --arg p "$optimize_policy_id" --arg k "acceptance:$stamp:$table_id" '{tableId:$t,policyId:$p,operationType:"OPTIMIZE_BINPACK",idempotencyKey:$k}')" "$API/operations/plan")"
  [[ "$(jq -r .executionTarget.type <<<"$plan")" == "$expected_target" ]] || { echo "Wrong execution target" >&2; return 1; }
  operation_id="$(jq -r .id <<<"$plan")"
  api -X POST "$API/operations/$operation_id/execute" >/dev/null
  result="$(wait_operation "$operation_id")"
  jq -e '.verificationEvidence.activeFilesAfter < .verificationEvidence.activeFilesBefore and .verificationEvidence.smallFilesAfter < .verificationEvidence.smallFilesBefore' <<<"$result" >/dev/null
  echo "OPTIMIZE verified for $expected_target target: $(jq -c .verificationEvidence <<<"$result")"
}

run_optimize "$path_id" PATH
run_optimize "$hms_id" CATALOG

vacuum_policy="$(jq -n --arg name "Acceptance vacuum $stamp" '{
  name:$name, description:"Isolated sample zero-hour VACUUM acceptance", enabled:true,
  selector:{environmentGlob:"*",catalogGlob:"*",namespaceGlob:"*",tableGlob:"*",requiredTags:{sample:"true"}},
  cron:"0 0 3 * * ?", timezone:"UTC",
  maintenanceWindow:{days:["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"],start:"00:00",end:"23:59:59"},
  operations:["VACUUM_FULL"],
  operationConfigs:{VACUUM_FULL:{retentionHours:0,zOrderColumns:[],predicate:"",inventoryTable:"",automatic:false,approvalRequired:true,
    fileLayoutPolicy:{smallFileThresholdBytes:134217728,minimumSmallFileCount:20,minimumSmallFileRatio:0.30,minimumRewriteBytes:0,targetFileSizeBytes:1073741824,minimumExpectedReductionRatio:0},
    vacuumPolicy:{minimumCandidateCount:1,minimumReclaimableBytes:0}}},
  maxBytesPerRun:0,maxConcurrentOperations:1,requireApprovalAboveBudget:true
}')"
vacuum_policy_id="$(api -X POST --data "$vacuum_policy" "$API/policies" | jq -r .id)"
api -X POST "$API/tables/$vacuum_id/health/refresh" >/dev/null
vacuum_plan="$(api -X POST --data "$(jq -n --arg t "$vacuum_id" --arg p "$vacuum_policy_id" --arg k "acceptance:$stamp:vacuum" '{tableId:$t,policyId:$p,operationType:"VACUUM_FULL",idempotencyKey:$k}')" "$API/operations/plan")"
jq -e '.state == "AWAITING_APPROVAL" and .preflightEvidence.candidateCount > 0 and (.preflightEvidence.candidateHash | length == 64)' <<<"$vacuum_plan" >/dev/null
vacuum_operation_id="$(jq -r .id <<<"$vacuum_plan")"
api -X POST --data '{"decision":"APPROVE","comment":"Isolated sample acceptance only"}' "$API/operations/$vacuum_operation_id/approval" >/dev/null
vacuum_result="$(wait_operation "$vacuum_operation_id")"
jq -e '.maintenanceApplied and .verificationEvidence.remainingApprovedCandidates == 0' <<<"$vacuum_result" >/dev/null
echo "VACUUM FULL dry-run/approval/apply/verify succeeded."
echo "FIQ Phase 1 Classic Delta core acceptance passed."
