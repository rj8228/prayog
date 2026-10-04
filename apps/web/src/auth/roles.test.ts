import { describe, expect, it } from 'vitest'
import { rolesOf } from './roles'

const token = (claims: object) =>
  `x.${btoa(JSON.stringify(claims)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')}.sig`

describe('rolesOf', () => {
  it('reads realm roles from the access token', () => {
    expect(
      rolesOf(token({ realm_access: { roles: ['trader', 'admin'] } })),
    ).toEqual(['trader', 'admin'])
  })

  it('is empty without a token or roles, or for garbage', () => {
    expect(rolesOf(null)).toEqual([])
    expect(rolesOf(token({ sub: 'x' }))).toEqual([])
    expect(rolesOf('not-a-jwt')).toEqual([])
  })
})
