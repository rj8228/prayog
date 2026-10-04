"""Python client for the Prayog exchange.

Quick start::

    import asyncio
    from prayog_sdk import PrayogClient, Settings

    async def main():
        async with PrayogClient(Settings.from_env()) as client:
            print(await client.book("INFY"))
            result = await client.buy_limit("INFY", quantity=10, price=149_500)  # price in paise
            print(result.status, result.order_id)

    asyncio.run(main())

Prices are integer paise (1 rupee = 100 paise) everywhere in the API; use :func:`rupees` /
:func:`to_rupees` to
convert. See docs/bots/ in the repository for the full guide.
"""

__version__ = "0.1.0"

from prayog_sdk.book import LocalBook, SequenceGap
from prayog_sdk.client import (
    BusyError,
    NotFoundError,
    PrayogClient,
    PrayogError,
    RateLimitedError,
)
from prayog_sdk.config import Settings
from prayog_sdk.models import (
    Fill,
    Instrument,
    Level,
    OpenOrder,
    OrderResult,
    OrderUpdate,
    SessionInfo,
    Snapshot,
    Ticker,
    TradePrint,
)
from prayog_sdk.money import rupees, to_rupees
from prayog_sdk.streams import market_data, private_updates

__all__ = [
    "BusyError",
    "Fill",
    "Instrument",
    "Level",
    "LocalBook",
    "NotFoundError",
    "OpenOrder",
    "OrderResult",
    "OrderUpdate",
    "PrayogClient",
    "PrayogError",
    "RateLimitedError",
    "SequenceGap",
    "SessionInfo",
    "Settings",
    "Snapshot",
    "Ticker",
    "TradePrint",
    "market_data",
    "private_updates",
    "rupees",
    "to_rupees",
]
