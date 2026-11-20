"""PicStory backend — database engine/session."""
import os
from pathlib import Path

from dotenv import load_dotenv
from sqlalchemy import create_engine
from sqlalchemy.orm import declarative_base, sessionmaker

load_dotenv(Path(__file__).resolve().parent.parent / ".env")

DATABASE_URL = os.environ.get("DATABASE_URL") or (
    "sqlite:///" + str(Path(__file__).resolve().parent.parent / "picstory.db")
)

# Supabase hands out plain postgresql:// (or postgres://) URLs, but
# SQLAlchemy 2.0 maps those schemes to the psycopg2 driver, which is NOT
# installed — this app ships psycopg (v3). Rewrite the scheme so a pasted
# Supabase URL works as-is; the sqlite default above is untouched.
if DATABASE_URL.startswith(("postgresql://", "postgres://")):
    DATABASE_URL = "postgresql+psycopg://" + DATABASE_URL.split("://", 1)[1]

if DATABASE_URL.startswith("sqlite"):
    connect_args = {"check_same_thread": False}
elif DATABASE_URL.startswith("postgresql+psycopg://"):
    # v3.4.1: the Supabase pooler is PgBouncer in transaction mode, which
    # reuses server connections across sessions — so psycopg's named
    # prepared statements collide ("DuplicatePreparedStatement: prepared
    # statement _pg3_0 already exists") and the app crashes at startup
    # during its table-existence checks. That is exactly what killed the
    # v3.4 Render deploy (build passed, runtime exit 1). Disabling
    # prepared statements entirely (prepare_threshold=None) is the
    # documented fix for psycopg + PgBouncer transaction pooling.
    connect_args = {"prepare_threshold": None}
else:
    connect_args = {}
engine = create_engine(DATABASE_URL, connect_args=connect_args)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
