#!/usr/bin/env bash
# Run the PicStory backend on this VM.
set -euo pipefail
cd "$(dirname "$0")"
export NO_PROXY="127.0.0.1,localhost"
export no_proxy="127.0.0.1,localhost"
exec .venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
