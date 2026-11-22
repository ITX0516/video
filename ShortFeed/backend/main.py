"""
Reference backend for ShortFeed's Douyin / Kuaishou sources.

Exposes the contract the Android app expects:

    GET /feed?platform={douyin|kuaishou}&cursor={cursor}
    -> {
         "items": [ {"id","title","author","cover","play_url"} ],
         "next_cursor": "opaque" | null
       }

For Douyin it wraps a self-hosted Douyin_TikTok_Download_API ("dtk") instance
(https://github.com/Evil0ctal/Douyin_TikTok_Download_API). That project already
implements a_bogus / X-Bogus / msToken signing natively, runs an identity pool with
circuit breakers, and can fall back to running the site's real JS in a browser
(signing.mode = rpc) the day a signature goes stale — so algorithm updates are fixed
here, never in the APK.

Run:
    pip install -r requirements.txt
    DTK_BASE_URL=http://127.0.0.1:8000 \
    DOUYIN_AUTHORS='<sec_uid_1>,<sec_uid_2>' \
    uvicorn main:app --host 0.0.0.0 --port 8000

Then point the Android app's "后端地址" at this service (e.g. http://192.168.1.10:8000).
"""

import os
from typing import Optional

import httpx
from fastapi import FastAPI

DTK_BASE_URL = os.environ.get("DTK_BASE_URL", "http://127.0.0.1:8000").rstrip("/")
# Comma-separated Douyin sec_uids of creators you follow. Find a sec_uid in the
# author's profile URL or via dtk's author lookup endpoints.
DOUYIN_AUTHORS = [a for a in os.environ.get("DOUYIN_AUTHORS", "").split(",") if a]

app = FastAPI()
_http = httpx.Client(timeout=30.0)

# Per-process cursor state. For a single user this is fine; scale out if needed.
_state: dict[str, dict] = {"douyin": {"author_idx": 0, "offset": 0}}


def _first(d: dict, *paths, default=""):
    """Best-effort field lookup across common dtk response shapes."""
    for p in paths:
        cur = d
        ok = True
        for part in p.split("."):
            if isinstance(cur, dict) and part in cur:
                cur = cur[part]
            elif isinstance(cur, list) and part.isdigit() and int(part) < len(cur):
                cur = cur[int(part)]
            else:
                ok = False
                break
        if ok and cur not in (None, "", [], {}):
            return cur
    return default


def _normalize_douyin(post: dict) -> Optional[dict]:
    play_url = _first(
        post,
        "video.play_addr.url_list.0",
        "video.play_addr_h264.url_list.0",
        "video.bit_rate.0.play_addr.url_list.0",
        "play_url",
    )
    if not play_url:
        return None
    return {
        "id": str(_first(post, "aweme_id", "id", default="")),
        "title": str(_first(post, "desc", "title", default="")),
        "author": str(_first(post, "author.nickname", "nickname", default="")),
        "cover": str(_first(post, "video.cover.url_list.0", "cover", default="")),
        "play_url": str(play_url),
    }


@app.get("/feed")
def feed(platform: str, cursor: Optional[str] = None):
    if platform == "douyin":
        return _douyin_feed(cursor)
    if platform == "kuaishou":
        return _kuaishou_feed(cursor)
    return {"items": [], "next_cursor": None}


def _douyin_feed(cursor: Optional[str]):
    """Round-robin your followed creators; paginate each via dtk's author_posts endpoint."""
    if not DOUYIN_AUTHORS:
        return {"items": [], "next_cursor": None}

    st = _state["douyin"]
    if cursor and cursor.startswith("d:"):
        try:
            _, idx_s, off_s = cursor.split(":")
            st["author_idx"] = int(idx_s)
            st["offset"] = int(off_s)
        except ValueError:
            pass

    author = DOUYIN_AUTHORS[st["author_idx"] % len(DOUYIN_AUTHORS)]
    params = {"sec_user_id": author, "count": 20, "cursor": st["offset"]}
    # dtk REST shape: /api/v1/douyin/author/posts  (verify against your dtk version's docs)
    try:
        r = _http.get(f"{DTK_BASE_URL}/api/v1/douyin/author/posts", params=params)
        r.raise_for_status()
        data = r.json()
    except Exception:
        return {"items": [], "next_cursor": None}

    raw = data.get("data", {}) if isinstance(data, dict) else {}
    posts = raw.get("posts") or raw.get("aweme_list") or raw.get("list") or []
    items = []
    for p in posts:
        norm = _normalize_douyin(p)
        if norm:
            items.append(norm)

    # Advance: next offset; roll to the next author when a page comes back short/exhausted.
    has_more = bool(raw.get("has_more", len(items) == 20))
    next_offset = raw.get("cursor", st["offset"] + len(items))
    if has_more:
        nxt = f"d:{st['author_idx']}:{next_offset}"
    else:
        st["author_idx"] = (st["author_idx"] + 1) % len(DOUYIN_AUTHORS)
        st["offset"] = 0
        nxt = f"d:{st['author_idx']}:0"

    return {"items": items, "next_cursor": nxt}


def _kuaishou_feed(cursor: Optional[str]):
    """
    Kuaishou extension point. No maintained, feed-capable engine comparable to dtk exists,
    so pick one of these and wire it in:

      1. Self-hosted parser (e.g. the __NS_sig3 reimplementations / resolve-kwai-cdn-url)
         and paginate a creator's GraphQL works endpoint, mapping to the same item shape.
      2. An MCP-style link-parse server for share links you curate (lower volume).

    Return the same {"items":[...],"next_cursor":...} envelope and the app Just Works.
    """
    return {"items": [], "next_cursor": None}
