import type { User } from 'oidc-client-ts'
import { useEffect, useState, type ReactNode } from 'react'
import { AuthContext } from './AuthContext'
import { currentUser, userManager } from './auth'

/** Keeps the signed-in user (if any) in React state, following silent renewals and sign-outs. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)

  useEffect(() => {
    let active = true
    void currentUser().then((u) => active && setUser(u))
    const loaded = (u: User) => setUser(u)
    const unloaded = () => setUser(null)
    userManager.events.addUserLoaded(loaded)
    userManager.events.addUserUnloaded(unloaded)
    userManager.events.addAccessTokenExpired(unloaded)
    return () => {
      active = false
      userManager.events.removeUserLoaded(loaded)
      userManager.events.removeUserUnloaded(unloaded)
      userManager.events.removeAccessTokenExpired(unloaded)
    }
  }, [])

  return (
    <AuthContext.Provider value={{ user, token: user?.access_token ?? null }}>
      {children}
    </AuthContext.Provider>
  )
}
