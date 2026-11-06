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
        )
        for name in expected:
            self.assertIn(name, source)
        block = source.split('# Социальная сеть: лента, публикации, комментарии и реакции', 1)[1]
        block = block.split('@app.get("/api/news")', 1)[0]
        self.assertEqual(block.count('user: dict = Depends(auth_user)'), 4)

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

    def test_social_write_limits_are_enforced(self):
        source = (ROOT / "core.py").read_text(encoding="utf-8")
        self.assertIn('len(text_value) > 5000', source)
        self.assertIn('len(text_value) > 2000', source)
        self.assertIn('if not subject or not subject.get("is_citizen")', source)


if __name__ == "__main__":
    unittest.main()
