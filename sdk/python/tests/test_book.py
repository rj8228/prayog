import pytest
from prayog_sdk import LocalBook, SequenceGap


def snapshot(seq=10):
    return {
        "type": "snapshot",
        "symbol": "INFY",
        "seq": seq,
        "bids": [
            {"price": 149900, "quantity": 10, "orders": 1},
            {"price": 149800, "quantity": 5, "orders": 2},
        ],
        "asks": [{"price": 150000, "quantity": 7, "orders": 1}],
        "ticker": {"last": 149950},
    }


def test_applies_a_snapshot_then_deltas_in_order():
    book = LocalBook("INFY")
    book.apply(snapshot())
    assert (book.best_bid, book.best_ask, book.last_price) == (149900, 150000, 149950)

    book.apply({"type": "trade", "seq": 11, "price": 150000, "quantity": 7})
    book.apply(
        {
            "type": "book",
            "seq": 12,
            "changes": [
                {"side": "SELL", "price": 150000, "quantity": 0, "orders": 0},
                {"side": "BUY", "price": 149950, "quantity": 3, "orders": 1},
            ],
        }
    )

    assert book.best_ask is None
    assert book.best_bid == 149950
    assert book.last_price == 150000
    assert book.depth("BUY", 2) == [(149950, 3, 1), (149900, 10, 1)]


def test_a_missing_message_is_detected():
    book = LocalBook("INFY")
    book.apply(snapshot(seq=10))
    with pytest.raises(SequenceGap):
        book.apply({"type": "book", "seq": 12, "changes": []})


def test_deltas_before_a_snapshot_are_a_gap():
    with pytest.raises(SequenceGap):
        LocalBook("INFY").apply({"type": "trade", "seq": 1, "price": 1, "quantity": 1})


def test_a_new_snapshot_resets_the_book():
    book = LocalBook("INFY")
    book.apply(snapshot(seq=10))
    book.apply(snapshot(seq=99) | {"bids": [], "asks": []})
    assert book.seq == 99 and book.best_bid is None


def test_other_messages_are_ignored():
    book = LocalBook("INFY")
    book.apply({"type": "heartbeat", "simTime": 1})
    book.apply({"type": "session", "state": "OPEN"})
    assert book.seq == -1
