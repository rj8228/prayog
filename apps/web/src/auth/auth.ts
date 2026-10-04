// Sign-in with Keycloak: authorization code flow with PKCE (ADR 0008). The browser never sees a client secret.
import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'

// app.prayog.localhost -> auth.prayog.localhost, so the same build works wherever the stack is served.
const authority =
  import.meta.env.VITE_AUTH_URL ??
  `${window.location.protocol}//${window.location.host.replace(/^app\./, 'auth.')}/realms/prayog`

export const userManager = new UserManager({
  authority,
  client_id: 'prayog-web',
  redirect_uri: `${window.location.origin}/callback`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: 'code',
  scope: 'openid profile',
  automaticSilentRenew: true, // uses the refresh token before the 5-minute access token expires
  // Tokens live in this tab's session storage: gone when the tab closes, never shared with other sites.
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
})

export async function currentUser(): Promise<User | null> {
  const user = await userManager.getUser()
  return user && !user.expired ? user : null
}

export const signIn = () =>
  userManager.signinRedirect({ state: window.location.pathname })
export const signOut = () => userManager.signoutRedirect()

/**
 * Runs an authenticated API call with a current token. If the exchange answers 401 (expired or rejected token,
 * e.g. after the laptop slept), renews the token silently once with the refresh token and retries.
 */
export async function withToken<T>(
  call: (token: string) => Promise<T>,
): Promise<T> {
  let user = await userManager.getUser()
  if (!user || user.expired) user = await renew()
  try {
    return await call(user.access_token)
  } catch (e) {
    if ((e as { status?: number }).status !== 401) throw e
    return await call((await renew()).access_token)
  }
}

async function renew(): Promise<User> {
  try {
    const user = await userManager.signinSilent()
    if (user) return user
  } catch {
    // fall through
  }
  await userManager.removeUser()
  throw Object.assign(new Error('Your session expired: sign in again'), {
    status: 401,
  })
}
