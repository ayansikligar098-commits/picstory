#!/usr/bin/env python3
"""PicStory backend E2E test — runs against http://127.0.0.1:8000."""
import io
import json
import sqlite3
import sys
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8000"
DB_PATH = "/home/hatch/workspace/picstory/backend/picstory.db"
PASS = 0
FAIL = 0


def check(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS {name}")
    else:
        FAIL += 1
        print(f"  FAIL {name} {extra}")


def call(method, path, token=None, body=None, raw=None, headers=None):
    url = BASE + path
    data = None
    hdrs = {"Content-Type": "application/json"}
    if body is not None:
        data = json.dumps(body).encode()
    if raw is not None:
        data = raw
        hdrs.pop("Content-Type", None)
    if headers:
        hdrs.update(headers)
    if token:
        hdrs["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except Exception:
            return e.code, {}


def upload_png(token):
    try:
        from PIL import Image
        buf = io.BytesIO()
        Image.new("RGB", (64, 64), (91, 33, 182)).save(buf, "PNG")
        png = buf.getvalue()
    except Exception:
        png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 100
    boundary = "----testboundary1234"
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
            f"filename=\"test.png\"\r\nContent-Type: image/png\r\n\r\n").encode() + png + \
           f"\r\n--{boundary}--\r\n".encode()
    return call("POST", "/api/upload", token=token, raw=body,
                headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})


def main():
    print("== auth ==")
    s, ayan = call("POST", "/api/auth/register",
                   body={"username": "ayan", "display_name": "Ayan Sikligar",
                         "password": "pass1234"})
    check("register ayan", s == 200 and "token" in ayan, f"{s} {ayan}")
    token_a = ayan.get("token")
    ayan_id = ayan["user"]["id"]
    s, priya = call("POST", "/api/auth/register",
                    body={"username": "priya", "display_name": "Priya Sharma",
                          "password": "pass1234"})
    check("register priya", s == 200 and "token" in priya, f"{s} {priya}")
    token_p = priya.get("token")
    priya_id = priya["user"]["id"]
    s, rahul = call("POST", "/api/auth/register",
                    body={"username": "rahul", "display_name": "Rahul Verma",
                          "password": "pass1234"})
    check("register rahul", s == 200 and "token" in rahul, f"{s} {rahul}")
    token_r = rahul.get("token")
    rahul_id = rahul["user"]["id"]
    s, dup = call("POST", "/api/auth/register",
                  body={"username": "ayan", "display_name": "Dup",
                        "password": "pass1234"})
    check("duplicate username rejected", s == 400, f"{s}")
    s, bad = call("POST", "/api/auth/login",
                  body={"username": "ayan", "password": "wrong"})
    check("wrong password rejected", s == 401, f"{s}")
    s, me = call("GET", "/api/auth/me", token=token_a)
    check("me returns ayan", s == 200 and me.get("username") == "ayan", f"{s} {me}")
    s, unauth = call("GET", "/api/auth/me")
    check("no token -> 401", s == 401, f"{s}")

    print("== users + search ==")
    s, users = call("GET", "/api/users?q=pri", token=token_a)
    check("search finds priya", s == 200 and any(u["username"] == "priya" for u in users), f"{s} {users}")
    s, users_all = call("GET", "/api/users", token=token_a)
    check("user list excludes self",
          s == 200 and all(u["username"] != "ayan" for u in users_all), f"{s} {users_all}")
    s, prof = call("GET", f"/api/users/{priya_id}", token=token_a)
    check("profile has counts",
          s == 200 and prof.get("post_count") == 0 and prof.get("followers_count") == 0,
          f"{s} {prof}")
    s, upd = call("PATCH", "/api/users/me", token=token_a,
                  body={"bio": "Photo lover 📸", "display_name": "Ayan S."})
    check("edit own bio", s == 200 and upd.get("bio") == "Photo lover 📸", f"{s} {upd}")

    print("== follow ==")
    s, fol = call("POST", f"/api/users/{priya_id}/follow", token=token_a)
    check("ayan follows priya", s == 200 and fol.get("following") is True
          and fol.get("followers_count") == 1, f"{s} {fol}")
    s, prof2 = call("GET", f"/api/users/{priya_id}", token=token_a)
    check("followers count 1 + followed_by_me",
          s == 200 and prof2.get("followers_count") == 1 and prof2.get("followed_by_me") is True,
          f"{s} {prof2}")
    s, flist = call("GET", f"/api/users/{priya_id}/followers", token=token_a)
    check("followers list shows ayan",
          s == 200 and any(u["username"] == "ayan" for u in flist), f"{s} {flist}")
    s, fing = call("GET", f"/api/users/{ayan_id}/following", token=token_a)
    check("following list shows priya",
          s == 200 and any(u["username"] == "priya" for u in fing), f"{s} {fing}")
    s, self_fol = call("POST", f"/api/users/{ayan_id}/follow", token=token_a)
    check("cannot follow self", s == 400, f"{s}")
    s, unf = call("POST", f"/api/users/{priya_id}/follow", token=token_a)
    check("unfollow toggles off", s == 200 and unf.get("following") is False
          and unf.get("followers_count") == 0, f"{s} {unf}")
    # follow again — priya's posts must appear in ayan's feed
    call("POST", f"/api/users/{priya_id}/follow", token=token_a)
    call("POST", f"/api/users/{rahul_id}/follow", token=token_a)

    print("== upload ==")
    s, up = upload_png(token_a)
    check("image upload", s == 200 and up.get("url", "").startswith("/media/"), f"{s} {up}")
    img_url = up.get("url", "")
    s, badup = call("POST", "/api/upload", token=token_a, raw=b"hello",
                    headers={"Content-Type": "text/plain"})
    check("non-image upload rejected", s in (400, 422), f"{s}")

    print("== photo posts + feed ==")
    s, post1 = call("POST", "/api/posts", token=token_p,
                    body={"caption": "Priya ki pehli photo!", "image_url": img_url})
    check("priya creates photo post", s == 200 and post1.get("id"), f"{s} {post1}")
    post1_id = post1["id"]
    s, post2 = call("POST", "/api/posts", token=token_a,
                    body={"caption": "Meri photo", "image_url": img_url})
    check("ayan creates photo post", s == 200 and post2.get("id"), f"{s} {post2}")
    post2_id = post2["id"]
    s, nopost = call("POST", "/api/posts", token=token_a, body={"caption": "no photo"})
    check("post without photo rejected", s == 400, f"{s}")
    s, feed = call("GET", "/api/posts", token=token_a)
    check("feed newest first (ayan, then priya)",
          s == 200 and len(feed) == 2 and feed[0]["id"] == post2_id
          and feed[1]["id"] == post1_id, f"{s} {feed}")
    s, feed_r = call("GET", "/api/posts", token=token_r)
    check("rahul feed is empty (follows nobody)",
          s == 200 and feed_r == [], f"{s} {feed_r}")
    s, uposts = call("GET", f"/api/users/{priya_id}/posts", token=token_a)
    check("priya profile posts", s == 200 and len(uposts) == 1
          and uposts[0]["id"] == post1_id, f"{s} {uposts}")
    s, prof3 = call("GET", f"/api/users/{priya_id}", token=token_a)
    check("priya post_count is 1", s == 200 and prof3.get("post_count") == 1, f"{s} {prof3}")

    print("== likes + comments ==")
    s, like = call("POST", f"/api/posts/{post1_id}/like", token=token_a)
    check("ayan likes priya post", s == 200 and like.get("liked") is True
          and like.get("like_count") == 1, f"{s} {like}")
    s, like2 = call("POST", f"/api/posts/{post1_id}/like", token=token_a)
    check("like toggle off", s == 200 and like2.get("liked") is False
          and like2.get("like_count") == 0, f"{s} {like2}")
    call("POST", f"/api/posts/{post1_id}/like", token=token_a)
    s, comment = call("POST", f"/api/posts/{post1_id}/comments", token=token_a,
                      body={"text": "Bahut badhiya photo!"})
    check("ayan comments", s == 200 and comment.get("text") == "Bahut badhiya photo!",
          f"{s} {comment}")
    s, comments = call("GET", f"/api/posts/{post1_id}/comments", token=token_p)
    check("comments list", s == 200 and len(comments) == 1, f"{s} {comments}")
    s, feed2 = call("GET", "/api/posts", token=token_p)
    check("feed shows like+comment counts",
          s == 200 and any(p["id"] == post1_id and p["like_count"] == 1
                           and p["comment_count"] == 1 for p in feed2), f"{s} {feed2}")

    print("== explore ==")
    s, ex = call("GET", "/api/explore", token=token_r)
    check("explore shows all posts",
          s == 200 and len(ex) == 2, f"{s} {ex}")

    print("== stories ==")
    s, story = call("POST", "/api/stories", token=token_p,
                    body={"image_url": img_url})
    check("priya photo story created", s == 200 and story.get("id"), f"{s} {story}")
    story_id = story["id"]
    s, story2 = call("POST", "/api/stories", token=token_a,
                     body={"image_url": img_url})
    check("ayan photo story created", s == 200 and story2.get("id"), f"{s} {story2}")
    story2_id = story2["id"]
    s, stories = call("GET", "/api/stories", token=token_r)
    check("stories list shows both authors",
          s == 200 and any(g["user"]["username"] == "priya" for g in stories)
          and any(g["user"]["username"] == "ayan" for g in stories), f"{s} {stories}")
    s, view = call("POST", f"/api/stories/{story_id}/view", token=token_r)
    check("rahul views priya story", s == 200 and view.get("viewers") == 1, f"{s} {view}")
    s, viewers = call("GET", f"/api/stories/{story_id}/viewers", token=token_p)
    check("owner sees viewers", s == 200 and len(viewers) == 1
          and viewers[0]["username"] == "rahul", f"{s} {viewers}")
    s, viewers_no = call("GET", f"/api/stories/{story_id}/viewers", token=token_r)
    check("non-owner cannot see viewers", s == 403, f"{s}")

    print("== story expiry (direct DB check) ==")
    con = sqlite3.connect(DB_PATH)
    con.execute("UPDATE stories SET expires_at = datetime('now', '-1 hour') WHERE id = ?",
                (story2_id,))
    con.commit()
    con.close()
    s, stories2 = call("GET", "/api/stories", token=token_r)
    check("expired story hidden from list",
          s == 200 and not any(g["user"]["username"] == "ayan" for g in stories2),
          f"{s} {stories2}")
    s, view_exp = call("POST", f"/api/stories/{story2_id}/view", token=token_r)
    check("expired story cannot be viewed", s == 404, f"{s} {view_exp}")

    print("== highlights ==")
    s, hl = call("POST", "/api/highlights", token=token_a,
                 body={"name": "Meri Yaadein", "story_ids": [story2_id]})
    check("highlight from EXPIRED own story created",
          s == 200 and hl.get("id") and hl.get("story_count") == 1, f"{s} {hl}")
    hl_id = hl["id"]
    s, hl2 = call("POST", "/api/highlights", token=token_p,
                  body={"name": "Priya ke din", "story_ids": [story_id]})
    check("priya highlight created", s == 200 and hl2.get("id"), f"{s} {hl2}")
    s, hlist = call("GET", f"/api/users/{ayan_id}/highlights", token=token_r)
    check("highlights list on profile",
          s == 200 and len(hlist) == 1 and hlist[0]["name"] == "Meri Yaadein"
          and hlist[0]["cover_url"] == img_url, f"{s} {hlist}")
    s, hdetail = call("GET", f"/api/highlights/{hl_id}", token=token_r)
    check("highlight view shows the expired story",
          s == 200 and len(hdetail.get("stories", [])) == 1
          and hdetail["stories"][0]["id"] == story2_id, f"{s} {hdetail}")
    s, hl_bad = call("POST", "/api/highlights", token=token_a,
                     body={"name": "Chori", "story_ids": [story_id]})
    check("cannot highlight someone else's story", s == 403, f"{s} {hl_bad}")
    s, hl_empty = call("POST", "/api/highlights", token=token_a,
                       body={"name": "Khaali", "story_ids": []})
    check("highlight needs at least one story", s == 400, f"{s} {hl_empty}")

    print("== delete own post ==")
    s, dele = call("DELETE", f"/api/posts/{post2_id}", token=token_p)
    check("cannot delete someone else's post", s == 403, f"{s} {dele}")
    s, dele2 = call("DELETE", f"/api/posts/{post2_id}", token=token_a)
    check("owner deletes own post", s == 200, f"{s} {dele2}")
    s, gone = call("GET", f"/api/posts/{post2_id}", token=token_a)
    check("deleted post is gone", s == 404, f"{s} {gone}")
    s, feed3 = call("GET", "/api/posts", token=token_a)
    check("feed has 1 post left", s == 200 and len(feed3) == 1, f"{s} {feed3}")

    print(f"\nRESULT: {PASS} passed, {FAIL} failed")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
