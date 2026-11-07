# DKD Android — Architecture Audit and Stage 1 Decisions

Date: 2026-11-07  
Repository: `NikolasConnor/dkd-miniapp`  
Working branch: `android-dkd-portal-redesign`  
Audited Android head: `589fa8bff3a8f7a43a2487da10116364bb360fe2`

## Product boundary

DKD Android is a standalone native Android client of the DKD platform, branded by NEXORA GROUP.

- Native Kotlin + Jetpack Compose UI. No WebView, iframe, or embedded Mini App.
- Independent Android navigation and screen lifecycle.
- Shared identity and government data through the DKD API and Supabase backend.
- Telegram is not required after Android account pairing.
- Messenger is a separate future product and is not part of DKD Android.
- The Telegram Mini App and production deployment are not changed by Android work.

## Confirmed Mini App design tokens

The current `main/index.html` contains the visual system to port. Initial extracted palette:

| Token | Value |
|---|---|
| Base background | `#0d1024` |
| Deep background | `#10132b` |
| Main surface | `#171b37` |
| Raised surface | `#20264a` |
| Border / muted surface | `#343b69` |
| Primary text | `#f5f6ff` |
| Secondary text | `#a8afd5` |
| Violet accent | `#8b7cff` |
| Blue accent | `#5e8bff` |
| Pink accent | `#f0b7d0` |
| Positive state | `#63d9a0` |
| Negative state | `#ff8298` |

The Android port must reproduce the Mini App's actual gradients, typography, spacing, icon style, surfaces, borders, and interaction states. These values are a token starting point, not permission to approximate the reference with generic Material cards.

## Stage 1 audit — current Android branch

### Existing foundation

- Single Android application module: `app`.
- Kotlin, Jetpack Compose, Material 3, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Java 17.
- `compileSdk 35`, `targetSdk 35`, `minSdk 26`.
- One Activity: `MainActivity`.
- HTTPS API base currently points to `https://dkd-miniapp-1.onrender.com`.
- Pairing-code login: `POST /api/android/auth/redeem`.
- Logout/revocation: `POST /api/android/auth/logout`.
- Session token is encrypted with AES/GCM using an Android Keystore key.
- Existing Compose screens call the backend with Bearer authorization.
- Manifest requests INTERNET and ACCESS_NETWORK_STATE; cleartext traffic is disabled and backup is disabled.
- Latest audited APK workflow at the audited SHA completed successfully; this confirms compilation only, not device behavior or feature correctness.

### Problems to address before feature implementation

1. **Monolithic UI file.** `MainActivity.kt` is approximately 1,830 lines / 105 KB. Navigation, UI, networking, parsing, and feature behavior are mixed together.
2. **No presentation/domain/data separation.** There are no feature ViewModels, repositories, use cases, or explicit UI state models.
3. **Navigation is hand-built.** Tab and detail state are stored directly in composables; there is no Navigation Compose graph or typed route contract.
4. **Old social application surface remains.** Current navigation still includes Feed and Chats. This conflicts with the agreed product boundary: Messenger is separate, and DKD Android is a government app.
5. **Placeholder and live screens coexist.** Several `Stub*` composables coexist with `Portal*` and older social composables. This creates ambiguity about the actual screen implementation.
6. **Dead WebView-era remnants.** `CookieManager`, `JavascriptInterface`, and `AndroidSessionBridge` remain despite there being no WebView. Logout also clears a web cookie. Remove these only as part of the controlled auth refactor, after confirming no other client depends on them.
7. **Networking is hand-written.** `HttpURLConnection` and `JSONObject` are used directly in UI code. There is no central API client, typed DTO boundary, common retry/error policy, or testable repository layer.
8. **API URL is duplicated/hard-coded.** The API URL is declared in Kotlin and Gradle BuildConfig. Use one build-configured source, with explicit debug/staging/production configuration; never place secrets in the APK.
9. **No local data layer.** There is no Room cache or defined offline-first policy. Offline mode must be read-only for authoritative government state; legally significant writes must always be server-confirmed.
10. **No Android unit/UI test suite was found in the audited tree.** Add tests before large feature migrations.
11. **Build tooling.** The repository has no Gradle wrapper in the audited tree; CI installs Gradle 8.9 directly. Add and pin the wrapper so local and CI builds use the same Gradle distribution.
12. **App identity.** Application ID is `ru.dkd.app`; package ID, signing, versioning, and release track need an explicit release plan before distribution.

## Proposed target package structure

Keep one installable Android app initially, but split code by responsibility and feature:

```
app/src/main/java/ru/dkd/app/
  core/
    design/          # Mini App tokens, typography, shapes, gradients, components
    navigation/      # NavHost, routes, deep links
    network/         # Retrofit/OkHttp, auth interceptor, error mapping
    security/        # Keystore-backed session storage
    common/          # shared UI state, formatters, result types
  data/
    remote/          # API DTOs and service interfaces
    local/           # Room database, cache, sync metadata
    repository/      # implementations
  domain/
    model/
    repository/      # repository contracts
    usecase/
  feature/
    auth/
    home/
    services/
    government/
    documents/
    elections/
    parties/
    legislation/
    family/
    court/
    news/
    profile/
```

Each feature owns its screen, ViewModel, UI state, and navigation entry. UI composables do not make HTTP calls directly.

## Proposed technical choices

- Jetpack Compose remains the UI toolkit.
- Navigation Compose for independent native screens and back-stack behavior.
- ViewModel + StateFlow for screen state and lifecycle-aware collection.
- Retrofit + OkHttp (or an equivalent single typed HTTP stack) for API access; one auth interceptor adds the Android Bearer session.
- Kotlin serialization for DTOs, with explicit adapters for legacy response shapes where required.
- Room for read-only/offline cache of profile, public documents, laws, and previously fetched news.
- WorkManager only for safe background refresh/sync. Never silently replay a vote, marriage decision, court filing, law vote, or other legally significant mutation.
- Android Keystore remains the root of protection for the local session token.
- JUnit, MockWebServer, and Compose UI tests for repository, API, and screen behavior.

Dependencies are to be introduced in small, build-verified changes. No broad version upgrades during the initial structural migration.

## Product navigation decision

The agreed DKD Android navigation is government-first:

1. Home
2. State
3. Documents
4. Elections
5. Profile

News, parties, legislation, family services, and court are opened from Home / State / Services. There is no Messenger tab and no in-app chat module in DKD Android.

## Backend/API compatibility finding — release blocker

The audited Android branch contains routes in `app.py` for:
- `/api/profile/gender`
- `/api/family` and marriage/divorce decisions
- `/api/court/cases`, responses, appeals, and public archive

However, the audited production `main` branch's `app.py` does **not** contain those Law No. 3 routes. The Android app currently points at the production Render URL. Therefore these routes must not be treated as deployed or available to the Android app until the backend branch is reviewed, merged with explicit approval, deployed with the required database migrations, and verified. No production merge, deploy, or database change is authorized by this architecture document.

Existing route inventory in the non-production Android branch also includes profile, passport, applications, documents, news, parties, elections, president, Duma, notifications, family, and court endpoints. Each endpoint still needs a request/response contract test before Android feature wiring.

## Law No. 3 — product and domain requirements

The Android architecture must reserve first-class features and domain models for the agreed Law No. 3 additions:

### Citizen gender
- Values: male / female, with an unset state where permitted.
- Citizen chooses the value themselves.
- Profile and relevant documents read the server's authoritative value.
- The audited feature branch has `PATCH /api/profile/gender`; production `main` did not expose it at audit time.

### Official marriage and divorce
- Marriage proposal to another citizen.
- Explicit acceptance by the other party; no unilateral registration.
- Active marriage and family status in profile.
- Digital marriage certificate based on a persisted marriage record.
- Divorce request and the required decision/confirmation flow.
- Notifications to both parties.
- Server-side prevention of conflicting active marriages.
- The feature branch exposes `GET /api/family`, `POST /api/family/proposals`, proposal decision, divorce request, and divorce decision routes. Contract and migration verification remain required.

### DKD Court and AI judge
- Citizens can file a claim with respondent, title, facts, and evidence.
- Respondent can submit a response.
- AI judge must be institutionally independent of President, Government, parties, and personal relationships; jurisdiction is intended to include senior officials, including President, Prime Minister, ministers, and deputies.
- Decisions must be reasoned and based on submitted evidence and applicable DKD law; the system must not invent evidence or sanctions.
- Appeal is reviewed by a different AI-judge instance.
- Final decisions are public; non-public case materials remain confidential.
- Effective decisions are sent to the relevant enforcement mechanism.
- Immutable audit history and decision versions are required.

**Critical audit finding:** the current feature-branch court implementation is only a workflow shell. Filing a response changes status to `awaiting_ai_judge`; filing an appeal changes status to `awaiting_ai_appeal_judge`. The inspected core code contains no AI judge execution, no completed decision-generation pipeline, and no implemented enforcement pipeline. These are backend/domain work, not screens that can be honestly marked complete.

**Constitutional/legal dependency:** earlier discussion identified a possible conflict between the proposed independent court and current Constitution Articles 20–21, including the need to resolve the constitutional basis through the agreed amendment/referendum route before treating the Court as an independent constitutional authority. This must be resolved in the DKD legal model before enabling binding court decisions.

## Existing government rules that must remain invariant

- President appoints Prime Minister and Foreign Minister.
- Administrator appoints President and deputies.
- President controls start/end of Duma elections and can dissolve Parliament no more than once per month.
- Duma term: three months; session duration: three days.
- Duma seats equal the total valid votes and are distributed proportionally by party vote share; no fixed 50-seat chamber.
- No Duma speaker/chairperson.
- Deputy mandate lasts three months and is shown only to a current deputy.
- Presidential certificate is separate from deputy mandate.
- Only presidentially signed decrees appear in the relevant Documents collection.
- Party leader cannot vote in Duma elections.
- Party candidate lists are formed by party leadership from party members.

These rules must be enforced by the server. Hiding a button in Android is not authorization.

## Stage 1 exit criteria

Stage 1 is complete when:

- This architecture decision is committed to the Android working branch.
- Current code structure and blockers are documented.
- The target feature/package structure and state-management pattern are agreed.
- API availability is classified as production-confirmed, branch-only, or not implemented.
- No Mini App, production service, or database has been modified.

## Next implementation stage

Stage 2 is a controlled Android structural refactor: establish the Gradle wrapper, split the monolithic Activity into app shell/core/data/domain/features, add typed API contracts and tests, retain the existing pairing-code login, remove unused WebView-era remnants, and verify the APK before starting the visual port. The Mini App remains read-only as the design source.


## Stage 2 implementation log — 2026-11-07

Work completed on `android-dkd-portal-redesign` after the audit:

- Extracted AES/GCM Android Keystore session persistence into `core/security/SessionStore.kt`.
- Extracted login redemption, logout revocation, authenticated JSON requests, multipart upload, HTTP error mapping, and the API base URL into `core/network/ApiClient.kt`.
- The API URL now comes from the existing `BuildConfig.DKD_API_BASE_URL` instead of a second Kotlin hard-coded constant.
- Removed the unused `CookieManager` logout path and the unused JavaScript bridge. Logout now clears the local token only after server revocation succeeds.
- Removed the Feed and Chats tabs from bottom navigation; navigation is now Home, State, Documents, Elections, Profile.
- Removed unused social feed, people, chat, social profile, and account composables from the DKD Android app. The separate Messenger is not embedded in DKD.
- Moved the one-time pairing-code login screen into `feature/auth/LoginScreen.kt`.
- Added a unit test for API URL construction and made the CI workflow run unit tests before assembling the debug APK.

### Remaining Stage 2 work / explicit limitations

- MainActivity now acts as the app shell; login, Home/State/Profile surfaces, notifications, palette, JSON helpers, network, session security, profile repository, and ViewModel state live in separate packages/files.
- API transport is extracted, but still uses `HttpURLConnection` and `JSONObject`; typed endpoint DTOs and a Retrofit/OkHttp client remain future work. A typed `ProfileRepository` boundary now maps the legacy profile/notifications JSON into a small app model.
- Navigation Compose is now installed and used by the app shell for the five main destinations, notifications, and detail routes.
- A `DkdViewModel` exposes profile and unread-notification state through `StateFlow`; the Compose shell observes it.
- An encrypted Android Keystore-backed last-known profile snapshot (name, role, unread count) is stored for offline startup. This is a limited profile cache, not a full offline data layer or sync engine.
- No Room database or background synchronization has been added.
- The Gradle wrapper is still absent; CI continues to use pinned Gradle 8.9 via setup-gradle.
- The current Home/State/service screens still contain demonstration placeholders. This work does not claim to make government services functional.
- No production API, Render service, Mini App, or database was changed.

The workflow runs `testDebugUnitTest assembleDebug`. API URL construction unit tests were added. GitHub Actions confirmed a successful test + debug APK build for commit `549f1db4b338f2de28bb765af832ad5230aa350f`. Subsequent commits introducing Navigation Compose, ViewModel, encrypted profile cache, profile repository, and visual updates are undergoing their own CI verification; use the latest branch workflow before calling the current head build-verified. A successful APK build alone does not prove runtime behavior; install/device and two-account auth tests are still required.


## Stage 3 implementation log — 2026-11-07

The native visual foundation has started from the existing `main/index.html` reference. The Android palette now uses the Mini App's exact core tokens: `#0d1024`, `#171b37`, `#20264a`, `#343b69`, `#f5f6ff`, `#a8afd5`, `#8b7cff`, `#5e8bff`, and `#f0b7d0`.

Applied in the native client:
- Login screen now uses the DKD violet/blue gradient, rounded panel, accent border, and typography hierarchy.
- Home, State, Profile and common service surfaces now use the Mini App palette, gradient hero panels, rounded cards, and muted text treatment.
- App shell now has a matching branded header, five agreed government tabs, and a notification bell with unread count.
- Notifications are wired into the native navigation graph and styled with the same card/border treatment.
- Documents tab is now connected to the production-confirmed `GET /api/passport` and `GET /api/documents` contracts through a repository and ViewModel. It renders the citizen passport, current deputy mandate, presidential certificate, published presidential decrees, and only signed laws returned by the server. Document status is read from the server; a failed request is not misrepresented as an empty collection.
- Elections tab now reads `/api/votes` through its own repository and ViewModel and renders presidential candidates, party lists, campaign status, and closed results in native cards.
- The Home news card opens a separate official News screen backed by `GET /api/news`, with category and importance styling. This is the state press feed, not a social feed or chat.
- Election casting is deliberately not enabled yet: production `core.cast_vote` does not enforce the agreed ban on party leaders voting in Duma elections. Hiding the button in Android is not a security boundary. The server-side rule must be implemented and deployed before native Duma voting is enabled.
- Version bumped to `0.6.0` / versionCode `6`.

Still outstanding in Stage 3:
- Election lists/results are connected, but ballot submission remains disabled pending server-side enforcement of election eligibility, especially the party-leader Duma voting restriction. Documents and elections still require live-account verification on a device.
- Remaining feature screens (President, Government, Duma, parties, laws, applications, and citizen services) still need individual native designs and API contract verification. News media is not yet displayed in the native News screen.
- Need screenshot comparison on a real Android device against the Mini App at equivalent viewport sizes, then spacing/typography refinements.
- Do not treat any placeholder action as a completed government service.

No Mini App files, production deployment, or database were modified. The visual source remains read-only.

### Stage 3 implementation log — continued

Additional native, server-backed read screens have been added on the Android working branch:

- Elections: reads `GET /api/votes` through an ElectionsRepository and ViewModel; shows presidential candidates, Duma party lists, campaign status, and published results. Voting is intentionally not enabled in Android yet. The inspected production vote handler does not enforce the agreed party-leader exclusion for Duma elections, so enabling a native cast-vote button before that server-side invariant is fixed would be unsafe.
- Official News: reads `GET /api/news` and presents official state publications in a native feed; the Home news card opens this screen.
- Political parties: reads `GET /api/parties` and presents party descriptions, membership counts, leaders, and members. Mutating party-management actions are not enabled in this read-only port.
- State overview: reads `GET /api/parliament-roster` and `GET /api/parties`, and presents the current Duma composition, seats, term dates, and active parties. This is a public institution overview; it does not claim that President/Government controls are implemented.

The Android CI workflow's concurrency policy was changed to avoid cancelling an in-progress APK build on every rapid commit. Python checks passed on the recent branch head, but the newest Android build must still complete successfully before this head is marked build-verified.

### Remaining release and implementation blockers

- Stage 2 is structurally improved but not fully complete against the original target architecture: API calls still use `HttpURLConnection`/JSONObjects rather than Retrofit/OkHttp and fully typed DTOs; Room and a sync engine are absent; Gradle wrapper files are not present.
- Several Home/Profile/service details still use demonstration content. Any service not connected to a verified production API must remain clearly marked as unavailable/demo.
- Android screens have not yet been installed and tested on a physical device. Pairing-code login, logout/revocation, notification read state, documents, elections, news, parties, and state roster require real-account runtime checks.
- No native vote casting is enabled. Before enabling it, the backend must enforce all election eligibility rules (including the Duma party-leader exclusion) and return a contract verified against the production deployment.
- Law No. 3 family/court functions remain blocked by production API and domain/legal gaps documented above; no UI-only implementation can make those services operational.
- No Mini App source, production Render service, or Supabase schema/data was modified by these Android changes.

Additional native profile work:
- Profile now reads the server-authoritative full name, avatar emoji, roles, citizenship status, party membership, and news-notification preference from `GET /api/profile`.
- The notification preference is displayed read-only. A mutation control was deliberately removed because the current `HttpURLConnection` transport does not safely support HTTP PATCH; the API transport must be migrated before enabling profile edits or preference writes in Android.


### Stage 3 implementation log — Presidential administration

Added a native President section on the Android working branch. It first requests `GET /api/president/status` and renders management controls only when the server reports `is_president=true`. The following actions use the existing production-confirmed API routes and retain server-side authorization:

- Publish a presidential decree: `POST /api/president/decrees`.
- List bills and sign or veto a bill: `GET /api/president/laws`, `PATCH /api/president/laws/{law_id}`.
- Schedule Duma elections: `POST /api/president/elections/duma`.
- Dissolve the Duma after an explicit confirmation: `POST /api/president/parliament/dissolve`; the backend enforces the once-per-30-days restriction and active-parliament preconditions.

The screen is linked from the Home government services. These operations have not yet been exercised with a real President account on a device. The app does not bypass server authorization, and no backend or production configuration was changed.


### Stage 3 implementation log — parliamentary and citizen applications

- Added a native Duma workspace backed by the production-confirmed `GET /api/duma`, `POST /api/duma/sessions`, `POST /api/duma/sessions/{id}/close`, `POST /api/duma/sessions/{id}/laws`, and `POST /api/duma/laws/{id}/vote` routes.
- The Duma screen only exposes parliamentary controls after the API authorizes the current account as a deputy or administrator. Vote buttons are rendered only when the server returns `can_vote=true`; the server remains responsible for one-vote-per-deputy, session state, and vote validation.
- Added a citizen applications screen backed by `GET /api/applications`, `POST /api/applications`, and `GET /api/profile`. It displays server-authoritative citizenship status, blocks a duplicate pending citizenship request in the UI, and shows application history and review notes. The backend remains the authoritative duplicate/citizenship guard.
- Added the President administration and citizen applications / Duma entries to the native Home services grid.

These new write flows have not yet been exercised using real citizen, deputy, and President accounts on a physical Android device. CI build verification is pending for the latest feature head. No production backend, database, or Mini App files were modified.


### Stage 3 implementation log — citizen profile and party actions

Additional native write flows now use the production-confirmed API contracts:

- Profile identity editor updates the citizen's full name with `PATCH /api/profile` and avatar with `PATCH /api/profile/avatar`. The avatar selector uses emojis from the backend allowlist. The UI refreshes authoritative profile data after a successful update and reports partial failure if the name was saved but the avatar update failed.
- News-notification preference is now editable in the native profile using `PATCH /api/profile/news-preferences`; the toggle changes only after the server confirms success.
- Citizens can create a party with `POST /api/parties`, join an existing party with `POST /api/parties/{party_id}/join`, and leave with `POST /api/parties/leave`. Leaving requires an explicit confirmation because the backend transfers leadership to another member or dissolves a party when the departing leader is the last member.
- All party membership and profile mutations remain server-authoritative. The Android UI does not grant privileges locally.
- Android app version is now `0.7.0` / versionCode `7`.

These flows have passed source-level API contract inspection but still require a successful Android CI build and real-account device tests. Party settings editing, member removal, candidate-list preparation, election ballot submission, and the remaining services are not claimed complete.


### Architecture status correction — latest branch review

The historical Stage 2 notes above describe the earlier checkpoint. At the latest reviewed branch head:

- The shared API transport has been migrated from `HttpURLConnection` to OkHttp 4.12 with connection/read/write/call timeouts and centralized HTTP error mapping.
- The app uses Navigation Compose, feature ViewModels/StateFlow, repositories, and encrypted Keystore session/profile cache.
- This is not yet a Retrofit + Kotlin Serialization typed-DTO architecture. Most endpoint payloads still use `JSONObject`.
- Room cache, background sync, Gradle wrapper, complete service coverage, and physical device verification remain outstanding.


### Stage 3 implementation log — party administration and parliamentary map

- Party chairs can now edit party name, emoji, description, and color via `PUT /api/parties/{party_id}`. The backend remains authoritative and synchronizes the historical roster labels.
- Party chairs and administrators with server management permission can remove ordinary members via `DELETE /api/parties/{party_id}/members/{member_id}`; the UI asks for confirmation and does not offer removal for the party leader.
- The party chair can open a dedicated native candidate-list editor. It reads `GET /api/elections/duma/party-template` and saves selected member Telegram IDs with `PUT /api/elections/duma/party-template`. The backend verifies party leadership and membership, and synchronizes only unfinished Duma campaigns. This lets the chair prepare the list before an election is opened.
- The State screen now renders a proportional seat-composition bar and party legend from `GET /api/parliament-roster`. It uses each party's current server-provided color, so changing a party color updates the parliament composition display on the next refresh.
- These additions are source/API-contract reviewed; they are not considered build-verified or device-tested until the latest CI and physical-device checks complete.


### Stage 3 implementation log — candidate ordering and President application review

- The party candidate-list editor preserves the order in which candidates are selected and includes up/down controls to explicitly change their order before saving. The backend persists that order in the party template and propagates it to open campaigns.
- The President administration now loads citizen applications from `GET /api/president/applications` and supports approve/reject decisions through `PATCH /api/president/applications/{application_id}`. Each decision requires confirmation; citizenship approval triggers passport issuance on the backend and the applicant receives a server notification.
- This feature set remains pending the current Android build and real-account verification.


### Stage 3 implementation log — presidential administration

- The native President workspace now reads the President-only subject and assignable-role directory and supports appointment of the Prime Minister and Foreign Minister through `PUT /api/president/roles`.
- The interface deliberately exposes only those two presidentially assignable positions; the backend enforces one holder per limited role and rejects unauthorized assignments.
- The President can review citizen applications directly in Android: approval/rejection is confirmed before sending; approval of citizenship causes the server to issue the passport and notify the applicant.
- The President can publish decrees, sign/veto laws, schedule Duma elections, and dissolve the Duma using the existing server-authorized routes. All actions require server-side role checks; no local role grants are performed.
- These additions are awaiting the latest Android CI run and still require live-account testing.


## Stage 3/4 safety follow-up — 2026-11-07

- Added a shared, pure eligibility rule for identifying active party leaders by numeric user ID, with a legacy username fallback.
- The non-production `android-dkd-portal-redesign` backend branch now rejects a party leader's Duma election ballot before inserting a vote. Presidential ballots remain unaffected.
- Added Python unit tests for leader, non-leader, legacy membership, dissolved-party, and missing-membership cases; CI now runs them.
- Disabled OkHttp automatic connection retries. This avoids silently replaying non-idempotent operations such as votes, role changes, and presidential decisions after ambiguous network failures.
- These backend changes are confined to the Android working branch. Production `main`, Render, and Supabase are unchanged.
- The Android app still points to the production Render URL, so Duma vote submission remains intentionally disabled in Android until this backend rule is explicitly reviewed, merged, deployed, and verified in production.
- Last known successful Android artifact was built from commit `7f617b4d48c32fc67ce20d0d5be303fafbe56361`; it predates this backend-only eligibility hardening. Rebuild after Android source changes.
