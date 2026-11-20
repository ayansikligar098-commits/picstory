# 70a 📸

**70a** — an original photo-sharing app (package `com.picstory.app`):
photo posts, stories, highlights, follow and explore — the familiar
photo-app features, with original branding. Nothing is copied from any
other app — no borrowed names, logos or branding.

> **Branding:** the name "70a", deep purple + amber colours, and the
> user-supplied anime-mask logo are original to this app.

## What's in v1.0

- **Auth** — register (name, username, password) / log in, JWT saved
  on the phone, log out from Settings.
- **Profiles** — display name, username, bio, initials avatar, post
  count, followers / following counts, own profile editable.
- **Follow** — follow / unfollow any user, followers / following lists.
- **Photo posts** — upload a photo + caption, feed (people you follow
  + your own posts, newest first), like toggle with count, comments,
  post detail view, delete your own post.
- **Stories** — photo story, 24-hour expiry (enforced by the server),
  stories row on Home, full-screen viewer with auto-advance, viewers
  count on your own stories.
- **Highlights** — save your own stories (active or expired) into a
  named highlight, shown as circles on your profile, tap to view.
- **Explore** — grid of all posts + user search by username / name.
- **Settings** — edit name/bio, light / dark / system theme, **server
  URL** (changeable — the phone cannot reach `localhost`). The login
  screen also has a Server URL field, so the app works before login.
- **No ads. No video posts, no DMs, no push notifications in v1.**

## Running it

### Backend (this VM, for testing)

```bash
cd ~/workspace/picstory/backend
./run.sh        # http://127.0.0.1:8000  (SQLite dev DB: picstory.db)
```

Test accounts are NOT shipped — register two accounts in the app (or
via the API). Backend E2E test:

```bash
cd ~/workspace/picstory/backend
.venv/bin/python test_e2e.py
```

### Android app (this VM)

```bash
cd ~/workspace/picstory
./build.sh      # -> ./app-debug.apk (debug-signed, testing only)
```

Copy `app-debug.apk` to the phone and install it. **The app defaults
to `http://127.0.0.1:8000`, which the phone cannot reach** — for phone
testing the backend needs a public link (free Render deploy): put the
deployed URL in the app's Server URL field on the login screen (or in
Settings → Server URL).

### On a normal machine

```bash
./gradlew assembleDebug
```

## API (backend, FastAPI + SQLAlchemy, JWT)

| Route | What it does |
|---|---|
| `GET /health` | `{"status":"ok"}` |
| `POST /api/auth/register` · `POST /api/auth/login` · `GET /api/auth/me` | Auth |
| `GET /api/users?q=` · `GET /api/users/{id}` · `PATCH /api/users/me` | Users + profile |
| `POST /api/users/{id}/follow` · `GET /api/users/{id}/followers` · `GET /api/users/{id}/following` | Follow |
| `POST /api/upload` | Image upload → `/media/<file>` |
| `GET /api/posts` (feed) · `GET /api/explore` · `GET /api/users/{id}/posts` · `POST /api/posts` · `GET/DELETE /api/posts/{id}` | Posts |
| `POST /api/posts/{id}/like` · `GET/POST /api/posts/{id}/comments` | Likes + comments |
| `GET /api/stories` · `POST /api/stories` · `POST /api/stories/{id}/view` · `GET /api/stories/{id}/viewers` | Stories (24h expiry) |
| `POST /api/highlights` · `GET /api/users/{id}/highlights` · `GET/DELETE /api/highlights/{id}` | Highlights |

`DATABASE_URL` env var switches SQLite → Postgres in production.
JWT secret lives in `backend/.env` only (fresh random dev key).

## Building on this VM

The sandbox blocks direct TCP from Java, so `./build.sh` runs
Gradle's main class in-process (`--no-daemon`, `--offline`) and
dependencies are mirrored with curl into `../local-maven`
(`mirror.py`). **Do not use plain `./gradlew` here.**

Toolchain: JDK 17 (Temurin), Android SDK (build-tools 34.0.0,
android-34), Gradle 8.7 — see `~/workspace/android/env.sh`.
App deps: OkHttp 4.12.0 (REST), Kotlin 1.9.24, AGP 8.5.2.
No AndroidX UI libraries are used (framework widgets only).

## Honest limits

- **No emulator exists on this VM** (`/dev/kvm` missing), so the app
  has NOT been play-tested on a real device — the user is the first
  play-tester. Build + aapt2 checks pass; backend E2E is verified.
- Phone testing needs the backend on a public URL (Render free deploy)
  — not done yet.
- No push notifications (no FCM), no video posts, no DMs, no
  end-to-end encryption, no profile-photo upload yet (initials
  avatars are used).
- Render Free (when deployed) restarts wipe the SQLite DB — same
  caveat as the other free-tier projects.
