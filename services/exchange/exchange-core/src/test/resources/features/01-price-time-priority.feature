Feature: Price-time priority
  Orders match at the best price first; at the same price, the order that arrived first fills first.
  A trade always happens at the resting order's price.

  Background:
    Given INFY trades in ticks of 0.05 rupees within 10% of 1500.00
    And the market is OPEN

  Scenario: The better price trades first
    Given alice has a resting SELL of 10 at 1500.50
    And bob has a resting SELL of 10 at 1500.00
    When carol places a BUY limit of 10 at 1501.00
    Then carol buys 10 at 1500.00 from bob
    And the asks are:
      | price   | quantity | orders |
      | 1500.50 | 10       | 1      |

  Scenario: At the same price, the earlier order trades first
    Given alice has a resting SELL of 10 at 1500.00
    And bob has a resting SELL of 10 at 1500.00
    When carol places a BUY limit of 15 at 1500.00
    Then carol buys 10 at 1500.00 from alice
    And carol buys 5 at 1500.00 from bob
    And the asks are:
      | price   | quantity | orders |
      | 1500.00 | 5        | 1      |

  Scenario: A limit order that does not cross rests on the book
    Given alice has a resting SELL of 10 at 1500.50
    When carol places a BUY limit of 10 at 1500.00
    Then no trade happens
    And the bids are:
      | price   | quantity | orders |
      | 1500.00 | 10       | 1      |

  Scenario: The unfilled part of an aggressive limit order rests at its limit
    Given alice has a resting SELL of 4 at 1500.00
    When carol places a BUY limit of 10 at 1500.10
    Then carol buys 4 at 1500.00 from alice
    And the bids are:
      | price   | quantity | orders |
      | 1500.10 | 6        | 1      |
