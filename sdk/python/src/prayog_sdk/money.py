"""Money helpers. The exchange never uses floating point for prices: everything is integer paise."""

from decimal import Decimal, InvalidOperation


def rupees(value: str | int | Decimal) -> int:
    """Converts rupees to paise exactly: ``rupees("1495.50") == 149550``. Refuses fractions of a
    paisa."""
    try:
        amount = Decimal(str(value)) * 100
    except InvalidOperation as e:
        raise ValueError(f"not a price: {value!r}") from e
    if amount != amount.to_integral_value():
        raise ValueError(f"{value} rupees is not a whole number of paise")
    return int(amount)


def to_rupees(paise: int) -> str:
    """Formats paise as rupees with two decimals: ``to_rupees(149550) == "1495.50"``."""
    sign = "-" if paise < 0 else ""
    paise = abs(paise)
    return f"{sign}{paise // 100}.{paise % 100:02d}"
