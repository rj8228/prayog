"""Access tokens for bots: OAuth2 client credentials against Keycloak, cached until shortly before
expiry."""

import asyncio
import time

import httpx

from prayog_sdk.config import Settings


class AuthError(Exception):
    """Keycloak refused the credentials (wrong client id or secret, or the client is disabled)."""


class TokenProvider:
    """Hands out a valid access token, fetching a new one 30 s before the current one expires
    (tokens live 5 min)."""

    REFRESH_MARGIN_SECONDS = 30

    def __init__(self, settings: Settings, http: httpx.AsyncClient, clock=time.monotonic) -> None:
        self._settings = settings
        self._http = http
        self._clock = clock
        self._token: str | None = None
        self._expires_at = 0.0
        self._lock = asyncio.Lock()

    async def token(self) -> str:
        async with self._lock:  # one refresh at a time, even with many concurrent requests
            if (
                self._token is None
                or self._clock() >= self._expires_at - self.REFRESH_MARGIN_SECONDS
            ):
                await self._refresh()
            assert self._token is not None
            return self._token

    def invalidate(self) -> None:
        """Forget the cached token (after a 401), so the next call fetches a fresh one."""
        self._token = None

    async def _refresh(self) -> None:
        response = await self._http.post(
            f"{self._settings.auth_url}/protocol/openid-connect/token",
            data={
                "grant_type": "client_credentials",
                "client_id": self._settings.client_id,
                "client_secret": self._settings.client_secret,
            },
        )
        if response.status_code != 200:
            raise AuthError(f"token request failed ({response.status_code}): {response.text}")
        body = response.json()
        self._token = body["access_token"]
        self._expires_at = self._clock() + float(body.get("expires_in", 300))
