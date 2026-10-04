// Realm roles live in the access token (realm_access.roles). The token is only decoded here to decide which screens
// to show; the exchange checks the signature and roles on every call.
export function rolesOf(token: string | null): string[] {
  if (!token) return []
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const claims = JSON.parse(
      atob(payload.padEnd(Math.ceil(payload.length / 4) * 4, '=')),
    ) as {
      realm_access?: { roles?: string[] }
    }
    return claims.realm_access?.roles ?? []
  } catch {
    return []
  }
}
