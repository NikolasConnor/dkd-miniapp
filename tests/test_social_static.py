from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class SocialApiStaticTests(unittest.TestCase):
    def test_social_routes_require_authenticated_user(self):
        source = (ROOT / "app.py").read_text(encoding="utf-8")
        expected = (
            'def social_feed(',
            'def social_create_post(',
            'def social_create_comment(',
            'def social_toggle_reaction(',
            'def social_list_comments(',
            'def social_report_post(',
            'def social_toggle_block(',
            'def social_create_post_with_media(',
            'def social_get_post_media(',
            'def update_profile_gender(',
        )
        for name in expected:
            self.assertIn(name, source)
        block = source.split('# Социальная сеть: лента, публикации, комментарии и реакции', 1)[1]
        block = block.split('@app.get("/api/news")', 1)[0]
        self.assertGreaterEqual(block.count('user: dict = Depends(auth_user)'), 9)
        for name in ('def social_get_profile(', 'def social_list_comments(', 'def social_report_post(', 'def social_toggle_block(', 'def social_create_post_with_media(', 'def social_get_post_media(', 'def social_update_profile(', 'def social_toggle_follow(', 'def social_upload_cover(', 'def social_get_cover('):
            self.assertIn(name, block)

    def test_social_database_tables_enable_rls_and_revoke_client_access(self):
        migrations = ROOT / "supabase/migrations/20261106130000_social_feed_foundation.sql"
        sql = migrations.read_text(encoding="utf-8").lower()
        for table in (
            "social_posts",
            "social_post_media",
            "social_comments",
            "social_reactions",
            "social_user_blocks",
            "social_reports",
        ):
            self.assertIn(f"alter table public.{table} enable row level security", sql)
            self.assertIn(f"revoke all on public.{table} from anon, authenticated", sql)

    def test_law3_family_and_court_routes_are_authenticated(self):
        app = (ROOT / "app.py").read_text(encoding="utf-8")
        core = (ROOT / "core.py").read_text(encoding="utf-8")
        for route in (
            'def family_list(', 'def family_propose(', 'def family_decide(',
            'def family_divorce(', 'def family_divorce_decision(',
            'def court_cases(', 'def court_file_case(', 'def court_respond(',
            'def court_appeal(', 'def court_archive(',
        ):
            self.assertIn(route, app)
        for function in (
            'def propose_marriage(', 'def decide_marriage(', 'def request_divorce(',
            'def decide_divorce(', 'def file_court_case(', 'def respond_court_case(',
            'def appeal_court_case(', 'def list_public_court_decisions(',
        ):
            self.assertIn(function, core)
        block = app.split('# Закон №3: регистрация брака, развод и Суд ДКД', 1)[1]
        block = block.split('@app.get("/api/news")', 1)[0]
        self.assertGreaterEqual(block.count('user: dict = Depends(auth_user)'), 10)

    def test_law3_migration_is_private_and_constrained(self):
        sql = (ROOT / "supabase/migrations/20261106180000_law3_family_and_court.sql").read_text(encoding="utf-8").lower()
        for table in ('marriages','court_cases','court_appeals'):
            self.assertIn(f'alter table public.{table} enable row level security', sql)
            self.assertIn(f'revoke all on public.{table} from anon, authenticated', sql)
        self.assertIn("partner_one_user_id <> partner_two_user_id", sql)
        self.assertIn("status='decided' and decision_public=true", sql)
        self.assertIn("dkd_guard_single_active_marriage", sql)
        self.assertIn("revoke all on function public.dkd_guard_single_active_marriage()", sql)

    def test_divorce_decision_is_only_shown_to_non_requesting_spouse(self):
        core = (ROOT / "core.py").read_text(encoding="utf-8")
        html = (ROOT / "index.html").read_text(encoding="utf-8")
        self.assertIn('"is_divorce_requester": int(item.get("divorce_requested_by") or 0) == uid', core)
        self.assertIn("m.status==='divorce_pending'&&!m.is_divorce_requester", html)

    def test_law3_ui_has_family_and_court_workflows(self):
        html = (ROOT / "index.html").read_text(encoding="utf-8")
        for marker in ("id=\"familyView\"","proposeMarriage(event)","decideMarriage(","fileCourtCase(event)","respondCourtCase(","appealCourtCase(","courtArchive"):
            self.assertIn(marker, html)

    def test_social_write_limits_are_enforced(self):
        source = (ROOT / "core.py").read_text(encoding="utf-8")
        self.assertIn('len(text_value) > 5000', source)
        self.assertIn('len(text_value) > 2000', source)
        self.assertIn('if not subject or not subject.get("is_citizen")', source)


if __name__ == "__main__":
    unittest.main()
