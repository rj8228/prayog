# 3. Browser tour

Open each address in Chrome or Firefox.

| Address | You should see |
|---|---|
| http://traefik.prayog.localhost/dashboard/ | Traefik dashboard. HTTP → Routers lists `auth`, `app`, `api`, `dashboard`, all green |
| http://app.prayog.localhost | Plain text starting `Hostname: ...` and `Host: app.prayog.localhost` (the placeholder echoes your request) |
| http://api.prayog.localhost | The same, with `Host: api.prayog.localhost` |
| http://nope.prayog.localhost | `404 page not found` (Traefik: no router for that host) |
| http://auth.prayog.localhost/realms/prayog/account | Keycloak sign-in page titled "Prayog" |
| ... sign in as `trader1` (or `ops1`) | The account page for "Trader One" (or "Ops One"). No "update your profile" step |
| http://auth.prayog.localhost/admin | Admin sign-in. Use `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`. A yellow "temporary admin user" banner is expected for a local setup |
| ... left menu **Manage realms** → **prayog** | The console now works on realm `prayog` (it starts on `master`, Keycloak's own realm) |
| ... **Clients**, **Realm roles**, **Users** | Clients: `prayog-web`, `prayog-api`, `prayog-bot-demo`, `prayog-agents`. Realm roles include `trader`, `ops`, `bot`. Users: `trader1`, `ops1` |

Find the passwords with:

```sh
grep -E 'KEYCLOAK_ADMIN|TRADER1|OPS1' .env
```

Sign out of the account page before signing in as another user (top right menu).
