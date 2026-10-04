import pytest
from prayog_sdk import rupees, to_rupees


def test_rupees_to_paise_is_exact():
    assert rupees("1495.50") == 149550
    assert rupees(1500) == 150000
    assert rupees("0.05") == 5


def test_refuses_fractions_of_a_paisa():
    with pytest.raises(ValueError):
        rupees("1495.505")


def test_formats_paise_as_rupees():
    assert to_rupees(149550) == "1495.50"
    assert to_rupees(5) == "0.05"
    assert to_rupees(-250) == "-2.50"
