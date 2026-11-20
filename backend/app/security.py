"""PicStory backend — JWT + bcrypt password hashing."""
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path

from dotenv import load_dotenv
from jose import jwt
from passlib.context import CryptContext

load_dotenv(Path(__file__).resolve().parent.parent / ".env")

SECRET = os.environ.get("JWT_SECRET", "picstory-dev-secret-change-me")
ALGORITHM = os.environ.get("JWT_ALGORITHM", "HS256")
EXPIRE_DAYS = int(os.environ.get("JWT_EXPIRE_DAYS", "30"))

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")


def hash_password(password: str) -> str:
    return pwd_context.hash(password)


def verify_password(plain: str, hashed: str) -> bool:
    return pwd_context.verify(plain, hashed)


def create_token(user_id: int) -> str:
    exp = datetime.now(timezone.utc) + timedelta(days=EXPIRE_DAYS)
    return jwt.encode({"sub": str(user_id), "exp": exp}, SECRET, algorithm=ALGORITHM)


def decode_token(token: str):
    try:
        payload = jwt.decode(token, SECRET, algorithms=[ALGORITHM])
        return int(payload.get("sub"))
    except Exception:
        return None
