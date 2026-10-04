#!/usr/bin/env bash
# Makes sure the realm has every role and seeded user it should, on a RUNNING stack, without deleting data.
# The realm file is imported only on an empty database (ADR 0008); this script brings older installs up to date
# through Keycloak's admin API. Safe to run any number of times. Run by `make up`; also `make users`.
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a

KC=http://auth.prayog.localhost
REALM=$KC/admin/realms/prayog
token=$(curl -sf -d grant_type=password -d client_id=admin-cli -d username="$KEYCLOAK_ADMIN" \
  -d password="$KEYCLOAK_ADMIN_PASSWORD" "$KC/realms/master/protocol/openid-connect/token" |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
auth=(-H "Authorization: Bearer $token")
json=(-H "Content-Type: application/json")

ensure_role() { # name description
  if ! curl -sf "${auth[@]}" "$REALM/roles/$1" >/dev/null; then
    curl -sf "${auth[@]}" "${json[@]}" -d "{\"name\":\"$1\",\"description\":\"$2\"}" "$REALM/roles" >/dev/null
    echo "  created role $1"
  fi
}

user_id() {
  curl -sf "${auth[@]}" "$REALM/users?exact=true&username=$1" |
    python3 -c 'import json,sys; u=json.load(sys.stdin); print(u[0]["id"] if u else "")'
}

ensure_user() { # username first last password roles...
  local username=$1 first=$2 last=$3 password=$4; shift 4
  local id; id=$(user_id "$username")
  if [ -z "$id" ]; then
    curl -sf "${auth[@]}" "${json[@]}" -d "{\"username\":\"$username\",\"enabled\":true,\"email\":\"$username@prayog.localhost\",
      \"emailVerified\":true,\"firstName\":\"$first\",\"lastName\":\"$last\"}" "$REALM/users" >/dev/null
    id=$(user_id "$username")
    echo "  created user $username"
  fi
  # Keep the password in step with .env.
  curl -sf -X PUT "${auth[@]}" "${json[@]}" -d "{\"type\":\"password\",\"value\":\"$password\",\"temporary\":false}" \
    "$REALM/users/$id/reset-password" >/dev/null
  local roles="[" sep=""
  for role in "$@"; do
    roles+="$sep$(curl -sf "${auth[@]}" "$REALM/roles/$role")"; sep=","
  done
  roles+="]"
  curl -sf "${auth[@]}" "${json[@]}" -d "$roles" "$REALM/users/$id/role-mappings/realm" >/dev/null
}

echo "Keycloak realm prayog: roles and users"
ensure_role admin "Admin console: self-test, simulation control, accounts, journal replay"
ensure_user admin1 Admin One "$PRAYOG_ADMIN1_PASSWORD" trader ops admin
echo "  admin1 ready (roles trader, ops, admin)"

# An optional personal admin, named in .env only (PRAYOG_EXTRA_ADMIN_USER and PRAYOG_EXTRA_ADMIN_PASSWORD).
if [ -n "${PRAYOG_EXTRA_ADMIN_USER:-}" ] && [ -n "${PRAYOG_EXTRA_ADMIN_PASSWORD:-}" ]; then
  ensure_user "$PRAYOG_EXTRA_ADMIN_USER" Admin "$PRAYOG_EXTRA_ADMIN_USER" "$PRAYOG_EXTRA_ADMIN_PASSWORD" trader ops admin
  echo "  $PRAYOG_EXTRA_ADMIN_USER ready (roles trader, ops, admin)"
fi
