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

    def test_social_blocks_hide_posts_profiles_and_post_actions(self):
        source = (ROOT / "core.py").read_text(encoding="utf-8")
        self.assertIn("def _social_blocked_user_ids(", source)
        self.assertIn("def _social_visible_post(", source)
        self.assertIn("hidden_ids = _social_blocked_user_ids(int(user[\"id\"]))", source)
        self.assertIn("posts = [p for p in posts if int(p[\"author_user_id\"]) not in hidden_ids]", source)
        self.assertIn("target_id in hidden_ids", source)
        self.assertGreaterEqual(source.count("post = _social_visible_post(user, post_id)"), 5)

    def test_social_blocked_comments_and_own_content_controls(self):
        core = (ROOT / "core.py").read_text(encoding="utf-8")
        html = (ROOT / "index.html").read_text(encoding="utf-8")
        self.assertIn('comments = [x for x in comments if int(x["author_user_id"]) not in hidden_ids]', core)
        self.assertIn('"followers_count": len(follower_list)', core)
        self.assertIn('async function deleteSocialPost(id)', html)
        self.assertIn('async function deleteSocialComment(id,postId)', html)
        self.assertIn('async function reportSocialComment(id)', html)

    def test_social_block_ui_uses_async_handler(self):
        html = (ROOT / "index.html").read_text(encoding="utf-8")
        self.assertIn("async function toggleSocialBlock(id){", html)

    def test_social_moderation_and_ownership_routes_are_authenticated(self):
        app = (ROOT / "app.py").read_text(encoding="utf-8")
        core = (ROOT / "core.py").read_text(encoding="utf-8")
        for marker in (
            'def social_report_comment(', 'def social_delete_post(',
            'def social_delete_comment(', 'def admin_social_reports(',
            'def admin_review_social_report(',
        ):
            self.assertIn(marker, app)
        for marker in (
            'def report_social_comment(', 'def delete_own_social_post(',
            'def delete_own_social_comment(', 'def admin_list_social_reports(',
            'def admin_review_social_report(',
        ):
            self.assertIn(marker, core)
        self.assertIn('require_admin(user)', core[core.index('def admin_list_social_reports'):])
        self.assertIn('Depends(admin_user)', app[app.index('def admin_social_reports'):])
        html = (ROOT / "index.html").read_text(encoding="utf-8")
        self.assertIn('id="adminSocialModeration"', html)
        self.assertIn('function loadSocialModeration()', html)

    def test_chat_api_routes_require_authenticated_users(self):
        app = (ROOT / "app.py").read_text(encoding="utf-8")
        core = (ROOT / "core.py").read_text(encoding="utf-8")
        for marker in (
            'def chats_people(', 'def chats_list(', 'def chats_create_direct(',
            'def chats_create_group(', 'def chats_messages(', 'def chats_send_message(',
            'def chats_mark_read(', 'def chats_leave(', 'def chats_add_members(',
        ):
            self.assertIn(marker, app)
        chat_block = app[app.index('# Direct and group chats'):app.index('# Закон №3')]
        self.assertGreaterEqual(chat_block.count('Depends(auth_user)'), 9)
        for marker in (
            'def list_chat_people(', 'def create_direct_chat(', 'def create_group_chat(',
            'def list_chats(', 'def list_chat_messages(', 'def send_chat_message(',
            'def mark_chat_read(', 'def leave_chat(', 'def add_chat_members(',
        ):
            self.assertIn(marker, core)

    def test_chat_ui_uses_subject_user_id(self):
        html = (ROOT / "index.html").read_text(encoding="utf-8")
        chat = html[html.index("let chatPeopleSearchTimer=null;"):html.index("async function loadSocialFeed(reset=true){")]
        self.assertIn("state.profile?.user_id", chat)
        self.assertNotIn("state.profile?.telegram_id", chat)
        self.assertIn('id="chatsView"', html)
        self.assertIn('data-view="chats"', html)

    def test_chat_migration_is_private_and_indexed(self):
        sql = (ROOT / "supabase/migrations/20261106200000_chat_foundation.sql").read_text(encoding="utf-8").lower()
        for table in ('chat_conversations','chat_participants','chat_messages'):
            self.assertIn(f'alter table public.{table} enable row level security', sql)
            self.assertIn(f'revoke all on public.{table} from anon, authenticated', sql)
        self.assertIn("direct_key text unique", sql)
        self.assertIn("char_length(btrim(body)) between 1 and 4000", sql)

    def test_social_write_limits_are_enforced(self):
        source = (ROOT / "core.py").read_text(encoding="utf-8")
        self.assertIn('len(text_value) > 5000', source)
        self.assertIn('len(text_value) > 2000', source)
        self.assertIn('if not subject or not subject.get("is_citizen")', source)


if __name__ == "__main__":
    unittest.main()
