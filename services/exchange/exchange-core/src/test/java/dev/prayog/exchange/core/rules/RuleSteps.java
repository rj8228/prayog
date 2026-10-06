package dev.prayog.exchange.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prayog.contracts.CancelReason;
import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.RejectReason;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.Trade;
import dev.prayog.exchange.core.CancelOrder;
import dev.prayog.exchange.core.ClockTick;
import dev.prayog.exchange.core.Command;
import dev.prayog.exchange.core.DepthLevel;
import dev.prayog.exchange.core.Instrument;
import dev.prayog.exchange.core.MatchingEngine;
import dev.prayog.exchange.core.ModifyOrder;
import dev.prayog.exchange.core.NewOrder;
import dev.prayog.exchange.core.SetAccountEnabled;
import dev.prayog.exchange.core.SetRules;
import dev.prayog.exchange.core.SetSessionState;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Step definitions for the rule scenarios. People are named accounts; prices are written in rupees ("1500.05") and
 * converted exactly to paise. Every action remembers where the event stream stood, and the "Then" steps look only at
 * the events the last action produced. Cucumber makes a fresh instance per scenario.
 */
public class RuleSteps {

    private static final long T0 = 1_790_000_000_000_000L;

    private final List<ExchangeEvent> events = new ArrayList<>();
    private final Map<String, Long> accounts = new HashMap<>();
    private final Map<Long, Long> lastOrder = new HashMap<>();
    private MatchingEngine engine;
    private String symbol;
    private int mark;
    private int nextClientId = 1;

    // ---- setup ------------------------------------------------------------------------------------------------------

    @Given("^(\\w+) trades in ticks of ([\\d.]+) rupees within (\\d+)% of ([\\d.]+)$")
    public void instrument(String name, String tick, int band, String reference) {
        symbol = name;
        engine = new MatchingEngine(
                List.of(new Instrument(name, paise(tick), 1_000_000, paise(reference), band)), this::onEvent);
        engine.apply(new ClockTick(T0));
        engine.apply(new SetRules(MatchingEngine.LATEST_RULES));
    }

    @Given("^the market is (OPEN|HALTED|CLOSED)$")
    public void session(String state) {
        act(new SetSessionState(SessionState.valueOf(state)));
    }

    @Given("^ops disables (\\w+)$")
    public void disable(String who) {
        act(new SetAccountEnabled(account(who), false));
    }

    // ---- actions ----------------------------------------------------------------------------------------------------

    @Given("^(\\w+) has a resting (BUY|SELL) of (\\d+) at ([\\d.]+)$")
    public void resting(String who, String side, long quantity, String price) {
        limit(who, side, quantity, price, null);
        assertThat(since())
                .as(who + "'s order should rest without trading")
                .hasSize(1)
                .first()
                .isInstanceOf(OrderAccepted.class);
    }

    @When("^(\\w+) places a (BUY|SELL) limit of (\\d+) at ([\\d.]+)(?: as \"([^\"]+)\")?$")
    public void limit(String who, String side, long quantity, String price, String clientOrderId) {
        act(new NewOrder(
                clientOrderId != null ? clientOrderId : "c-" + nextClientId++,
                account(who),
                symbol,
                Side.valueOf(side),
                OrderType.LIMIT,
                paise(price),
                quantity));
    }

    @When("^(\\w+) places a (BUY|SELL) market order of (\\d+)$")
    public void market(String who, String side, long quantity) {
        act(new NewOrder(
                "c-" + nextClientId++, account(who), symbol, Side.valueOf(side), OrderType.MARKET, 0, quantity));
    }

    @When("^(\\w+) cancels (?:his|her|their) last order$")
    public void cancelOwn(String who) {
        act(new CancelOrder("x-" + nextClientId++, account(who), symbol, lastOrder(who)));
    }

    @When("^(\\w+) cancels (\\w+)'s last order$")
    public void cancelOther(String who, String owner) {
        act(new CancelOrder("x-" + nextClientId++, account(who), symbol, lastOrder(owner)));
    }

    @When("^(\\w+) changes (?:his|her|their) last order to (\\d+) at ([\\d.]+)$")
    public void modify(String who, long quantity, String price) {
        act(new ModifyOrder("x-" + nextClientId++, account(who), symbol, lastOrder(who), paise(price), quantity));
    }

    // ---- outcomes ---------------------------------------------------------------------------------------------------

    @Then("^(\\w+) (buys|sells) (\\d+) at ([\\d.]+) (?:from|to) (\\w+)$")
    public void trades(String who, String verb, long quantity, String price, String other) {
        long buyer = account(verb.equals("buys") ? who : other);
        long seller = account(verb.equals("buys") ? other : who);
        assertThat(since())
                .as("a trade of %d at %s between %s and %s", quantity, price, who, other)
                .anySatisfy(e -> {
                    assertThat(e).isInstanceOf(Trade.class);
                    Trade t = (Trade) e;
                    assertThat(t.buyAccountId()).isEqualTo(buyer);
                    assertThat(t.sellAccountId()).isEqualTo(seller);
                    assertThat(t.quantity()).isEqualTo(quantity);
                    assertThat(t.price()).isEqualTo(paise(price));
                });
    }

    @Then("^no trade happens$")
    public void noTrade() {
        assertThat(since()).noneMatch(e -> e instanceof Trade);
    }

    @Then("^(\\w+)'s order is cancelled with (\\w+) for (\\d+)$")
    public void cancelled(String who, String reason, long quantity) {
        assertThat(since())
                .as("%s's order cancelled with %s for %d", who, reason, quantity)
                .anySatisfy(e -> {
                    assertThat(e).isInstanceOf(OrderCancelled.class);
                    OrderCancelled c = (OrderCancelled) e;
                    assertThat(c.accountId()).isEqualTo(account(who));
                    assertThat(c.reason()).isEqualTo(CancelReason.valueOf(reason));
                    assertThat(c.cancelledQuantity()).isEqualTo(quantity);
                });
    }

    @Then("^(\\w+) is rejected with (\\w+)$")
    public void rejected(String who, String reason) {
        assertThat(since()).as("%s rejected with %s", who, reason).anySatisfy(e -> {
            assertThat(e).isInstanceOf(OrderRejected.class);
            OrderRejected r = (OrderRejected) e;
            assertThat(r.accountId()).isEqualTo(account(who));
            assertThat(r.reason()).isEqualTo(RejectReason.valueOf(reason));
        });
    }

    @Then("^the (bids|asks) are:$")
    public void book(String sideName, DataTable table) {
        List<DepthLevel> expected = new ArrayList<>();
        for (Map<String, String> row : table.asMaps()) {
            expected.add(new DepthLevel(
                    paise(row.get("price")), Long.parseLong(row.get("quantity")), Integer.parseInt(row.get("orders"))));
        }
        assertThat(depth(sideName)).isEqualTo(expected);
    }

    @Then("^the book has no (bids|asks)$")
    public void emptySide(String sideName) {
        assertThat(depth(sideName)).isEmpty();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private void act(Command command) {
        mark = events.size();
        engine.apply(command);
    }

    private void onEvent(ExchangeEvent event) {
        events.add(event);
        if (event instanceof OrderAccepted a) {
            lastOrder.put(a.accountId(), a.orderId());
        }
    }

    private List<ExchangeEvent> since() {
        return List.copyOf(events.subList(mark, events.size()));
    }

    private List<DepthLevel> depth(String sideName) {
        return engine.book(symbol).depth(sideName.equals("bids") ? Side.BUY : Side.SELL, 1_000);
    }

    private long account(String name) {
        return accounts.computeIfAbsent(name, n -> (long) accounts.size() + 1);
    }

    private long lastOrder(String who) {
        Long id = lastOrder.get(account(who));
        assertThat(id).as(who + " has placed an order").isNotNull();
        return id;
    }

    private static long paise(String rupees) {
        return new BigDecimal(rupees).movePointRight(2).longValueExact();
    }
}
