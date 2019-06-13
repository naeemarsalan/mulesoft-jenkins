#!/bin/bash
# Expected env vars
# ANYPOINT_USERNAME
# ANYPOINT_PASSWORD
# ANYPOINT_ENVIRONMENT

set -euo pipefail
TIMESTAMP=$(date +"%s%N" | cut -b1-13)
ANYPOINT_ACCOUNT_ENDPOINT=https://anypoint.mulesoft.com/accounts
ANYPOINT_API_ENDPOINT=https://anypoint.mulesoft.com/hybrid/api/v1/
ANYPOINT_USERNAME=$1
ANYPOINT_PASSWORD=$2
ANYPOINT_ENVIRONMENT=$3


#######################################
# Anypoint API methods
#######################################
function clean_old_registrations() {
  server_list=$(get_servers)
  #group_id="$(get_group_id "${SERVER_GROUP}")"
  delete_dead_servers "${server_list}"
  #delete_existing_server "${server_list}" "${group_id}"
}
function get_servers() {
  curl -sSL -X GET \
    -H "${global_auth_header}" \
    -H "X-ANYPNT-ORG-ID: ${global_org_id}" \
    -H "X-ANYPNT-ENV-ID: ${global_env_id}" \
    "${ANYPOINT_API_ENDPOINT}/servers"
}
function get_auth_header() {
  curl -sSL -X POST \
      -H "Content-Type: application/json" \
      -d "{\"username\":\"${ANYPOINT_USERNAME}\",\"password\":\"${ANYPOINT_PASSWORD}\"}" \
      "${ANYPOINT_ACCOUNT_ENDPOINT}/login" \
    | jq -r '"Authorization: \(.token_type) \(.access_token)"'
}
function get_env() {
  curl -sSL -X GET \
    -H "${global_auth_header}" \
    "${ANYPOINT_ACCOUNT_ENDPOINT}/api/organizations/${global_org_id}/environments" \
  | jq -r ".data[] | select(.name == \"${ANYPOINT_ENVIRONMENT}\") | .id"
}

function get_org_id() {
  curl -sSL -X GET \
    -H "${global_auth_header}" \
    "${ANYPOINT_ACCOUNT_ENDPOINT}/api/me" \
  | jq -r '.user.organization.id'
}

function delete_dead_servers() {
  servers=$1
  # Note: This will not delete servers that are created, but never started
  #       Only servers that ran at one point, then stopped (old pods)
  echo "Checking for old disconnected servers"
  #ten_minutes_ago=$(( TIMESTAMP - 600000 ))
  dead_servers=$(echo "${servers}" | \
    jq ".data[] | select(.status == \"DISCONNECTED\").id")

  for server_id in $dead_servers; do
    name=$(echo "${servers}" | jq ".data[] | select(.id == ${server_id}).name")
    echo "Removing old disconnected server id: ${name}"
    remove_server_entry "${server_id}"
  done
}
function remove_server_entry() {
  local server_id=$1
  curl -sSL -X DELETE \
      -H "${global_auth_header}" \
      -H "X-ANYPNT-ORG-ID: ${global_org_id}" \
      -H "X-ANYPNT-ENV-ID: ${global_env_id}" \
      "${ANYPOINT_API_ENDPOINT}/servers/${server_id}" >/dev/null
}



function set_global_auth_details() {
  # Global variables, because passing these all over is painful
  echo "Authenticating with Anypoint using account: ${ANYPOINT_USERNAME}"
  global_auth_header="$(get_auth_header)"
  global_org_id="$(get_org_id "${global_auth_header}")"
  global_env_id="$(get_env "${global_auth_header}" "${global_org_id}")"
  echo "Authentication details obtained"
}

function main() {
  set_global_auth_details
  clean_old_registrations

}

main
