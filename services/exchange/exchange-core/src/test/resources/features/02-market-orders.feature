Feature: Market orders
  A market order takes whatever liquidity there is, up to the price band, and never rests.

  Background:
    Given INFY trades in ticks of 0.05 rupees within 10% of 1500.00
    And the market is OPEN

  Scenario: A market order walks the book
    Given alice has a resting SELL of 5 at 1500.00
    And bob has a resting SELL of 5 at 1501.00
    When carol places a BUY market order of 8
    Then carol buys 5 at 1500.00 from alice
    And carol buys 3 at 1501.00 from bob

  Scenario: What a market order cannot fill is cancelled
    Given alice has a resting SELL of 5 at 1500.00
    When carol places a BUY market order of 8
    Then carol buys 5 at 1500.00 from alice
    And carol's order is cancelled with NO_LIQUIDITY for 3

  Scenario: A market order into an empty book is cancelled
    When carol places a SELL market order of 8
    Then no trade happens
    And carol's order is cancelled with NO_LIQUIDITY for 8
