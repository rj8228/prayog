import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { userManager } from './auth/auth'

/** Keycloak sends the browser back here with a one-time code; exchange it (with the PKCE verifier) for tokens. */
export function CallbackPage() {
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    userManager
      .signinRedirectCallback()
      .then((user) =>
        navigate(typeof user.state === 'string' ? user.state : '/', {
          replace: true,
        }),
      )
      .catch((e: unknown) => setError(String(e)))
  }, [navigate])
  return (
    <p className="muted pad">
      {error ? `Sign-in failed: ${error}` : 'Signing you in...'}
    </p>
  )
}
