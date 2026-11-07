import unittest

from election_rules import president_can_close_duma_election, president_can_decide_law



class PresidentDumaElectionControlTests(unittest.TestCase):
    def test_president_can_close_only_active_election(self):
        self.assertTrue(president_can_close_duma_election("active"))
        self.assertFalse(president_can_close_duma_election("scheduled"))
        self.assertFalse(president_can_close_duma_election("closed"))
        self.assertFalse(president_can_close_duma_election("unknown"))

    def test_president_can_decide_only_duma_approved_law(self):
        self.assertTrue(president_can_decide_law("awaiting_president"))
        self.assertFalse(president_can_decide_law("under_duma_review"))
        self.assertFalse(president_can_decide_law("active"))
        self.assertFalse(president_can_decide_law("signed"))


if __name__ == "__main__":
    unittest.main()
