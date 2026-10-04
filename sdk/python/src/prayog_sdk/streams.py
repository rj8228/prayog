"""Live WebSocket feeds as async iterators, with automatic reconnects."""

import asyncio
import json
import logging
from collections.abc import AsyncIterator, Iterable

from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

from prayog_sdk.client import PrayogClient
from prayog_sdk.config import Settings
from prayog_sdk.models import Fill, OrderUpdate

log = logging.getLogger(__name__)


async def market_data(
    settings: Settings, symbols: Iterable[str], depth: int = 20, reconnect_delay: float = 1.0
) -> AsyncIterator[dict]:
    """Yields market-data messages (dicts with ``type``: snapshot, book, trade, session, heartbeat).

    After a disconnect it reconnects and starts again with fresh snapshots, so feed every message
    into a
    :class:`~prayog_sdk.book.LocalBook` and reset it on each snapshot.
    """
    url = f"{settings.ws_url}/api/v1/ws/market?symbols={','.join(symbols)}&depth={depth}"
    while True:
        try:
            async with connect(url, max_size=None) as socket:
                async for raw in socket:
                    yield json.loads(raw)
        except (ConnectionClosed, OSError) as e:
            log.warning("market data disconnected (%s); reconnecting", e)
            await asyncio.sleep(reconnect_delay)


async def private_updates(
    client: PrayogClient, reconnect_delay: float = 1.0
) -> AsyncIterator[OrderUpdate | Fill]:
    """Yields this account's :class:`OrderUpdate` and :class:`Fill` messages as they happen.

    Updates sent while disconnected are not replayed: after a reconnect, call
    ``client.open_orders()`` to resync.
    """
    url = f"{client.settings.ws_url}/api/v1/ws/private?account={client.settings.account}"
    while True:
        try:
            headers = {"Authorization": (await client.auth_headers())["Authorization"]}
            async with connect(url, additional_headers=headers, max_size=None) as socket:
                async for raw in socket:
                    message = json.loads(raw)
                    if message.get("type") == "order":
                        yield OrderUpdate.model_validate(message)
                    elif message.get("type") == "fill":
                        yield Fill.model_validate(message)
        except (ConnectionClosed, OSError) as e:
            log.warning("private feed disconnected (%s); reconnecting", e)
            client.tokens.invalidate()
            await asyncio.sleep(reconnect_delay)
