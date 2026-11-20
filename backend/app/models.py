"""PicStory backend — SQLAlchemy models."""
from datetime import datetime, timedelta, timezone

from sqlalchemy import (
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


class Story(Base):
    """Photo story — expires 24h after posting (server-enforced)."""
    __tablename__ = "stories"
    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    kind = Column(String(16), default="image", nullable=False)  # image only in v1
    text = Column(Text, default="")
    image_url = Column(String(512), default="")
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


class Post(Base):
    __tablename__ = "posts"
    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    caption = Column(Text, default="")
    image_url = Column(String(512), default="")
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
