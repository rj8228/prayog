import json

import httpx
import pytest
from prayog_sdk import BusyError, NotFoundError, PrayogClient, RateLimitedError, Settings
from prayog_sdk.auth import TokenProvider

SETTINGS = Settings(
    api_url="http://api.test",
    auth_url="http://auth.test/realms/prayog",
    client_secret="s3cret",
    account="mm",
)


class FakeExchange:
    """Answers token and API requests the way Keycloak and the exchange do, and records what it
    saw."""

    def __init__(self):
        self.requests: list[httpx.Request] = []
        self.tokens_issued = 0
        self.reject_token: str | None = None
        self.next_status: int | None = None

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if request.url.path.endswith("/token"):
            self.tokens_issued += 1
            return httpx.Response(
                200, json={"access_token": f"t{self.tokens_issued}", "expires_in": 300}
            )
        if request.headers.get("authorization") == f"Bearer {self.reject_token}":
            return httpx.Response(401)
        if self.next_status:
            status, self.next_status = self.next_status, None
            return httpx.Response(status, json={"error": "x", "message": "nope"})
        if request.method == "POST" and request.url.path == "/api/v1/orders":
            body = json.loads(request.content)
            return httpx.Response(
                201,
                json={
                    "status": "resting",
                    "orderId": 7,
                    "clientOrderId": body["clientOrderId"],
                    "symbol": body["symbol"],
                    "filledQuantity": 0,
                    "leavesQuantity": body["quantity"],
                    "fills": [],
                    "inputSeq": 3,
                    "simTime": 1,
                },
            )
        return httpx.Response(200, json=[])


@pytest.fixture
def exchange():
    return FakeExchange()


@pytest.fixture
async def client(exchange):
    http = httpx.AsyncClient(transport=httpx.MockTransport(exchange))
    c = PrayogClient(SETTINGS, http=http)
    yield c
    await http.aclose()


async def test_places_a_limit_order_with_token_and_account_header(client, exchange):
    result = await client.buy_limit("INFY", quantity=10, price=149_500)

    assert result.status == "resting" and result.order_id == 7 and result.leaves_quantity == 10
    sent = exchange.requests[-1]
    body = json.loads(sent.content)
    assert body == {
        "symbol": "INFY",
        "side": "BUY",
        "type": "LIMIT",
        "quantity": 10,
        "price": 149_500,
        "clientOrderId": body["clientOrderId"],
    }
    assert sent.headers["authorization"] == "Bearer t1"
    assert sent.headers["x-prayog-account"] == "mm"


async def test_a_market_order_sends_no_price(client, exchange):
    await client.sell_market("TCS", 5)
    body = json.loads(exchange.requests[-1].content)
    assert body["type"] == "MARKET" and "price" not in body


async def test_the_token_is_reused_until_it_nears_expiry(client, exchange):
    await client.open_orders()
    await client.open_orders()
    assert exchange.tokens_issued == 1


async def test_a_rejected_token_is_refreshed_once(client, exchange):
    await client.open_orders()  # gets t1
    exchange.reject_token = "t1"
    await client.open_orders()  # 401 with t1 -> new token t2 -> retried
    assert exchange.tokens_issued == 2


@pytest.mark.parametrize(
    "status, error", [(429, RateLimitedError), (503, BusyError), (404, NotFoundError)]
)
async def test_errors_are_typed(client, exchange, status, error):
    exchange.next_status = status
    with pytest.raises(error) as raised:
        await client.cancel(1)
    assert raised.value.status == status


async def test_public_calls_send_no_token(client, exchange):
    await client.instruments()
    assert "authorization" not in exchange.requests[-1].headers
    assert exchange.tokens_issued == 0


async def test_token_provider_refreshes_30_seconds_before_expiry():
    now = [1000.0]
    issued = []

    def handler(request):
        issued.append(1)
        return httpx.Response(200, json={"access_token": f"t{len(issued)}", "expires_in": 300})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        tokens = TokenProvider(SETTINGS, http, clock=lambda: now[0])
        assert await tokens.token() == "t1"
        now[0] += 269
        assert await tokens.token() == "t1"
        now[0] += 2  # 271 s: inside the 30 s margin
        assert await tokens.token() == "t2"
