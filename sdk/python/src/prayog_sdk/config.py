"""Connection settings, normally from environment variables."""

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    """Where the exchange is and how to authenticate.

    A bot authenticates as a Keycloak *client* (client credentials): its "API key" is the client id
    and secret.
    ``account`` picks one of several accounts under the same client (for example ``mm`` and
    ``taker``); each label
    is a separate account with its own orders and limits.
    """

    api_url: str = "http://api.prayog.localhost"
    auth_url: str = "http://auth.prayog.localhost/realms/prayog"
    client_id: str = "prayog-bot-demo"
    client_secret: str = ""
    account: str = "main"
    timeout_seconds: float = 10.0

    @staticmethod
    def from_env(**overrides: str) -> "Settings":
        """Reads ``PRAYOG_API_URL``, ``PRAYOG_AUTH_URL``, ``PRAYOG_CLIENT_ID``,
        ``PRAYOG_CLIENT_SECRET`` and
        ``PRAYOG_ACCOUNT``. For the demo bot the secret is ``PRAYOG_BOT_DEMO_SECRET`` from the
        repository's .env,
        used as a fallback."""
        values = {
            "api_url": os.environ.get("PRAYOG_API_URL", Settings.api_url),
            "auth_url": os.environ.get("PRAYOG_AUTH_URL", Settings.auth_url),
            "client_id": os.environ.get("PRAYOG_CLIENT_ID", Settings.client_id),
            "client_secret": os.environ.get(
                "PRAYOG_CLIENT_SECRET", os.environ.get("PRAYOG_BOT_DEMO_SECRET", "")
            ),
            "account": os.environ.get("PRAYOG_ACCOUNT", Settings.account),
        }
        values.update(overrides)
        return Settings(**values)

    @property
    def ws_url(self) -> str:
        return self.api_url.replace("https://", "wss://").replace("http://", "ws://")
