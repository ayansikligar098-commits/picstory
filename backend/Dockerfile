# PicStory backend — FastAPI (photo posts, stories, highlights only).
# Free-test image. No secrets are baked in: JWT_SECRET / DATABASE_URL
# must come from the host environment (see ../render.yaml).
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# Uploaded photos land here. On Render Free there is NO persistent disk —
# uploads and the SQLite DB are LOST on every restart / redeploy.
RUN mkdir -p storage

EXPOSE 8000
# Render sets $PORT; default to 8000 locally.
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
