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
