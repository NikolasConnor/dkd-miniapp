import unittest

from election_rules import is_party_leader


class PartyLeaderEligibilityTests(unittest.TestCase):
    def test_active_leader_is_identified_by_numeric_user_id(self):
        self.assertTrue(
            is_party_leader(
                {"user_id": 42, "username": "nik"},
                {"status": "active", "leader_user_id": 42, "leader_username": "other"},
                42,
            )
        )

    def test_regular_party_member_is_not_leader(self):
        self.assertFalse(
            is_party_leader(
                {"user_id": 43, "username": "member"},
                {"status": "active", "leader_user_id": 42, "leader_username": "leader"},
                43,
            )
        )

    def test_legacy_party_falls_back_to_username(self):
        self.assertTrue(
            is_party_leader(
                {"username": "@Nik"},
                {"status": "active", "leader_user_id": None, "leader_username": "nik"},
                7,
            )
        )

    def test_inactive_party_does_not_block_voting(self):
        self.assertFalse(
            is_party_leader(
                {"user_id": 42, "username": "nik"},
                {"status": "dissolved", "leader_user_id": 42, "leader_username": "nik"},
                42,
            )
        )

    def test_missing_membership_does_not_block_voting(self):
        self.assertFalse(is_party_leader(None, None, 42))


if __name__ == "__main__":
    unittest.main()
