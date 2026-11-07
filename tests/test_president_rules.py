"""Regression checks for the presidential administration's permission boundary."""
import ast
import unittest
from pathlib import Path

import core


class PresidentRoleRulesTests(unittest.TestCase):
    def test_president_can_only_appoint_prime_minister_and_foreign_minister(self):
        self.assertEqual(
            core.PRESIDENT_ASSIGNABLE_ROLES,
            {"премьер-министр", "министр иностранных дел"},
        )
        self.assertNotIn("президент", core.PRESIDENT_ASSIGNABLE_ROLES)
        self.assertNotIn("депутат", core.PRESIDENT_ASSIGNABLE_ROLES)
        self.assertNotIn("гражданин", core.PRESIDENT_ASSIGNABLE_ROLES)

    def test_decree_execution_statuses_are_closed_set(self):
        self.assertEqual(
            core.PRESIDENT_DECREE_EXECUTION_STATUSES,
            {"in_progress", "completed", "suspended"},
        )

    def test_president_activity_uses_the_actual_application_audit_action(self):
        self.assertIn("president_application_reviewed", core.PRESIDENT_ACTIVITY_ACTIONS)
        self.assertNotIn("application_reviewed", core.PRESIDENT_ACTIVITY_ACTIONS)

    def test_personal_certificates_and_mandates_are_not_decrees(self):
        self.assertFalse(core._is_presidential_decree_record(
            {"title": "Удостоверение Президента ДКД"}
        ))
        self.assertFalse(core._is_presidential_decree_record(
            {"title": "Мандат депутата Госдумы · созыв №2"}
        ))
        self.assertTrue(core._is_presidential_decree_record(
            {"title": "О назначении государственного праздника"}
        ))


class DumaRulesTests(unittest.TestCase):
    def test_duma_party_lists_lock_when_campaign_is_active(self):
        from datetime import datetime, timezone
        from unittest.mock import patch

        election = {"id": 12, "status": "active", "ends_at": "2030-01-03T00:00:00+00:00"}
        with patch.object(core, "duma_start_at", return_value=datetime(2030, 1, 1, tzinfo=timezone.utc)):
            self.assertTrue(core._duma_campaign_lists_locked(
                election, datetime(2030, 1, 2, tzinfo=timezone.utc)
            ))

    def test_duma_party_lists_remain_editable_before_campaign(self):
        from datetime import datetime, timezone
        from unittest.mock import patch

        election = {"id": 12, "status": "scheduled", "ends_at": "2030-01-03T00:00:00+00:00"}
        with patch.object(core, "duma_start_at", return_value=datetime(2030, 1, 2, tzinfo=timezone.utc)):
            self.assertFalse(core._duma_campaign_lists_locked(
                election, datetime(2030, 1, 1, tzinfo=timezone.utc)
            ))

    def test_duma_session_expires_after_three_days(self):
        from datetime import datetime, timezone

        events = [{
            "id": 100, "action": "duma_session_opened",
            "details": '{"created_at":"2030-01-01T12:00:00+00:00"}',
        }]
        before_expiry = datetime(2030, 1, 4, 11, 59, tzinfo=timezone.utc)
        at_expiry = datetime(2030, 1, 4, 12, 0, tzinfo=timezone.utc)
        self.assertFalse(core._duma_session_expired(events, 100, before_expiry))
        self.assertTrue(core._duma_session_expired(events, 100, at_expiry))
        self.assertTrue(core._is_duma_session_closed(events, 100) is False)
        self.assertEqual(
            core._duma_session_expiry(events, 100),
            datetime(2030, 1, 4, 12, 0, tzinfo=timezone.utc),
        )

    def test_duma_law_voting_requires_open_session(self):
        source = Path("core.py").read_text(encoding="utf-8")
        self.assertIn('"and session_open"', source)
        self.assertIn('"law["can_vote"] = ("', source)


class PresidentApiPermissionTests(unittest.TestCase):
    def test_mutating_president_routes_require_president_dependency(self):
        source = Path("app.py").read_text(encoding="utf-8")
        tree = ast.parse(source)
        for node in ast.walk(tree):
            if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                continue
            decorators = [ast.unparse(item) for item in node.decorator_list]
            is_president_route = any(
                "/api/president/" in decorator
                and any(method in decorator for method in ("post(", "put(", "patch(", "delete("))
                for decorator in decorators
            )
            if is_president_route:
                args = ast.unparse(node.args)
                self.assertIn(
                    "Depends(president_user)",
                    args,
                    msg=f"{node.name} must enforce president_user",
                )

    def test_activity_and_decree_execution_endpoints_exist(self):
        source = Path("app.py").read_text(encoding="utf-8")
        self.assertIn('"/api/president/activity"', source)
        self.assertIn('"/api/president/decrees"', source)
        self.assertIn('"/api/president/decrees/{decree_id}/execution"', source)


if __name__ == "__main__":
    unittest.main()
