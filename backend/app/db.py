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

connect_args = {"check_same_thread": False} if DATABASE_URL.startswith("sqlite") else {}
engine = create_engine(DATABASE_URL, connect_args=connect_args)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
