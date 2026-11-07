"""Pure election eligibility rules shared by API handlers and unit tests."""

from __future__ import annotations


def is_party_leader(membership: dict | None, party: dict | None, user_id: int) -> bool:
    """Return whether this member is the active party leader.

    Prefer the immutable numeric user id. Fall back to the membership username
    only for legacy rows where leader_user_id was never populated.
    """
    if not membership or not party or party.get("status") != "active":
        return False

    leader_id = party.get("leader_user_id")
    if leader_id not in (None, "", 0, "0"):
        try:
            return int(leader_id) == int(user_id)
        except (TypeError, ValueError):
            pass

    leader_username = str(party.get("leader_username") or "").strip().lstrip("@").casefold()
    member_username = str(membership.get("username") or "").strip().lstrip("@").casefold()
    return bool(leader_username and member_username and leader_username == member_username)
