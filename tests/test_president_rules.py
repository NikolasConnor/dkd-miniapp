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
