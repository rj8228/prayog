#!/usr/bin/env bash
# S2 acceptance check for a running stack (`make up` first). Exits non-zero if any check fails.
#
# It checks what a later service will rely on: each subdomain answers through Traefik, Keycloak issues real tokens
# with the right issuer, audience and roles, the seeded users exist, and PostgreSQL, Redis and Kafka accept work.
set -uo pipefail
cd "$(dirname "$0")/../.."

COMPOSE=(docker compose --env-file .env -f deploy/compose/compose.yaml)
set -a; . ./.env; set +a

failures=0
pass() { printf '  \033[32mok\033[0m    %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; failures=$((failures + 1)); }
check() { local name=$1; shift; if "$@" >/dev/null 2>&1; then pass "$name"; else fail "$name"; fi; }

# curl resolves *.localhost to the loopback address itself, so no /etc/hosts entry is needed here.
http_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$1"; }
body_has() { curl -s --max-time 10 "$1" | grep -q "$2"; }
# Prints one claim of a JWT's payload (base64url JSON) as JSON.
claim() { python3 -c '
import base64, json, sys
payload = sys.argv[1].split(".")[1]
payload += "=" * (-len(payload) % 4)
print(json.dumps(json.loads(base64.urlsafe_b64decode(payload)).get(sys.argv[2])))' "$1" "$2"; }
json_field() { python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1], ""))' "$1"; }

AUTH=http://auth.prayog.localhost
REALM=$AUTH/realms/prayog

echo "Edge (Traefik)"
check "app.prayog.localhost serves the web app" body_has http://app.prayog.localhost "<title>Prayog"
check "web app routes like /callback fall back to the app" test "$(http_code http://app.prayog.localhost/callback)" = 200
check "traefik.prayog.localhost dashboard answers" test "$(http_code http://traefik.prayog.localhost/dashboard/)" = 200
check "unknown host gets 404 from Traefik" test "$(http_code http://nope.prayog.localhost/)" = 404

echo "Identity (Keycloak)"
issuer=$(curl -s --max-time 10 "$REALM/.well-known/openid-configuration" | json_field issuer)
check "auth.prayog.localhost serves realm prayog, issuer $issuer" test "$issuer" = "$REALM"

token_response=$(curl -s --max-time 10 -d grant_type=client_credentials \
  -d client_id=prayog-bot-demo -d client_secret="$PRAYOG_BOT_DEMO_SECRET" "$REALM/protocol/openid-connect/token")
token=$(printf '%s' "$token_response" | json_field access_token)
if [ -n "$token" ]; then
  pass "prayog-bot-demo gets a token (client credentials)"
  check "token issuer is $REALM" test "$(claim "$token" iss)" = "\"$REALM\""
  check "token audience includes prayog-api" sh -c "echo '$(claim "$token" aud)' | grep -q prayog-api"
  check "token carries realm role bot" sh -c "echo '$(claim "$token" realm_access)' | grep -q '\"bot\"'"
else
  fail "prayog-bot-demo gets a token: $token_response"
fi
agents=$(curl -s --max-time 10 -d grant_type=client_credentials -d client_id=prayog-agents \
  -d client_secret="$PRAYOG_AGENTS_SECRET" "$REALM/protocol/openid-connect/token" | json_field access_token)
check "prayog-agents gets a token (client credentials)" test -n "$agents"
check "a wrong client secret is refused (401)" test "$(curl -s -o /dev/null -w '%{http_code}' -d grant_type=client_credentials \
  -d client_id=prayog-bot-demo -d client_secret=wrong "$REALM/protocol/openid-connect/token")" = 401

admin=$(curl -s --max-time 10 -d grant_type=password -d client_id=admin-cli -d username="$KEYCLOAK_ADMIN" \
  -d password="$KEYCLOAK_ADMIN_PASSWORD" "$AUTH/realms/master/protocol/openid-connect/token" | json_field access_token)
user_has_role() {
  local id
  id=$(curl -s -H "Authorization: Bearer $admin" "$AUTH/admin/realms/prayog/users?exact=true&username=$1" |
    python3 -c 'import json,sys; u=json.load(sys.stdin); print(u[0]["id"] if u else "")')
  [ -n "$id" ] && curl -s -H "Authorization: Bearer $admin" \
    "$AUTH/admin/realms/prayog/users/$id/role-mappings/realm" | grep -q "\"name\":\"$2\""
}
check "user trader1 exists with role trader" user_has_role trader1 trader
check "user ops1 exists with role ops" user_has_role ops1 ops
check "client prayog-web is public with PKCE S256" sh -c "curl -s -H 'Authorization: Bearer $admin' \
  '$AUTH/admin/realms/prayog/clients?clientId=prayog-web' | grep -q '\"pkce.code.challenge.method\":\"S256\"'"

echo "Exchange"
API=http://api.prayog.localhost/api/v1
check "exchange is healthy (readiness UP)" body_has http://api.prayog.localhost/actuator/health/readiness '"UP"'
check "api.prayog.localhost lists 4 instruments" sh -c "curl -s $API/instruments | python3 -c 'import json,sys; assert len(json.load(sys.stdin)) == 4'"
check "app.prayog.localhost/api reaches the exchange too" body_has http://app.prayog.localhost/api/v1/session '"state"'
session=$(curl -s "$API/session" | json_field state)
check "orders without a token are refused (401)" test "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/orders")" = 401
if [ -n "$token" ]; then
  placed=$(curl -s -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d '{"symbol":"INFY","side":"BUY","type":"LIMIT","price":140000,"quantity":1,"clientOrderId":"smoke"}' "$API/orders")
  status=$(printf '%s' "$placed" | json_field status)
  if [ "$session" = OPEN ]; then
    check "bot order rests on the book (session OPEN)" test "$status" = resting
    order_id=$(printf '%s' "$placed" | json_field orderId)
    check "bot order is listed as open" sh -c "curl -s -H 'Authorization: Bearer $token' $API/orders | grep -q '\"orderId\":$order_id'"
    check "bot order cancels" sh -c "curl -s -X DELETE -H 'Authorization: Bearer $token' $API/orders/$order_id | grep -q '\"status\":\"cancelled\"'"
  else
    check "bot order is answered (session $session: rejected SESSION_NOT_OPEN)" test "$status" = rejected
  fi
fi

echo "Data and messaging"
check "PostgreSQL: prayog database answers SELECT 1" \
  "${COMPOSE[@]}" exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc 'SELECT 1'
check "PostgreSQL: keycloak database exists, owned by keycloak" sh -c "$(printf '%q ' "${COMPOSE[@]}") exec -T postgres \
  psql -U '$POSTGRES_USER' -d '$POSTGRES_DB' -tAc \"SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='keycloak'\" | grep -qx keycloak"
check "Redis answers PING" sh -c "$(printf '%q ' "${COMPOSE[@]}") exec -T redis redis-cli ping | grep -qx PONG"
check "Kafka topic prayog.exchange.events.v1 has 3 partitions" sh -c "$(printf '%q ' "${COMPOSE[@]}") exec -T kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic prayog.exchange.events.v1 | grep -q 'PartitionCount: 3'"
check "Kafka host listener (localhost:9094) answers" "${COMPOSE[@]}" exec -T kafka \
  /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9094
check "Kafka auto topic creation is off" sh -c "$(printf '%q ' "${COMPOSE[@]}") exec -T kafka \
  /opt/kafka/bin/kafka-configs.sh --bootstrap-server kafka:9092 --entity-type brokers --entity-name 1 --describe --all |
  grep -q 'auto.create.topics.enable=false'"

echo
if [ "$failures" -eq 0 ]; then echo "All checks passed."; else echo "$failures check(s) failed."; exit 1; fi
