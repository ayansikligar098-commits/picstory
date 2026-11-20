"""PicStory backend — FastAPI app: auth, follow, photo posts, stories, highlights."""
import os
import re
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import List, Optional

from fastapi import (
    Depends,
    FastAPI,
    File,
    HTTPException,
    Query,
    UploadFile,
)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from sqlalchemy import or_, text
from sqlalchemy.orm import Session

from .db import Base, engine, get_db
from .models import (
    Block,
    Conversation,
    Follow,
    Highlight,
    HighlightStory,
    Message,
    Notification,
    Post,
    PostComment,
    PostLike,
    Story,
    StoryLike,
    StoryView,
    TypingState,
    User,
)
from .security import create_token, decode_token, hash_password, verify_password

BASE_DIR = Path(__file__).resolve().parent.parent
STORAGE_DIR = BASE_DIR / "storage"
STORAGE_DIR.mkdir(exist_ok=True)
MAX_UPLOAD_MB = 10
MAX_VIDEO_MB = 50

Base.metadata.create_all(bind=engine)


def _migrate_user_columns():
    """Add users.avatar_url / users.music_url / users.last_seen_at on
    databases created before those columns existed.

    create_all() only creates missing TABLES, never missing columns, so a
    database built by an older version would 500 on every user response
    without this. Safe to run on every startup (no-op when present).
    """
    try:
        with engine.begin() as conn:
            if engine.dialect.name == "sqlite":
                cols = [r[1] for r in conn.execute(text("PRAGMA table_info(users)"))]
                for col in ("avatar_url", "music_url"):
                    if col not in cols:
                        conn.execute(
                            text(f"ALTER TABLE users ADD COLUMN {col} VARCHAR(512) DEFAULT ''")
                        )
                if "last_seen_at" not in cols:
                    conn.execute(text("ALTER TABLE users ADD COLUMN last_seen_at DATETIME"))
            else:
                for col in ("avatar_url", "music_url"):
                    conn.execute(
                        text(f"ALTER TABLE users ADD COLUMN IF NOT EXISTS {col} VARCHAR(512) DEFAULT ''")
                    )
                conn.execute(
                    text("ALTER TABLE users ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP")
                )
    except Exception:
        # Never crash startup over a migration; the columns exist in every
        # database built by the current code, so this is belt-and-braces.
        pass


_migrate_user_columns()


def _migrate_blocks():
    """Create the blocks table on databases built before v3.1 (same
    reason as above: create_all() only creates missing tables, and this
    runs as belt-and-braces alongside it so a live Supabase Postgres
    database gets the table even if create_all was skipped). Safe no-op
    when the table already exists."""
    try:
        with engine.begin() as conn:
            if engine.dialect.name == "sqlite":
                conn.execute(text(
                    "CREATE TABLE IF NOT EXISTS blocks ("
                    "id INTEGER NOT NULL PRIMARY KEY, "
                    "blocker_id INTEGER NOT NULL, "
                    "blocked_id INTEGER NOT NULL, "
                    "created_at DATETIME NOT NULL, "
                    "FOREIGN KEY(blocker_id) REFERENCES users (id), "
                    "FOREIGN KEY(blocked_id) REFERENCES users (id))"
                ))
                conn.execute(text(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_block "
                    "ON blocks (blocker_id, blocked_id)"
                ))
            else:
                conn.execute(text(
                    "CREATE TABLE IF NOT EXISTS blocks ("
                    "id SERIAL PRIMARY KEY, "
                    "blocker_id INTEGER NOT NULL REFERENCES users (id), "
                    "blocked_id INTEGER NOT NULL REFERENCES users (id), "
                    "created_at TIMESTAMP NOT NULL)"
                ))
                conn.execute(text(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_block "
                    "ON blocks (blocker_id, blocked_id)"
                ))
    except Exception:
        pass


def _migrate_new_columns():
    """Add dm_messages.media_url/media_kind, posts.video_url and
    stories.video_url on databases built before those columns existed
    (same reason as above: create_all() never adds missing columns).
    Safe no-op when present."""
    try:
        with engine.begin() as conn:
            if engine.dialect.name == "sqlite":
                dm_cols = [r[1] for r in conn.execute(text("PRAGMA table_info(dm_messages)"))]
                if "media_url" not in dm_cols:
                    conn.execute(text("ALTER TABLE dm_messages ADD COLUMN media_url VARCHAR(512) DEFAULT ''"))
                if "media_kind" not in dm_cols:
                    conn.execute(text("ALTER TABLE dm_messages ADD COLUMN media_kind VARCHAR(16) DEFAULT 'text'"))
                post_cols = [r[1] for r in conn.execute(text("PRAGMA table_info(posts)"))]
                if "video_url" not in post_cols:
                    conn.execute(text("ALTER TABLE posts ADD COLUMN video_url VARCHAR(512) DEFAULT ''"))
                story_cols = [r[1] for r in conn.execute(text("PRAGMA table_info(stories)"))]
                if "video_url" not in story_cols:
                    conn.execute(text("ALTER TABLE stories ADD COLUMN video_url VARCHAR(512) DEFAULT ''"))
            else:
                conn.execute(
                    text("ALTER TABLE dm_messages ADD COLUMN IF NOT EXISTS media_url VARCHAR(512) DEFAULT ''")
                )
                conn.execute(
                    text("ALTER TABLE dm_messages ADD COLUMN IF NOT EXISTS media_kind VARCHAR(16) DEFAULT 'text'")
                )
                conn.execute(
                    text("ALTER TABLE posts ADD COLUMN IF NOT EXISTS video_url VARCHAR(512) DEFAULT ''")
                )
                conn.execute(
                    text("ALTER TABLE stories ADD COLUMN IF NOT EXISTS video_url VARCHAR(512) DEFAULT ''")
                )
    except Exception:
        pass


_migrate_new_columns()


_migrate_blocks()

app = FastAPI(title="PicStory", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.mount("/media", StaticFiles(directory=str(STORAGE_DIR)), name="media")

bearer = HTTPBearer(auto_error=False)


def now_naive():
    return datetime.now(timezone.utc).replace(tzinfo=None)


def iso(dt):
    if dt is None:
        return None
    return dt.isoformat() + "Z"


def get_current_user(
    creds: Optional[HTTPAuthorizationCredentials] = Depends(bearer),
    db: Session = Depends(get_db),
) -> User:
    if not creds or not creds.credentials:
        raise HTTPException(status_code=401, detail="Not signed in")
    user_id = decode_token(creds.credentials)
    if user_id is None:
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    user = db.query(User).get(user_id)
    if not user:
        raise HTTPException(status_code=401, detail="Account not found")
    # Presence: every authenticated request proves the user is active now.
    # Cheap at this scale; the DM/chat pollers keep an open screen "online".
    user.last_seen_at = now_naive()
    db.commit()
    return user


ONLINE_WINDOW_SECONDS = 120


def is_online(u: User) -> bool:
    if u.last_seen_at is None:
        return False
    return (now_naive() - u.last_seen_at).total_seconds() <= ONLINE_WINDOW_SECONDS


def counts(db: Session, user_id: int):
    post_count = db.query(Post).filter(Post.user_id == user_id).count()
    followers = db.query(Follow).filter(Follow.following_id == user_id).count()
    following = db.query(Follow).filter(Follow.follower_id == user_id).count()
    return post_count, followers, following


def user_out(u: User, me_id: int, db: Session) -> dict:
    post_count, followers, following = counts(db, u.id)
    followed = (
        db.query(Follow)
        .filter(Follow.follower_id == me_id, Follow.following_id == u.id)
        .first()
        is not None
    ) if me_id != u.id else False
    blocked = (
        db.query(Block)
        .filter(Block.blocker_id == me_id, Block.blocked_id == u.id)
        .first()
        is not None
    ) if me_id != u.id else False
    # v3.3: did the viewed user block me?
    blocked_me = (
        db.query(Block)
        .filter(Block.blocker_id == u.id, Block.blocked_id == me_id)
        .first()
        is not None
    ) if me_id != u.id else False
    # v3.3: a block in either direction hides online activity both ways.
    blocked_either = blocked or blocked_me
    return {
        "id": u.id,
        "username": u.username,
        "display_name": u.display_name,
        "bio": u.bio or "",
        "avatar_url": u.avatar_url or "",
        "music_url": u.music_url or "",
        "post_count": post_count,
        "followers_count": followers,
        "following_count": following,
        "followed_by_me": followed,
        "blocked_by_me": blocked,
        "blocked_me": blocked_me,
        "online": is_online(u) and not blocked_either,
        "last_seen_at": None if blocked_either else iso(u.last_seen_at),
        "created_at": iso(u.created_at),
    }


def my_blocked_ids(db: Session, me_id: int) -> set:
    """User ids I have blocked."""
    return {r.blocked_id for r in
            db.query(Block).filter(Block.blocker_id == me_id).all()}


def blocked_either_way_ids(db: Session, me_id: int) -> set:
    """User ids with a block in either direction with me (v3.3)."""
    out = my_blocked_ids(db, me_id)
    out |= {r.blocker_id for r in
            db.query(Block).filter(Block.blocked_id == me_id).all()}
    return out


def blocked_either_way(db: Session, a_id: int, b_id: int) -> bool:
    """True if a blocked b OR b blocked a."""
    return (
        db.query(Block)
        .filter(or_(
            (Block.blocker_id == a_id) & (Block.blocked_id == b_id),
            (Block.blocker_id == b_id) & (Block.blocked_id == a_id),
        ))
        .first()
        is not None
    )


def notify(db: Session, recipient_id: int, actor: User, ntype: str,
             ref_id: Optional[int], body: str):
    """Create one in-app notification. Never notify yourself; never crash
    the request that triggered it (best effort)."""
    if recipient_id == actor.id:
        return
    try:
        db.add(Notification(
            user_id=recipient_id,
            actor_id=actor.id,
            type=ntype,
            ref_id=ref_id,
            body=body[:500],
        ))
    except Exception:
        db.rollback()


def notification_out(db: Session, n: Notification, me_id: int) -> dict:
    actor = db.query(User).get(n.actor_id)
    return {
        "id": n.id,
        "type": n.type,
        "ref_id": n.ref_id,
        "body": n.body or "",
        "is_read": bool(n.is_read),
        "created_at": iso(n.created_at),
        "actor": user_out(actor, me_id, db) if actor else None,
    }


def story_out(s: Story, me_id: int, db: Session) -> dict:
    viewed = (
        db.query(StoryView)
        .filter(StoryView.story_id == s.id, StoryView.viewer_id == me_id)
        .first()
        is not None
    )
    like_count = db.query(StoryLike).filter(StoryLike.story_id == s.id).count()
    liked = (
        db.query(StoryLike)
        .filter(StoryLike.story_id == s.id, StoryLike.user_id == me_id)
        .first()
        is not None
    )
    return {
        "id": s.id,
        "user_id": s.user_id,
        "kind": s.kind,
        "text": s.text or "",
        "image_url": s.image_url or "",
        "video_url": s.video_url or "",
        "bg_color": s.bg_color or "#5B21B6",
        "viewed_by_me": viewed,
        "like_count": like_count,
        "liked_by_me": liked,
        "created_at": iso(s.created_at),
        "expires_at": iso(s.expires_at),
    }


def post_out(db: Session, post: Post, me_id: int) -> dict:
    author = db.query(User).get(post.user_id)
    like_count = db.query(PostLike).filter(PostLike.post_id == post.id).count()
    comment_count = db.query(PostComment).filter(PostComment.post_id == post.id).count()
    liked = (
        db.query(PostLike)
        .filter(PostLike.post_id == post.id, PostLike.user_id == me_id)
        .first()
        is not None
    )
    return {
        "id": post.id,
        "user_id": post.user_id,
        "author": user_out(author, me_id, db) if author else None,
        "caption": post.caption or "",
        "image_url": post.image_url or "",
        "video_url": post.video_url or "",
        "like_count": like_count,
        "comment_count": comment_count,
        "liked_by_me": liked,
        "created_at": iso(post.created_at),
    }


def highlight_out(db: Session, h: Highlight, me_id: int, with_stories: bool = False) -> dict:
    links = (
        db.query(HighlightStory)
        .filter(HighlightStory.highlight_id == h.id)
        .order_by(HighlightStory.position.asc(), HighlightStory.id.asc())
        .all()
    )
    stories = []
    cover_url = ""
    for link in links:
        s = db.query(Story).get(link.story_id)
        if s:
            stories.append(story_out(s, me_id, db))
            if not cover_url and s.image_url:
                cover_url = s.image_url
    out = {
        "id": h.id,
        "user_id": h.user_id,
        "name": h.name,
        "cover_url": cover_url,
        "story_count": len(stories),
        "created_at": iso(h.created_at),
    }
    if with_stories:
        out["stories"] = stories
    return out


# ---------------------------------------------------------------------------
# Schemas
# ---------------------------------------------------------------------------
class RegisterIn(BaseModel):
    username: str = Field(..., min_length=3, max_length=32)
    display_name: str = Field(..., min_length=1, max_length=64)
    password: str = Field(..., min_length=4, max_length=128)


class LoginIn(BaseModel):
    username: str
    password: str


class ProfileIn(BaseModel):
    display_name: Optional[str] = None
    username: Optional[str] = None
    bio: Optional[str] = None
    avatar_url: Optional[str] = None
    music_url: Optional[str] = None


class StoryIn(BaseModel):
    image_url: str = ""
    video_url: str = ""
    text: str = ""


class PostIn(BaseModel):
    caption: str = ""
    image_url: str = ""
    video_url: str = ""


class CommentIn(BaseModel):
    text: str = Field(..., min_length=1, max_length=500)


class DmIn(BaseModel):
    # Validated by hand in the handler (empty / too long -> 400, not 422).
    body: str = ""
    media_url: str = ""
    media_kind: str = "text"  # text | image | video


class HighlightIn(BaseModel):
    name: str = Field(..., min_length=1, max_length=64)
    story_ids: List[int] = []


USERNAME_RE = re.compile(r"^[A-Za-z0-9_]{3,32}$")


# ---------------------------------------------------------------------------
# Health + auth
# ---------------------------------------------------------------------------
@app.get("/health")
def health():
    return {"status": "ok", "service": "picstory-backend"}


@app.post("/api/auth/register")
def register(body: RegisterIn, db: Session = Depends(get_db)):
    username = body.username.strip().lower()
    if not USERNAME_RE.match(username):
        raise HTTPException(
            status_code=400,
            detail="Username must be 3-32 letters, numbers or underscore",
        )
    if db.query(User).filter(User.username == username).first():
        raise HTTPException(status_code=400, detail="Username already taken")
    user = User(
        username=username,
        display_name=body.display_name.strip(),
        password_hash=hash_password(body.password),
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return {"token": create_token(user.id), "user": user_out(user, user.id, db)}


@app.post("/api/auth/login")
def login(body: LoginIn, db: Session = Depends(get_db)):
    user = (
        db.query(User)
        .filter(User.username == body.username.strip().lower())
        .first()
    )
    if not user or not verify_password(body.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Wrong username or password")
    return {"token": create_token(user.id), "user": user_out(user, user.id, db)}


@app.get("/api/auth/me")
def me(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    return user_out(user, user.id, db)


@app.patch("/api/users/me")
def update_me(body: ProfileIn, db: Session = Depends(get_db),
              user: User = Depends(get_current_user)):
    if body.display_name is not None and body.display_name.strip():
        user.display_name = body.display_name.strip()[:64]
    if body.username is not None:
        new_username = body.username.strip().lower()
        if not USERNAME_RE.match(new_username):
            raise HTTPException(
                status_code=400,
                detail="Username must be 3-32 letters, numbers or underscore",
            )
        taken = (
            db.query(User)
            .filter(User.username == new_username, User.id != user.id)
            .first()
        )
        if taken:
            raise HTTPException(status_code=409, detail="Username already taken")
        user.username = new_username
    if body.bio is not None:
        user.bio = body.bio[:200]
    if body.avatar_url is not None:
        user.avatar_url = body.avatar_url[:512]
    if body.music_url is not None:
        user.music_url = body.music_url[:512]
    db.commit()
    return user_out(user, user.id, db)


# ---------------------------------------------------------------------------
# Users + follow
# ---------------------------------------------------------------------------
@app.get("/api/users")
def search_users(
    q: str = Query("", min_length=0, max_length=64),
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    query = db.query(User).filter(User.id != user.id)
    if q.strip():
        like = f"%{q.strip()}%"
        query = query.filter(
            or_(User.username.ilike(like), User.display_name.ilike(like))
        )
    return [user_out(u, user.id, db) for u in query.order_by(User.display_name).limit(50).all()]


@app.get("/api/users/blocked")
def list_blocked(db: Session = Depends(get_db),
                 user: User = Depends(get_current_user)):
    # NOTE: this MUST stay above GET /api/users/{user_id} — Starlette
    # matches "/api/users/blocked" against the {user_id} route first and
    # answers 422 instead of trying the next route.
    rows = (
        db.query(Block)
        .filter(Block.blocker_id == user.id)
        .order_by(Block.created_at.desc(), Block.id.desc())
        .all()
    )
    out = []
    for r in rows:
        u = db.query(User).get(r.blocked_id)
        if u:
            out.append(user_out(u, user.id, db))
    return out


@app.get("/api/users/{user_id}")
def get_user(user_id: int, db: Session = Depends(get_db),
             user: User = Depends(get_current_user)):
    other = db.query(User).get(user_id)
    if not other:
        raise HTTPException(status_code=404, detail="User not found")
    return user_out(other, user.id, db)


@app.post("/api/users/{user_id}/follow")
def toggle_follow(user_id: int, db: Session = Depends(get_db),
                  user: User = Depends(get_current_user)):
    if user_id == user.id:
        raise HTTPException(status_code=400, detail="You cannot follow yourself")
    other = db.query(User).get(user_id)
    if not other:
        raise HTTPException(status_code=404, detail="User not found")
    existing = (
        db.query(Follow)
        .filter(Follow.follower_id == user.id, Follow.following_id == user_id)
        .first()
    )
    if existing:
        db.delete(existing)
        following = False
    else:
        db.add(Follow(follower_id=user.id, following_id=user_id))
        following = True
        notify(db, user_id, user, "follow", user.id,
               f"{user.display_name} started following you")
    db.commit()
    followers = db.query(Follow).filter(Follow.following_id == user_id).count()
    return {"following": following, "followers_count": followers}


# ---------------------------------------------------------------------------
# Blocks (v3.1) — blocking also removes follows in BOTH directions, and
# hides the blocked user from the blocker's stories/feed/DMs (see the
# filters in list_stories / list_posts and the 403 in send_dm).
# ---------------------------------------------------------------------------
@app.post("/api/users/{user_id}/block")
def block_user(user_id: int, db: Session = Depends(get_db),
               user: User = Depends(get_current_user)):
    if user_id == user.id:
        raise HTTPException(status_code=400, detail="You cannot block yourself")
    other = db.query(User).get(user_id)
    if not other:
        raise HTTPException(status_code=404, detail="User not found")
    existing = (
        db.query(Block)
        .filter(Block.blocker_id == user.id, Block.blocked_id == user_id)
        .first()
    )
    if not existing:
        db.add(Block(blocker_id=user.id, blocked_id=user_id))
    # Follows in both directions go away with the block.
    db.query(Follow).filter(
        or_(
            (Follow.follower_id == user.id) & (Follow.following_id == user_id),
            (Follow.follower_id == user_id) & (Follow.following_id == user.id),
        )
    ).delete(synchronize_session=False)
    db.commit()
    return {"blocked": True}


@app.post("/api/users/{user_id}/unblock")
def unblock_user(user_id: int, db: Session = Depends(get_db),
                 user: User = Depends(get_current_user)):
    if user_id == user.id:
        raise HTTPException(status_code=400, detail="You cannot block yourself")
    other = db.query(User).get(user_id)
    if not other:
        raise HTTPException(status_code=404, detail="User not found")
    db.query(Block).filter(
        Block.blocker_id == user.id, Block.blocked_id == user_id
    ).delete(synchronize_session=False)
    db.commit()
    return {"blocked": False}


@app.get("/api/users/{user_id}/followers")
def list_followers(user_id: int, db: Session = Depends(get_db),
                   user: User = Depends(get_current_user)):
    other = db.query(User).get(user_id)
    if not other:
        raise HTTPException(status_code=404, detail="User not found")
    rows = (
        db.query(Follow)
        .filter(Follow.following_id == user_id)
        .order_by(Follow.created_at.desc())
        .all()
    )
    out = []
    for r in rows:
        u = db.query(User).get(r.follower_id)
        if u:
            out.append(user_out(u, user.id, db))
    return out


@app.get("/api/users/{user_id}/following")
def list_following(user_id: int, db: Session = Depends(get_db),
                   user: User = Depends(get_current_user)):
    other = db.query(User).get(user_id)
    if not other:
        raise HTTPException(status_code=404, detail="User not found")
    rows = (
        db.query(Follow)
        .filter(Follow.follower_id == user_id)
        .order_by(Follow.created_at.desc())
        .all()
    )
    out = []
    for r in rows:
        u = db.query(User).get(r.following_id)
        if u:
            out.append(user_out(u, user.id, db))
    return out


# ---------------------------------------------------------------------------
# Uploads — local disk by default; Cloudinary when configured (v2.4)
# ---------------------------------------------------------------------------
def _cloudinary_configured() -> bool:
    """True only when ALL THREE Cloudinary credentials are set. Any one
    missing means local-storage fallback — never a half-configured upload."""
    return all(
        os.environ.get(k)
        for k in (
            "CLOUDINARY_CLOUD_NAME",
            "CLOUDINARY_API_KEY",
            "CLOUDINARY_API_SECRET",
        )
    )


def _upload_to_cloudinary(data: bytes, ext: str) -> str:
    """Upload bytes to Cloudinary (folder "picstory") and return the secure
    URL. resource_type="auto" covers images, video and audio. The SDK is
    imported lazily so the local fallback never depends on it. The API
    secret is used only by cloudinary.config() and is never logged or
    returned."""
    import cloudinary
    import cloudinary.uploader

    cloudinary.config(
        cloud_name=os.environ["CLOUDINARY_CLOUD_NAME"],
        api_key=os.environ["CLOUDINARY_API_KEY"],
        api_secret=os.environ["CLOUDINARY_API_SECRET"],
        secure=True,
    )
    # Upload from a temp file (not raw bytes) so Cloudinary can infer the
    # format from the extension — that is what makes video/audio land in
    # the right resource type with a playable URL.
    suffix = f".{ext}" if ext else ""
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(data)
        tmp_path = tmp.name
    try:
        result = cloudinary.uploader.upload(
            tmp_path, folder="picstory", resource_type="auto",
        )
    finally:
        os.unlink(tmp_path)
    url = result.get("secure_url") or result.get("url")
    if not url:
        raise RuntimeError("Cloudinary upload returned no URL")
    return url


@app.post("/api/upload")
async def upload(
    file: UploadFile = File(...),
    kind: str = Query("image"),
    user: User = Depends(get_current_user),
):
    ctype = (file.content_type or "").lower()
    ext = (file.filename or "").rsplit(".", 1)[-1].lower() if "." in (file.filename or "") else ""
    if kind == "audio":
        # Profile music: audio files only.
        if not ctype.startswith("audio/"):
            raise HTTPException(status_code=400, detail="Only audio files can be uploaded as music")
        if ext not in ("mp3", "m4a", "aac", "ogg", "wav"):
            raise HTTPException(
                status_code=400, detail="Audio must be mp3, m4a, aac, ogg or wav")
        max_mb = MAX_UPLOAD_MB
    elif kind == "video":
        # Reels / video DMs: video files only, up to 50 MB.
        if not ctype.startswith("video/"):
            raise HTTPException(status_code=400, detail="Only videos can be uploaded as video")
        if ext not in ("mp4", "mov"):
            raise HTTPException(status_code=400, detail="Video must be mp4 or mov")
        max_mb = MAX_VIDEO_MB
    else:
        if not ctype.startswith("image/"):
            raise HTTPException(status_code=400, detail="Only images can be uploaded")
        if ext not in ("jpg", "jpeg", "png", "webp", "gif"):
            ext = "jpg"
        max_mb = MAX_UPLOAD_MB
    data = await file.read()
    if len(data) > max_mb * 1024 * 1024:
        raise HTTPException(status_code=400, detail=f"File is too big (max {max_mb} MB)")
    if _cloudinary_configured():
        # Cloud storage: the file lives on Cloudinary, so it survives
        # Render restarts/redeploys. On failure return 502 (retryable) —
        # never silently fall back to local disk, which would split media
        # across two stores and lose the file on the next restart anyway.
        try:
            return {"url": _upload_to_cloudinary(data, ext)}
        except Exception:
            raise HTTPException(
                status_code=502, detail="Media upload failed, please try again"
            )
    name = f"{uuid.uuid4().hex}.{ext}"
    (STORAGE_DIR / name).write_bytes(data)
    return {"url": f"/media/{name}"}


# ---------------------------------------------------------------------------
# Posts (feed = people I follow + me, newest first)
# ---------------------------------------------------------------------------
@app.get("/api/posts")
def list_posts(
    limit: int = Query(20, ge=1, le=50),
    offset: int = Query(0, ge=0),
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    followed_ids = [r.following_id for r in
                    db.query(Follow).filter(Follow.follower_id == user.id).all()]
    allowed = set(followed_ids + [user.id])
    # v3.3: a block in either direction hides posts both ways.
    allowed -= blocked_either_way_ids(db, user.id)
    posts = (
        db.query(Post).filter(Post.user_id.in_(allowed))
        .order_by(Post.created_at.desc(), Post.id.desc())
        .offset(offset).limit(limit).all()
    )
    return [post_out(db, p, user.id) for p in posts]


@app.get("/api/explore")
def explore(
    limit: int = Query(50, ge=1, le=100),
    offset: int = Query(0, ge=0),
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    # v3.3: blocked users' posts (either direction) never appear here.
    hidden = blocked_either_way_ids(db, user.id)
    posts = (
        db.query(Post).filter(~Post.user_id.in_(hidden))
        .order_by(Post.created_at.desc(), Post.id.desc())
        .offset(offset).limit(limit).all()
    )
    return [post_out(db, p, user.id) for p in posts]


@app.get("/api/users/{user_id}/posts")
def list_user_posts(user_id: int, db: Session = Depends(get_db),
                    user: User = Depends(get_current_user)):
    other = db.query(User).get(user_id)
    if not other:
        raise HTTPException(status_code=404, detail="User not found")
    # v3.3: a block in either direction hides the profile's posts.
    if user_id != user.id and blocked_either_way(db, user.id, user_id):
        return []
    posts = (
        db.query(Post).filter(Post.user_id == user_id)
        .order_by(Post.created_at.desc(), Post.id.desc()).all()
    )
    return [post_out(db, p, user.id) for p in posts]


@app.post("/api/posts")
def create_post(body: PostIn, db: Session = Depends(get_db),
                user: User = Depends(get_current_user)):
    if not body.image_url.strip() and not body.video_url.strip():
        raise HTTPException(status_code=400, detail="Photo posts need a photo, Reels need a video")
    post = Post(
        user_id=user.id,
        caption=body.caption.strip()[:1000],
        image_url=body.image_url.strip()[:512],
        video_url=body.video_url.strip()[:512],
    )
    db.add(post)
    db.commit()
    db.refresh(post)
    return post_out(db, post, user.id)


@app.get("/api/posts/{post_id}")
def get_post(post_id: int, db: Session = Depends(get_db),
             user: User = Depends(get_current_user)):
    post = db.query(Post).get(post_id)
    if not post:
        raise HTTPException(status_code=404, detail="Post not found")
    # v3.3: a block in either direction hides the post (and its comments).
    if post.user_id != user.id and blocked_either_way(db, user.id, post.user_id):
        raise HTTPException(status_code=404, detail="Post not found")
    return post_out(db, post, user.id)


@app.delete("/api/posts/{post_id}")
def delete_post(post_id: int, db: Session = Depends(get_db),
                user: User = Depends(get_current_user)):
    post = db.query(Post).get(post_id)
    if not post:
        raise HTTPException(status_code=404, detail="Post not found")
    if post.user_id != user.id:
        raise HTTPException(status_code=403, detail="You can only delete your own posts")
    db.query(PostLike).filter(PostLike.post_id == post_id).delete()
    db.query(PostComment).filter(PostComment.post_id == post_id).delete()
    db.delete(post)
    db.commit()
    return {"ok": True}


@app.post("/api/posts/{post_id}/like")
def toggle_like(post_id: int, db: Session = Depends(get_db),
                user: User = Depends(get_current_user)):
    post = db.query(Post).get(post_id)
    if not post:
        raise HTTPException(status_code=404, detail="Post not found")
    existing = (
        db.query(PostLike)
        .filter(PostLike.post_id == post_id, PostLike.user_id == user.id)
        .first()
    )
    if existing:
        db.delete(existing)
        liked = False
    else:
        db.add(PostLike(post_id=post_id, user_id=user.id))
        liked = True
        if post.user_id != user.id:
            notify(db, post.user_id, user, "post_like", post_id,
                   f"{user.display_name} liked your photo")
    db.commit()
    count = db.query(PostLike).filter(PostLike.post_id == post_id).count()
    return {"liked": liked, "like_count": count}


@app.get("/api/posts/{post_id}/comments")
def list_comments(post_id: int, db: Session = Depends(get_db),
                  user: User = Depends(get_current_user)):
    post = db.query(Post).get(post_id)
    if not post:
        raise HTTPException(status_code=404, detail="Post not found")
    # v3.3: same hiding as the post itself.
    if post.user_id != user.id and blocked_either_way(db, user.id, post.user_id):
        raise HTTPException(status_code=404, detail="Post not found")
    comments = (
        db.query(PostComment)
        .filter(PostComment.post_id == post_id)
        .order_by(PostComment.created_at.asc(), PostComment.id.asc())
        .all()
    )
    out = []
    for c in comments:
        author = db.query(User).get(c.user_id)
        out.append({
            "id": c.id,
            "post_id": c.post_id,
            "user_id": c.user_id,
            "author": user_out(author, user.id, db) if author else None,
            "text": c.text,
            "created_at": iso(c.created_at),
        })
    return out


@app.post("/api/posts/{post_id}/comments")
def add_comment(post_id: int, body: CommentIn, db: Session = Depends(get_db),
                user: User = Depends(get_current_user)):
    post = db.query(Post).get(post_id)
    if not post:
        raise HTTPException(status_code=404, detail="Post not found")
    comment = PostComment(post_id=post_id, user_id=user.id, text=body.text.strip())
    db.add(comment)
    if post.user_id != user.id:
        notify(db, post.user_id, user, "post_comment", post_id,
               f"{user.display_name} commented: {body.text.strip()[:80]}")
    db.commit()
    db.refresh(comment)
    return {
        "id": comment.id,
        "post_id": comment.post_id,
        "user_id": comment.user_id,
        "author": user_out(user, user.id, db),
        "text": comment.text,
        "created_at": iso(comment.created_at),
    }


# ---------------------------------------------------------------------------
# Stories (photo only, expire after 24h)
# ---------------------------------------------------------------------------
@app.get("/api/stories")
def list_stories(db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    now = now_naive()
    # Only my own stories + stories from people I follow. Strangers'
    # stories never appear here (that was the v1.9 bug: everyone saw everyone).
    followed_ids = [r.following_id for r in
                    db.query(Follow).filter(Follow.follower_id == user.id).all()]
    allowed = set(followed_ids + [user.id])
    # v3.3: a block in either direction hides stories both ways.
    allowed -= blocked_either_way_ids(db, user.id)
    stories = (
        db.query(Story)
        .filter(Story.expires_at > now, Story.user_id.in_(allowed))
        .order_by(Story.created_at.desc())
        .all()
    )
    grouped = {}
    for s in stories:
        author = db.query(User).get(s.user_id)
        if s.user_id not in grouped:
            grouped[s.user_id] = {
                "user": user_out(author, user.id, db) if author else None,
                "stories": [],
            }
        grouped[s.user_id]["stories"].append(story_out(s, user.id, db))
    # Most recently active first; own stories first.
    result = sorted(
        grouped.values(),
        key=lambda g: (g["user"]["id"] == user.id,
                       max(s["id"] for s in g["stories"])),
        reverse=True,
    )
    return result


@app.post("/api/stories")
def create_story(body: StoryIn, db: Session = Depends(get_db),
                 user: User = Depends(get_current_user)):
    # Photo or video story: exactly one media URL must be given. The
    # 59-second cap on video stories is enforced client-side at pick
    # time (this image has no ffprobe to check duration server-side).
    image_url = body.image_url.strip()[:512]
    video_url = body.video_url.strip()[:512]
    if not image_url and not video_url:
        raise HTTPException(status_code=400, detail="Story photo or video is missing")
    story = Story(
        user_id=user.id,
        kind="video" if video_url else "image",
        text=body.text.strip()[:500],
        image_url=image_url,
        video_url=video_url,
        bg_color="#5B21B6",
    )
    db.add(story)
    db.commit()
    db.refresh(story)
    return story_out(story, user.id, db)


@app.post("/api/stories/{story_id}/view")
def view_story(story_id: int, db: Session = Depends(get_db),
               user: User = Depends(get_current_user)):
    story = db.query(Story).get(story_id)
    if not story or story.expires_at <= now_naive():
        raise HTTPException(status_code=404, detail="Story not found or expired")
    existing = (
        db.query(StoryView)
        .filter(StoryView.story_id == story_id, StoryView.viewer_id == user.id)
        .first()
    )
    if not existing:
        db.add(StoryView(story_id=story_id, viewer_id=user.id))
        db.commit()
    count = db.query(StoryView).filter(StoryView.story_id == story_id).count()
    return {"ok": True, "viewers": count}


@app.get("/api/stories/{story_id}/viewers")
def story_viewers(story_id: int, db: Session = Depends(get_db),
                  user: User = Depends(get_current_user)):
    story = db.query(Story).get(story_id)
    if not story:
        raise HTTPException(status_code=404, detail="Story not found")
    if story.user_id != user.id:
        raise HTTPException(status_code=403, detail="Only the story owner can see viewers")
    views = (
        db.query(StoryView).filter(StoryView.story_id == story_id)
        .order_by(StoryView.viewed_at.desc()).all()
    )
    out = []
    for v in views:
        u = db.query(User).get(v.viewer_id)
        if u:
            out.append({**user_out(u, user.id, db), "viewed_at": iso(v.viewed_at)})
    return out


@app.post("/api/stories/{story_id}/like")
def toggle_story_like(story_id: int, db: Session = Depends(get_db),
                      user: User = Depends(get_current_user)):
    story = db.query(Story).get(story_id)
    if not story:
        raise HTTPException(status_code=404, detail="Story not found")
    existing = (
        db.query(StoryLike)
        .filter(StoryLike.story_id == story_id, StoryLike.user_id == user.id)
        .first()
    )
    if existing:
        db.delete(existing)
        liked = False
    else:
        db.add(StoryLike(story_id=story_id, user_id=user.id))
        liked = True
        if story.user_id != user.id:
            notify(db, story.user_id, user, "story_like", story_id,
                   f"{user.display_name} liked your story")
    db.commit()
    count = db.query(StoryLike).filter(StoryLike.story_id == story_id).count()
    return {"liked": liked, "like_count": count}


@app.delete("/api/stories/{story_id}")
def delete_story(story_id: int, db: Session = Depends(get_db),
                 user: User = Depends(get_current_user)):
    story = db.query(Story).get(story_id)
    if not story:
        raise HTTPException(status_code=404, detail="Story not found")
    if story.user_id != user.id:
        raise HTTPException(status_code=403, detail="You can only delete your own stories")
    # Clean up everything pointing at it; highlights skip missing stories,
    # so a deleted story simply disappears from any highlight it was in.
    db.query(StoryLike).filter(StoryLike.story_id == story_id).delete()
    db.query(StoryView).filter(StoryView.story_id == story_id).delete()
    db.query(HighlightStory).filter(HighlightStory.story_id == story_id).delete()
    db.delete(story)
    db.commit()
    return {"ok": True}


# ---------------------------------------------------------------------------
# Highlights (saved stories — active or expired — grouped by name)
# ---------------------------------------------------------------------------
@app.post("/api/highlights")
def create_highlight(body: HighlightIn, db: Session = Depends(get_db),
                     user: User = Depends(get_current_user)):
    name = body.name.strip()
    if not name:
        raise HTTPException(status_code=400, detail="Highlight needs a name")
    story_ids = list(dict.fromkeys(body.story_ids))
    if not story_ids:
        raise HTTPException(status_code=400, detail="Pick at least one story")
    stories = db.query(Story).filter(Story.id.in_(story_ids)).all()
    if len(stories) != len(story_ids):
        raise HTTPException(status_code=404, detail="One of the stories does not exist")
    if any(s.user_id != user.id for s in stories):
        raise HTTPException(
            status_code=403, detail="Highlights can only use your own stories")
    highlight = Highlight(user_id=user.id, name=name[:64])
    db.add(highlight)
    db.commit()
    db.refresh(highlight)
    for pos, sid in enumerate(story_ids):
        db.add(HighlightStory(highlight_id=highlight.id, story_id=sid, position=pos))
    db.commit()
    return highlight_out(db, highlight, user.id, with_stories=True)


@app.get("/api/users/{user_id}/highlights")
def list_highlights(user_id: int, db: Session = Depends(get_db),
                    user: User = Depends(get_current_user)):
    other = db.query(User).get(user_id)
    if not other:
        raise HTTPException(status_code=404, detail="User not found")
    # v3.3: a block in either direction hides the highlights.
    if user_id != user.id and blocked_either_way(db, user.id, user_id):
        return []
    highlights = (
        db.query(Highlight).filter(Highlight.user_id == user_id)
        .order_by(Highlight.created_at.asc(), Highlight.id.asc()).all()
    )
    return [highlight_out(db, h, user.id) for h in highlights]


@app.get("/api/highlights/{highlight_id}")
def get_highlight(highlight_id: int, db: Session = Depends(get_db),
                  user: User = Depends(get_current_user)):
    h = db.query(Highlight).get(highlight_id)
    if not h:
        raise HTTPException(status_code=404, detail="Highlight not found")
    # v3.3: same hiding as the highlights list.
    if h.user_id != user.id and blocked_either_way(db, user.id, h.user_id):
        raise HTTPException(status_code=404, detail="Highlight not found")
    return highlight_out(db, h, user.id, with_stories=True)


@app.delete("/api/highlights/{highlight_id}")
def delete_highlight(highlight_id: int, db: Session = Depends(get_db),
                     user: User = Depends(get_current_user)):
    h = db.query(Highlight).get(highlight_id)
    if not h:
        raise HTTPException(status_code=404, detail="Highlight not found")
    if h.user_id != user.id:
        raise HTTPException(status_code=403, detail="You can only delete your own highlights")
    db.query(HighlightStory).filter(HighlightStory.highlight_id == highlight_id).delete()
    db.delete(h)
    db.commit()
    return {"ok": True}


# ---------------------------------------------------------------------------
# Notifications (in-app only — no push/Firebase in v2.0)
# ---------------------------------------------------------------------------
# v2.3: DM-type notifications never belong in the heart centre — DMs live
# only in the bottom paper-plane badge (GET /api/dm/unread, driven by
# read_at). Rows of these types, including legacy ones created before v2.3,
# are hidden from the list and the unread count below.
DM_NOTIFICATION_TYPES = ("dm", "story_reply")


@app.get("/api/notifications")
def list_notifications(limit: int = Query(50, ge=1, le=100),
                        db: Session = Depends(get_db),
                        user: User = Depends(get_current_user)):
    rows = (
        db.query(Notification)
        .filter(Notification.user_id == user.id,
                ~Notification.type.in_(DM_NOTIFICATION_TYPES))
        .order_by(Notification.created_at.desc(), Notification.id.desc())
        .limit(limit).all()
    )
    return [notification_out(db, n, user.id) for n in rows]


@app.get("/api/notifications/unread-count")
def notifications_unread_count(db: Session = Depends(get_db),
                               user: User = Depends(get_current_user)):
    total = (
        db.query(Notification)
        .filter(Notification.user_id == user.id,
                Notification.is_read.is_(False),
                ~Notification.type.in_(DM_NOTIFICATION_TYPES))
        .count()
    )
    return {"unread": total}


@app.post("/api/notifications/read")
def mark_all_notifications_read(db: Session = Depends(get_db),
                                user: User = Depends(get_current_user)):
    n = (
        db.query(Notification)
        .filter(Notification.user_id == user.id,
                Notification.is_read.is_(False))
        .update({Notification.is_read: True}, synchronize_session=False)
    )
    db.commit()
    return {"ok": True, "read": n}


@app.post("/api/notifications/{notif_id}/read")
def mark_one_notification_read(notif_id: int, db: Session = Depends(get_db),
                               user: User = Depends(get_current_user)):
    n = db.query(Notification).get(notif_id)
    if not n or n.user_id != user.id:
        raise HTTPException(status_code=404, detail="Notification not found")
    n.is_read = True
    db.commit()
    return {"ok": True}


# ---------------------------------------------------------------------------
# DMs (1:1 text messages)
# ---------------------------------------------------------------------------
@app.get("/api/dm/conversations")
def list_dm_conversations(db: Session = Depends(get_db),
                          user: User = Depends(get_current_user)):
    convs = (
        db.query(Conversation)
        .filter(or_(Conversation.user1_id == user.id,
                    Conversation.user2_id == user.id))
        .order_by(Conversation.updated_at.desc(), Conversation.id.desc())
        .all()
    )
    out = []
    for c in convs:
        other_id = c.user2_id if c.user1_id == user.id else c.user1_id
        other = db.query(User).get(other_id)
        if not other:
            continue
        last = (
            db.query(Message)
            .filter(Message.conversation_id == c.id)
            .order_by(Message.id.desc())
            .first()
        )
        unread = (
            db.query(Message)
            .filter(Message.conversation_id == c.id,
                    Message.sender_id == other_id,
                    Message.read_at.is_(None))
            .count()
        )
        out.append({
            "user": user_out(other, user.id, db),
            "last_message": last.body if last else "",
            "last_message_at": iso(last.created_at) if last else None,
            "unread_count": unread,
        })
    return out


@app.get("/api/dm/unread")
def dm_unread_count(db: Session = Depends(get_db),
                    user: User = Depends(get_current_user)):
    total = (
        db.query(Message)
        .join(Conversation, Message.conversation_id == Conversation.id)
        .filter(or_(Conversation.user1_id == user.id,
                    Conversation.user2_id == user.id),
                Message.sender_id != user.id,
                Message.read_at.is_(None))
        .count()
    )
    return {"unread": total}


@app.post("/api/dm/{user_id}")
def send_dm(user_id: int, body: DmIn, db: Session = Depends(get_db),
            user: User = Depends(get_current_user)):
    if user_id == user.id:
        raise HTTPException(status_code=400, detail="You cannot message yourself")
    other = db.query(User).get(user_id)
    if not other:
        raise HTTPException(status_code=404, detail="User not found")
    if blocked_either_way(db, user.id, user_id):
        raise HTTPException(
            status_code=403, detail="You can't message this user")
    text = (body.body or "").strip()
    media_url = (body.media_url or "").strip()[:512]
    media_kind = (body.media_kind or "text").strip().lower()
    if media_url:
        if media_kind not in ("image", "video"):
            raise HTTPException(status_code=400, detail="media_kind must be image or video")
    else:
        media_kind = "text"
    if not text and not media_url:
        raise HTTPException(status_code=400, detail="Message cannot be empty")
    if len(text) > 2000:
        raise HTTPException(
            status_code=400, detail="Message is too long (max 2000 characters)")
    conv = get_or_create_conversation(db, user.id, user_id)
    # v2.3: DMs (and story replies, which arrive as DMs) NEVER create a
    # heart notification — a DM shows only in the bottom paper-plane badge
    # (GET /api/dm/unread, driven by read_at, not by this table).
    msg = Message(conversation_id=conv.id, sender_id=user.id, body=text,
                  media_url=media_url, media_kind=media_kind)
    conv.updated_at = now_naive()
    db.add(msg)
    db.commit()
    db.refresh(msg)
    return message_out(msg)


@app.get("/api/dm/{user_id}")
def get_dm_thread(user_id: int, db: Session = Depends(get_db),
                  user: User = Depends(get_current_user)):
    other = db.query(User).get(user_id)
    if not other:
        raise HTTPException(status_code=404, detail="User not found")
    conv = find_conversation(db, user.id, user_id)
    if not conv:
        return []
    msgs = (
        db.query(Message)
        .filter(Message.conversation_id == conv.id)
        .order_by(Message.id.desc())
        .limit(100)
        .all()
    )
    msgs.reverse()
    return [message_out(m) for m in msgs]


@app.post("/api/dm/{user_id}/read")
def mark_dm_read(user_id: int, db: Session = Depends(get_db),
                 user: User = Depends(get_current_user)):
    other = db.query(User).get(user_id)
    if not other:
        raise HTTPException(status_code=404, detail="User not found")
    conv = find_conversation(db, user.id, user_id)
    if not conv:
        return {"ok": True, "read": 0}
    read = (
        db.query(Message)
        .filter(Message.conversation_id == conv.id,
                Message.sender_id == user_id,
                Message.read_at.is_(None))
        .update({Message.read_at: now_naive()}, synchronize_session=False)
    )
    db.commit()
    return {"ok": True, "read": read}


def message_out(m: Message) -> dict:
    return {
        "id": m.id,
        "conversation_id": m.conversation_id,
        "sender_id": m.sender_id,
        "body": m.body,
        "media_url": m.media_url or "",
        "media_kind": m.media_kind or "text",
        "created_at": iso(m.created_at),
        "read_at": iso(m.read_at),
    }


TYPING_TTL_SECONDS = 10


@app.post("/api/dm/{user_id}/typing")
def typing_heartbeat(user_id: int, db: Session = Depends(get_db),
                     user: User = Depends(get_current_user)):
    """Heartbeat: I am typing a message to user_id right now."""
    other = db.query(User).get(user_id)
    if not other:
        raise HTTPException(status_code=404, detail="User not found")
    row = (
        db.query(TypingState)
        .filter(TypingState.typer_id == user.id,
                TypingState.recipient_id == user_id)
        .first()
    )
    if row:
        row.updated_at = now_naive()
    else:
        db.add(TypingState(typer_id=user.id, recipient_id=user_id))
    db.commit()
    return {"typing": True}


@app.get("/api/dm/{user_id}/typing")
def typing_status(user_id: int, db: Session = Depends(get_db),
                  user: User = Depends(get_current_user)):
    """Is user_id typing a message to me right now? Rows older than
    10 seconds count as not typing (the sender's app heartbeats every 3s)."""
    other = db.query(User).get(user_id)
    if not other:
        raise HTTPException(status_code=404, detail="User not found")
    row = (
        db.query(TypingState)
        .filter(TypingState.typer_id == user_id,
                TypingState.recipient_id == user.id)
        .first()
    )
    typing = bool(
        row and (now_naive() - row.updated_at).total_seconds() <= TYPING_TTL_SECONDS
    )
    return {"typing": typing}


def find_conversation(db: Session, me_id: int, other_id: int):
    u1, u2 = (me_id, other_id) if me_id < other_id else (other_id, me_id)
    return (
        db.query(Conversation)
        .filter(Conversation.user1_id == u1, Conversation.user2_id == u2)
        .first()
    )


def get_or_create_conversation(db: Session, me_id: int, other_id: int) -> Conversation:
    conv = find_conversation(db, me_id, other_id)
    if not conv:
        u1, u2 = (me_id, other_id) if me_id < other_id else (other_id, me_id)
        conv = Conversation(user1_id=u1, user2_id=u2)
        db.add(conv)
        db.commit()
        db.refresh(conv)
    return conv


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)
