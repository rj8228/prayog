# 4. Identity and tokens

Load the secrets first: `set -a; source .env; set +a`

## Get a bot token (client credentials)

```sh
curl -s -d grant_type=client_credentials -d client_id=prayog-bot-demo \
  -d client_secret="$PRAYOG_BOT_DEMO_SECRET" \
  http://auth.prayog.localhost/realms/prayog/protocol/openid-connect/token | python3 -m json.tool
```

Expect JSON with `access_token`, `expires_in: 300` and `token_type: Bearer`. The same works for `prayog-agents` with
`$PRAYOG_AGENTS_SECRET`.

## Look inside it

Save it, then print the claims:

```sh
TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=prayog-bot-demo -d client_secret="$PRAYOG_BOT_DEMO_SECRET" \
  http://auth.prayog.localhost/realms/prayog/protocol/openid-connect/token | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
python3 -c 'import base64,json,sys; p=sys.argv[1].split(".")[1]; p+="="*(-len(p)%4); print(json.dumps(json.loads(base64.urlsafe_b64decode(p)), indent=2))' "$TOKEN"
```

Expect `"iss": "http://auth.prayog.localhost/realms/prayog"`, `"aud"` containing `prayog-api`, `"azp": "prayog-bot-demo"`
and `"realm_access": {"roles": [... "bot" ...]}`. Or paste the token into section 7 of `docs/overview/oauth-oidc.html`
(decoding stays in your browser).

## Things that must fail

| Try | Expect |
|---|---|
| Wrong secret: `-d client_secret=wrong` | HTTP 401, `"error": "unauthorized_client"` |
| Password grant for the web client: `-d grant_type=password -d client_id=prayog-web -d username=trader1 -d password="$PRAYOG_TRADER1_PASSWORD"` | HTTP 400, `unauthorized_client`, "Client not allowed for direct access grants": the web client may only use the browser flow with PKCE |

## Admin API (read-only look)

```sh
ADMIN=$(curl -s -d grant_type=password -d client_id=admin-cli -d username="$KEYCLOAK_ADMIN" -d password="$KEYCLOAK_ADMIN_PASSWORD" \
  http://auth.prayog.localhost/realms/master/protocol/openid-connect/token | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
curl -s -H "Authorization: Bearer $ADMIN" http://auth.prayog.localhost/admin/realms/prayog/users | python3 -m json.tool | grep '"username"'
```

Expect `trader1` and `ops1`. The bots' `service-account-...` users exist too, but Keycloak leaves service accounts out of this list (see them under Clients → the bot client → Service account roles in the admin console).
