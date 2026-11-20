"""PicStory backend — SQLAlchemy models."""
from datetime import datetime, timedelta, timezone

from sqlalchemy import (
    Boolean,
    Column,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
)

from .db import Base


def utcnow():
    # Naive UTC: SQLite returns naive datetimes, so all timestamps stay naive.
    return datetime.now(timezone.utc).replace(tzinfo=None)


class User(Base):
    __tablename__ = "users"
    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(32), unique=True, index=True, nullable=False)
    display_name = Column(String(64), nullable=False)
    password_hash = Column(String(128), nullable=False)
    bio = Column(String(200), default="")
    avatar_url = Column(String(512), default="")
    music_url = Column(String(512), default="")
    # Last authenticated request — drives the online/offline presence dot.
    last_seen_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=utcnow, nullable=False)


class Follow(Base):
    """follower_id follows following_id."""
    __tablename__ = "follows"
    id = Column(Integer, primary_key=True, index=True)
    follower_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    following_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    created_at = Column(DateTime, default=utcnow, nullable=False)
    __table_args__ = (
        UniqueConstraint("follower_id", "following_id", name="uq_follow"),
    )


class Block(Base):
    """blocker_id blocked blocked_id. Blocking also removes follows in
    both directions (done by the block endpoint, not by the model)."""
    __tablename__ = "blocks"
    id = Column(Integer, primary_key=True, index=True)
    blocker_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    blocked_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    created_at = Column(DateTime, default=utcnow, nullable=False)
    __table_args__ = (
        UniqueConstraint("blocker_id", "blocked_id", name="uq_block"),
    )


class Story(Base):
    """Photo or video story — expires 24h after posting (server-enforced).
    Video stories are capped at 59s (enforced client-side at pick time;
    the Docker image has no ffprobe, so the server cannot check duration).
    """
    __tablename__ = "stories"
    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    kind = Column(String(16), default="image", nullable=False)  # image | video
    text = Column(Text, default="")
    image_url = Column(String(512), default="")
    # Video story file (Cloudinary/local URL). Empty for photo stories.
    video_url = Column(String(512), default="")
    bg_color = Column(String(16), default="#5B21B6")
    created_at = Column(DateTime, default=utcnow, nullable=False)
    expires_at = Column(
        DateTime, default=lambda: utcnow() + timedelta(hours=24), nullable=False
    )


class StoryView(Base):
    __tablename__ = "story_views"
    id = Column(Integer, primary_key=True, index=True)
    story_id = Column(Integer, ForeignKey("stories.id"), nullable=False, index=True)
    viewer_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    viewed_at = Column(DateTime, default=utcnow, nullable=False)
    __table_args__ = (UniqueConstraint("story_id", "viewer_id", name="uq_story_view"),)


class StoryLike(Base):
    """One user liking one story (toggle)."""
    __tablename__ = "story_likes"
    id = Column(Integer, primary_key=True, index=True)
    story_id = Column(Integer, ForeignKey("stories.id"), nullable=False, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    created_at = Column(DateTime, default=utcnow, nullable=False)
    __table_args__ = (UniqueConstraint("story_id", "user_id", name="uq_story_like"),)


class Post(Base):
    __tablename__ = "posts"
    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    caption = Column(Text, default="")
    image_url = Column(String(512), default="")
    # Reel (video post). Empty for photo posts.
    video_url = Column(String(512), default="")
    created_at = Column(DateTime, default=utcnow, nullable=False)


class PostLike(Base):
    __tablename__ = "post_likes"
    id = Column(Integer, primary_key=True, index=True)
    post_id = Column(Integer, ForeignKey("posts.id"), nullable=False, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    created_at = Column(DateTime, default=utcnow, nullable=False)
    __table_args__ = (UniqueConstraint("post_id", "user_id", name="uq_post_like"),)


class PostComment(Base):
    __tablename__ = "post_comments"
    id = Column(Integer, primary_key=True, index=True)
    post_id = Column(Integer, ForeignKey("posts.id"), nullable=False, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    text = Column(Text, nullable=False)
    created_at = Column(DateTime, default=utcnow, nullable=False)


class Conversation(Base):
    """1:1 DM thread. user1_id is always the smaller id, so each pair has
    exactly one row (enforced by the unique pair constraint)."""
    __tablename__ = "conversations"
    id = Column(Integer, primary_key=True, index=True)
    user1_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    user2_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    created_at = Column(DateTime, default=utcnow, nullable=False)
    updated_at = Column(DateTime, default=utcnow, nullable=False)
    __table_args__ = (
        UniqueConstraint("user1_id", "user2_id", name="uq_conversation_pair"),
    )


class Message(Base):
    """One DM text. read_at is null until the recipient marks it read.
    media_url/media_kind carry photo/video messages; plain text has kind 'text'."""
    __tablename__ = "dm_messages"
    id = Column(Integer, primary_key=True, index=True)
    conversation_id = Column(Integer, ForeignKey("conversations.id"), nullable=False, index=True)
    sender_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    body = Column(Text, nullable=False, default="")
    media_url = Column(String(512), default="")
    media_kind = Column(String(16), default="text")  # text | image | video
    created_at = Column(DateTime, default=utcnow, nullable=False)
    read_at = Column(DateTime, nullable=True)


class Notification(Base):
    """In-app notification for one user. type: follow | post_like |
    post_comment | story_like. ref_id points at the post/story when there
    is one, else the actor's user id. v2.3: 'dm' and 'story_reply' rows are
    no longer created (DMs show only in the DM badge) and are excluded from
    the heart-centre list/unread-count (see DM_NOTIFICATION_TYPES in
    main.py); legacy rows of those types may still sit in the table."""
    __tablename__ = "notifications"
    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    actor_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    type = Column(String(32), nullable=False)
    ref_id = Column(Integer, nullable=True)
    body = Column(Text, default="")
    is_read = Column(Boolean, default=False, nullable=False)
    created_at = Column(DateTime, default=utcnow, nullable=False)


class TypingState(Base):
    """One row per (typer, recipient) while the typer is typing in that DM.
    A heartbeat refreshes updated_at; rows older than 10s count as not typing."""
    __tablename__ = "typing_states"
    id = Column(Integer, primary_key=True, index=True)
    typer_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    recipient_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    updated_at = Column(DateTime, default=utcnow, nullable=False)
    __table_args__ = (
        UniqueConstraint("typer_id", "recipient_id", name="uq_typing_pair"),
    )


class Highlight(Base):
    """A named collection of the owner's stories (active or expired)."""
    __tablename__ = "highlights"
    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    name = Column(String(64), nullable=False)
    created_at = Column(DateTime, default=utcnow, nullable=False)


class HighlightStory(Base):
    __tablename__ = "highlight_stories"
    id = Column(Integer, primary_key=True, index=True)
    highlight_id = Column(Integer, ForeignKey("highlights.id"), nullable=False, index=True)
    story_id = Column(Integer, ForeignKey("stories.id"), nullable=False, index=True)
    position = Column(Integer, default=0, nullable=False)
    __table_args__ = (
        UniqueConstraint("highlight_id", "story_id", name="uq_highlight_story"),
    )
