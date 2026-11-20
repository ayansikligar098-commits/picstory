"""PicStory backend — FastAPI app: auth, follow, photo posts, stories, highlights."""
import re
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
    Follow,
    Highlight,
    HighlightStory,
    Post,
    PostComment,
    PostLike,
    Story,
    StoryView,
    User,
)
from .security import create_token, decode_token, hash_password, verify_password

BASE_DIR = Path(__file__).resolve().parent.parent
STORAGE_DIR = BASE_DIR / "storage"
STORAGE_DIR.mkdir(exist_ok=True)
MAX_UPLOAD_MB = 10

Base.metadata.create_all(bind=engine)


def _migrate_user_columns():
    """Add users.avatar_url / users.music_url on databases created before
    those columns existed.

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
            else:
                for col in ("avatar_url", "music_url"):
                    conn.execute(
                        text(f"ALTER TABLE users ADD COLUMN IF NOT EXISTS {col} VARCHAR(512) DEFAULT ''")
                    )
    except Exception:
        # Never crash startup over a migration; the columns exist in every
        # database built by the current code, so this is belt-and-braces.
        pass


_migrate_user_columns()

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
    return user


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
        "created_at": iso(u.created_at),
    }


def story_out(s: Story, me_id: int, db: Session) -> dict:
    viewed = (
        db.query(StoryView)
        .filter(StoryView.story_id == s.id, StoryView.viewer_id == me_id)
        .first()
        is not None
    )
    return {
        "id": s.id,
        "user_id": s.user_id,
        "kind": s.kind,
        "text": s.text or "",
        "image_url": s.image_url or "",
        "bg_color": s.bg_color or "#5B21B6",
        "viewed_by_me": viewed,
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
    text: str = ""


class PostIn(BaseModel):
    caption: str = ""
    image_url: str = ""


class CommentIn(BaseModel):
    text: str = Field(..., min_length=1, max_length=500)


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
    db.commit()
    followers = db.query(Follow).filter(Follow.following_id == user_id).count()
    return {"following": following, "followers_count": followers}


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
# Uploads
# ---------------------------------------------------------------------------
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
    else:
        if not ctype.startswith("image/"):
            raise HTTPException(status_code=400, detail="Only images can be uploaded")
        if ext not in ("jpg", "jpeg", "png", "webp", "gif"):
            ext = "jpg"
    data = await file.read()
    if len(data) > MAX_UPLOAD_MB * 1024 * 1024:
        raise HTTPException(status_code=400, detail="File is too big (max 10 MB)")
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
    posts = (
        db.query(Post).order_by(Post.created_at.desc(), Post.id.desc())
        .offset(offset).limit(limit).all()
    )
    return [post_out(db, p, user.id) for p in posts]


@app.get("/api/users/{user_id}/posts")
def list_user_posts(user_id: int, db: Session = Depends(get_db),
                    user: User = Depends(get_current_user)):
    other = db.query(User).get(user_id)
    if not other:
        raise HTTPException(status_code=404, detail="User not found")
    posts = (
        db.query(Post).filter(Post.user_id == user_id)
        .order_by(Post.created_at.desc(), Post.id.desc()).all()
    )
    return [post_out(db, p, user.id) for p in posts]


@app.post("/api/posts")
def create_post(body: PostIn, db: Session = Depends(get_db),
                user: User = Depends(get_current_user)):
    if not body.image_url.strip():
        raise HTTPException(status_code=400, detail="Photo posts need a photo")
    post = Post(
        user_id=user.id,
        caption=body.caption.strip()[:1000],
        image_url=body.image_url.strip()[:512],
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
    db.commit()
    count = db.query(PostLike).filter(PostLike.post_id == post_id).count()
    return {"liked": liked, "like_count": count}


@app.get("/api/posts/{post_id}/comments")
def list_comments(post_id: int, db: Session = Depends(get_db),
                  user: User = Depends(get_current_user)):
    post = db.query(Post).get(post_id)
    if not post:
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
    stories = (
        db.query(Story)
        .filter(Story.expires_at > now)
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
    if not body.image_url.strip():
        raise HTTPException(status_code=400, detail="Story photo is missing")
    story = Story(
        user_id=user.id,
        kind="image",
        text=body.text.strip()[:500],
        image_url=body.image_url.strip()[:512],
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


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)
