"""REST client. Every order call returns only after the exchange has journaled the request."""

import uuid
from typing import Any

import httpx

from prayog_sdk.auth import TokenProvider
from prayog_sdk.config import Settings
from prayog_sdk.models import (
    Instrument,
    OpenOrder,
    OrderResult,
    SessionInfo,
    Snapshot,
    Ticker,
    TradePrint,
)


class PrayogError(Exception):
    """The exchange answered with an error. ``status`` is the HTTP status, ``error`` the
    machine-readable code."""

    def __init__(self, status: int, error: str, message: str) -> None:
        super().__init__(f"{status} {error}: {message}")
        self.status = status
        self.error = error
        self.message = message


class RateLimitedError(PrayogError):
    """429: this account sent faster than its limit. Slow down and retry."""


class BusyError(PrayogError):
    """503: the exchange's ring is full. Retry shortly."""


class NotFoundError(PrayogError):
    """404: unknown symbol, or no open order with that id for this account."""


class PrayogClient:
    """Async client for one account. Use as ``async with PrayogClient(settings) as client: ...``."""

    def __init__(self, settings: Settings, http: httpx.AsyncClient | None = None) -> None:
        self.settings = settings
        self._http = http or httpx.AsyncClient(timeout=settings.timeout_seconds)
        self._owns_http = http is None
        self.tokens = TokenProvider(settings, self._http)

    async def __aenter__(self) -> "PrayogClient":
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.close()

    async def close(self) -> None:
        if self._owns_http:
            await self._http.aclose()

    # ---- trading

    async def buy_limit(
        self, symbol: str, quantity: int, price: int, client_order_id: str | None = None
    ):
        return await self.place(
            symbol, "BUY", quantity, price=price, client_order_id=client_order_id
        )

    async def sell_limit(
        self, symbol: str, quantity: int, price: int, client_order_id: str | None = None
    ):
        return await self.place(
            symbol, "SELL", quantity, price=price, client_order_id=client_order_id
        )

    async def buy_market(self, symbol: str, quantity: int) -> OrderResult:
        return await self.place(symbol, "BUY", quantity)

    async def sell_market(self, symbol: str, quantity: int) -> OrderResult:
        return await self.place(symbol, "SELL", quantity)

    async def place(
        self,
        symbol: str,
        side: str,
        quantity: int,
        price: int | None = None,
        client_order_id: str | None = None,
    ) -> OrderResult:
        """A LIMIT order if ``price`` (paise) is given, else a MARKET order (never rests: unfilled
        quantity is
        cancelled)."""
        body: dict[str, Any] = {
            "symbol": symbol,
            "side": side,
            "type": "LIMIT" if price is not None else "MARKET",
            "quantity": quantity,
            "clientOrderId": client_order_id or uuid.uuid4().hex,
        }
        if price is not None:
            body["price"] = price
        return OrderResult.model_validate(await self._request("POST", "/api/v1/orders", json=body))

    async def cancel(self, order_id: int) -> OrderResult:
        return OrderResult.model_validate(
            await self._request("DELETE", f"/api/v1/orders/{order_id}")
        )

    async def modify(self, order_id: int, price: int, quantity: int) -> OrderResult:
        """New price and new *total* quantity (including what already filled). Same price and less
        quantity keeps
        queue priority; anything else goes to the back of the queue."""
        body = {"price": price, "quantity": quantity}
        return OrderResult.model_validate(
            await self._request("PATCH", f"/api/v1/orders/{order_id}", json=body)
        )

    async def open_orders(self) -> list[OpenOrder]:
        return [OpenOrder.model_validate(o) for o in await self._request("GET", "/api/v1/orders")]

    async def cancel_all(self) -> int:
        """Cancels every open order of this account; returns how many were cancelled."""
        return int((await self._request("DELETE", "/api/v1/orders"))["cancelled"])

    async def me(self) -> dict[str, Any]:
        return await self._request("GET", "/api/v1/me")

    # ---- after the trade (the post-trade service: official P&L and history)

    async def pnl(self) -> dict[str, Any]:
        """This account's official P&L: realised, unrealised, charges, net, leaderboard rank and
        positions (amounts in paise). Calling it also puts this account's name on the
        leaderboard."""
        return await self._request("GET", "/api/v1/account/pnl")

    async def fills(self, limit: int = 100) -> list[dict[str, Any]]:
        """This account's fills from the ledger, newest first, with charges and realised P&L."""
        return await self._request("GET", f"/api/v1/account/fills?limit={limit}")

    # ---- market information (public)

    async def instruments(self) -> list[Instrument]:
        return [
            Instrument.model_validate(i)
            for i in await self._request("GET", "/api/v1/instruments", auth=False)
        ]

    async def session(self) -> SessionInfo:
        return SessionInfo.model_validate(await self._request("GET", "/api/v1/session", auth=False))

    async def book(self, symbol: str, depth: int = 20) -> Snapshot:
        data = await self._request("GET", f"/api/v1/market/{symbol}/book?depth={depth}", auth=False)
        return Snapshot.model_validate(data)

    async def trades(self, symbol: str, limit: int = 200) -> list[TradePrint]:
        data = await self._request(
            "GET", f"/api/v1/market/{symbol}/trades?limit={limit}", auth=False
        )
        return [TradePrint.model_validate(t) for t in data]

    async def tickers(self) -> list[Ticker]:
        return [
            Ticker.model_validate(t)
            for t in await self._request("GET", "/api/v1/market/tickers", auth=False)
        ]

    # ---- plumbing

    async def auth_headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {await self.tokens.token()}",
            "X-Prayog-Account": self.settings.account,
        }

    async def _request(self, method: str, path: str, *, auth: bool = True, json: Any = None) -> Any:
        url = self.settings.api_url + path
        for attempt in range(2):
            headers = await self.auth_headers() if auth else {}
            response = await self._http.request(method, url, headers=headers, json=json)
            if response.status_code == 401 and auth and attempt == 0:
                self.tokens.invalidate()  # expired or rotated: fetch a new token once and retry
                continue
            return _check(response)
        raise AssertionError("unreachable")


def _check(response: httpx.Response) -> Any:
    if response.is_success:
        return response.json()
    try:
        body = response.json()
        error, message = body.get("error", "error"), body.get("message", response.text)
    except ValueError:
        error, message = "error", response.text
    kind = {429: RateLimitedError, 503: BusyError, 404: NotFoundError}.get(
        response.status_code, PrayogError
    )
    raise kind(response.status_code, error, message)
