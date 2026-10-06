Feature: Pre-trade checks, sessions and the kill switch

  Background:
    Given INFY trades in ticks of 0.05 rupees within 10% of 1500.00

  Scenario Outline: Bad orders are rejected with one clear reason
    Given the market is OPEN
    When carol places a BUY limit of <quantity> at <price>
    Then carol is rejected with <reason>

    Examples:
      | price   | quantity | reason             |
      | 1500.01 | 10       | PRICE_NOT_ON_TICK  |
      | 1650.05 | 10       | PRICE_OUTSIDE_BAND |
      | 1349.95 | 10       | PRICE_OUTSIDE_BAND |
      | 1500.00 | 0        | INVALID_QUANTITY   |

  Scenario: The band edges themselves are allowed
    Given the market is OPEN
    When carol places a BUY limit of 1 at 1350.00
    And dave places a SELL limit of 1 at 1650.00
    Then no trade happens

  Scenario: Nothing new is accepted while the market is halted, but cancels are
    Given the market is OPEN
    And alice has a resting BUY of 10 at 1499.00
    When the market is HALTED
    And carol places a BUY limit of 10 at 1499.00
    Then carol is rejected with SESSION_NOT_OPEN
    When alice cancels her last order
    Then alice's order is cancelled with CLIENT_REQUEST for 10

  Scenario: Closing the market expires every open order
    Given the market is OPEN
    And alice has a resting BUY of 10 at 1499.00
    When the market is CLOSED
    Then alice's order is cancelled with EXPIRED for 10
    And the book has no bids

  Scenario: The account kill switch cancels its orders and refuses new ones
    Given the market is OPEN
    And alice has a resting BUY of 10 at 1499.00
    When ops disables alice
    Then alice's order is cancelled with KILL_SWITCH for 10
    When alice places a BUY limit of 10 at 1499.00
    Then alice is rejected with ACCOUNT_DISABLED

  Scenario: An account never trades with itself
    Given the market is OPEN
    And alice has a resting SELL of 10 at 1500.00
    When alice places a BUY limit of 10 at 1500.00
    Then no trade happens
    And alice's order is cancelled with SELF_TRADE_PREVENTION for 10

  Scenario: A retried order with the same client order id is refused
    Given the market is OPEN
    When alice places a BUY limit of 10 at 1499.00 as "retry-1"
    And alice places a BUY limit of 10 at 1499.00 as "retry-1"
    Then alice is rejected with DUPLICATE_CLIENT_ORDER_ID
    And the bids are:
      | price   | quantity | orders |
      | 1499.00 | 10       | 1      |
