Feature: Cancel and modify
  Reducing quantity keeps time priority; changing the price or adding quantity loses it.
  Nobody can touch another account's order.

  Background:
    Given INFY trades in ticks of 0.05 rupees within 10% of 1500.00
    And the market is OPEN

  Scenario: A cancelled order leaves the book
    Given alice has a resting BUY of 10 at 1499.00
    When alice cancels her last order
    Then alice's order is cancelled with CLIENT_REQUEST for 10
    And the book has no bids

  Scenario: Reducing quantity keeps the place in the queue
    Given alice has a resting SELL of 10 at 1500.00
    And bob has a resting SELL of 10 at 1500.00
    When alice changes her last order to 6 at 1500.00
    And carol places a BUY limit of 6 at 1500.00
    Then carol buys 6 at 1500.00 from alice

  Scenario: Changing the price loses the place in the queue
    Given alice has a resting SELL of 10 at 1500.00
    And bob has a resting SELL of 10 at 1500.00
    When alice changes her last order to 10 at 1500.05
    And alice changes her last order to 10 at 1500.00
    And carol places a BUY limit of 10 at 1500.00
    Then carol buys 10 at 1500.00 from bob

  Scenario: Another account's order looks like no order at all
    Given alice has a resting BUY of 10 at 1499.00
    When bob cancels alice's last order
    Then bob is rejected with UNKNOWN_ORDER
    And the bids are:
      | price   | quantity | orders |
      | 1499.00 | 10       | 1      |

  Scenario: Modifying down to the filled amount cancels the rest
    Given alice has a resting SELL of 10 at 1500.00
    And carol places a BUY limit of 4 at 1500.00
    When alice changes her last order to 4 at 1500.00
    Then alice's order is cancelled with MODIFIED_TO_ZERO for 6
