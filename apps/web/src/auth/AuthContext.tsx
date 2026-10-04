import type { User } from 'oidc-client-ts'
import { createContext } from 'react'

export interface AuthState {
  user: User | null
  token: string | null
}

export const AuthContext = createContext<AuthState>({ user: null, token: null })
