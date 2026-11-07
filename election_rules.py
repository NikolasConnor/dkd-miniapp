"""Pure presidential and parliamentary election rules shared by API handlers and unit tests."""

from __future__ import annotations


def president_can_close_duma_election(status: str) -> bool:
    """A President may finalize only a campaign that has actually started."""
    return str(status or "").strip().casefold() == "active"


def president_can_decide_law(status: str) -> bool:
    """Only a bill passed by the Duma may be signed or vetoed by the President."""
    return str(status or "").strip().casefold() == "awaiting_president"


