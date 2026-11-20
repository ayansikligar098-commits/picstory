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

    print("== profile photo (DP) ==")
    s, av_up = upload_png(token_a)
    check("avatar image upload", s == 200 and av_up.get("url", "").startswith("/media/"),
          f"{s} {av_up}")
    av_url = av_up.get("url", "")
    s, set_av = call("PATCH", "/api/users/me", token=token_a,
                     body={"avatar_url": av_url})
    check("set own avatar", s == 200 and set_av.get("avatar_url") == av_url, f"{s} {set_av}")
    s, prof_av = call("GET", f"/api/users/{ayan_id}", token=token_p)
    check("profile shows avatar", s == 200 and prof_av.get("avatar_url") == av_url,
          f"{s} {prof_av}")
    s, search_av = call("GET", "/api/users?q=aya", token=token_p)
    check("search shows avatar",
          s == 200 and any(u["username"] == "ayan" and u.get("avatar_url") == av_url
                           for u in search_av), f"{s} {search_av}")
    s, feed_av = call("GET", "/api/posts", token=token_a)
    check("feed post author shows avatar",
          s == 200 and any(p["id"] == post2_id and p["author"]
                           and p["author"].get("avatar_url") == av_url for p in feed_av),
          f"{s} {feed_av}")
    s, comm_av = call("GET", f"/api/posts/{post1_id}/comments", token=token_p)
    check("comment author shows avatar",
          s == 200 and any(c["author"] and c["author"].get("avatar_url") == av_url
                           for c in comm_av), f"{s} {comm_av}")
    s, badav = call("POST", "/api/upload", token=token_a, raw=b"not an image",
                    headers={"Content-Type": "text/plain"})
    check("non-image avatar upload rejected", s in (400, 422), f"{s}")
    s, prof_p = call("GET", f"/api/users/{priya_id}", token=token_a)
    check("other account avatar untouched",
          s == 200 and prof_p.get("avatar_url") == "", f"{s} {prof_p}")
    s, noauth_av = call("PATCH", "/api/users/me", body={"avatar_url": av_url})
    check("set avatar without token -> 401", s == 401, f"{s}")
    s, rm_av = call("PATCH", "/api/users/me", token=token_a, body={"avatar_url": ""})
    check("remove avatar", s == 200 and rm_av.get("avatar_url") == "", f"{s} {rm_av}")
    s, prof_rm = call("GET", f"/api/users/{ayan_id}", token=token_p)
    check("profile shows avatar removed",
          s == 200 and prof_rm.get("avatar_url") == "", f"{s} {prof_rm}")
    # set it again so later checks (and the app) see a DP on ayan
    call("PATCH", "/api/users/me", token=token_a, body={"avatar_url": av_url})

    print("== profile music ==")
    import wave
    wbuf = io.BytesIO()
    with wave.open(wbuf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(8000)
        w.writeframes(b"\x00\x00" * 800)
    wav_bytes = wbuf.getvalue()

    def upload_audio(token, data, filename, ctype):
        boundary = "----testboundaryaudio"
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                f"filename=\"{filename}\"\r\nContent-Type: {ctype}\r\n\r\n").encode() + data + \
               f"\r\n--{boundary}--\r\n".encode()
        return call("POST", "/api/upload?kind=audio", token=token, raw=body,
                    headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})

    s, mu = upload_audio(token_a, wav_bytes, "test.wav", "audio/wav")
    check("audio upload", s == 200 and mu.get("url", "").endswith(".wav"), f"{s} {mu}")
    mu_url = mu.get("url", "")
    s, set_mu = call("PATCH", "/api/users/me", token=token_a, body={"music_url": mu_url})
    check("set profile music", s == 200 and set_mu.get("music_url") == mu_url, f"{s} {set_mu}")
    s, prof_mu = call("GET", f"/api/users/{ayan_id}", token=token_p)
    check("profile shows music", s == 200 and prof_mu.get("music_url") == mu_url,
          f"{s} {prof_mu}")
    s, search_mu = call("GET", "/api/users?q=aya", token=token_p)
    check("search shows music",
          s == 200 and any(u["username"] == "ayan" and u.get("music_url") == mu_url
                           for u in search_mu), f"{s} {search_mu}")
    png_bytes = b"\x89PNG\r\n\x1a\n" + b"\x00" * 100
    s, bad_mu = upload_audio(token_a, png_bytes, "test.png", "image/png")
    check("image rejected as music", s == 400, f"{s}")
    s, bad_mu2 = call("POST", "/api/upload?kind=audio", token=token_a, raw=b"not audio",
                      headers={"Content-Type": "text/plain"})
    check("text rejected as music", s in (400, 422), f"{s}")

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
    check("story author shows avatar",
          s == 200 and any(g["user"]["username"] == "ayan"
                           and g["user"].get("avatar_url") == av_url for g in stories),
          f"{s} {stories}")
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

    print("== DMs ==")
    s, unread0 = call("GET", "/api/dm/unread", token=token_p)
    check("unread starts at 0", s == 200 and unread0.get("unread") == 0,
          f"{s} {unread0}")
    s, dm1 = call("POST", f"/api/dm/{priya_id}", token=token_a,
                  body={"body": "Hi Priya! 👋"})
    check("ayan sends DM to priya",
          s == 200 and dm1.get("body") == "Hi Priya! 👋"
          and dm1.get("sender_id") == ayan_id, f"{s} {dm1}")
    s, unread1 = call("GET", "/api/dm/unread", token=token_p)
    check("priya unread is 1", s == 200 and unread1.get("unread") == 1,
          f"{s} {unread1}")
    s, convs = call("GET", "/api/dm/conversations", token=token_p)
    check("priya conversations show ayan, unread 1",
          s == 200 and len(convs) == 1 and convs[0]["user"]["username"] == "ayan"
          and convs[0]["unread_count"] == 1
          and convs[0]["last_message"] == "Hi Priya! 👋", f"{s} {convs}")
    s, thread = call("GET", f"/api/dm/{ayan_id}", token=token_p)
    check("priya reads thread (1 message)",
          s == 200 and len(thread) == 1 and thread[0]["body"] == "Hi Priya! 👋",
          f"{s} {thread}")
    s, unread_still = call("GET", "/api/dm/unread", token=token_p)
    check("unread still 1 until marked read",
          s == 200 and unread_still.get("unread") == 1, f"{s} {unread_still}")
    s, rd = call("POST", f"/api/dm/{ayan_id}/read", token=token_p)
    check("mark thread read", s == 200 and rd.get("read") == 1, f"{s} {rd}")
    s, unread2 = call("GET", "/api/dm/unread", token=token_p)
    check("unread is 0 after read", s == 200 and unread2.get("unread") == 0,
          f"{s} {unread2}")
    s, dm2 = call("POST", f"/api/dm/{ayan_id}", token=token_p,
                  body={"body": "Hi Ayan, wapas!"})
    check("priya replies", s == 200 and dm2.get("body") == "Hi Ayan, wapas!",
          f"{s} {dm2}")
    s, thread2 = call("GET", f"/api/dm/{priya_id}", token=token_a)
    check("thread has both messages, oldest first",
          s == 200 and len(thread2) == 2 and thread2[0]["body"] == "Hi Priya! 👋"
          and thread2[1]["body"] == "Hi Ayan, wapas!", f"{s} {thread2}")
    s, convs_a = call("GET", "/api/dm/conversations", token=token_a)
    check("ayan conversations show priya with unread 1",
          s == 200 and len(convs_a) == 1
          and convs_a[0]["user"]["username"] == "priya"
          and convs_a[0]["unread_count"] == 1
          and convs_a[0]["last_message"] == "Hi Ayan, wapas!", f"{s} {convs_a}")
    s, self_dm = call("POST", f"/api/dm/{ayan_id}", token=token_a,
                      body={"body": "hello me"})
    check("DM to self is 400", s == 400, f"{s}")
    s, empty_dm = call("POST", f"/api/dm/{priya_id}", token=token_a,
                       body={"body": "   "})
    check("empty DM is 400", s == 400, f"{s}")
    s, long_dm = call("POST", f"/api/dm/{priya_id}", token=token_a,
                      body={"body": "x" * 2001})
    check("DM over 2000 chars is 400", s == 400, f"{s}")
    s, ghost_dm = call("POST", "/api/dm/99999", token=token_a, body={"body": "hi"})
    check("DM to nonexistent user is 404", s == 404, f"{s}")
    s, ghost_thread = call("GET", "/api/dm/99999", token=token_a)
    check("thread with nonexistent user is 404", s == 404, f"{s}")
    s, noauth_dm = call("POST", f"/api/dm/{priya_id}", body={"body": "hi"})
    check("unauthenticated DM is 401", s == 401, f"{s}")
    s, noauth_unread = call("GET", "/api/dm/unread")
    check("unauthenticated unread is 401", s == 401, f"{s}")

    print("== username change ==")
    s, rn = call("PATCH", "/api/users/me", token=token_a, body={"username": "ayan_new"})
    check("change own username", s == 200 and rn.get("username") == "ayan_new",
          f"{s} {rn}")
    s, rn_taken = call("PATCH", "/api/users/me", token=token_a, body={"username": "priya"})
    check("taken username -> 409", s == 409, f"{s}")
    s, rn_bad = call("PATCH", "/api/users/me", token=token_a, body={"username": "ab"})
    check("too-short username rejected", s == 400, f"{s}")
    s, rn_bad2 = call("PATCH", "/api/users/me", token=token_a,
                      body={"username": "bad name!"})
    check("bad characters rejected", s == 400, f"{s}")
    s, login_new = call("POST", "/api/auth/login",
                        body={"username": "ayan_new", "password": "pass1234"})
    check("login works with new username", s == 200 and "token" in login_new,
          f"{s} {login_new}")
    s, login_old = call("POST", "/api/auth/login",
                        body={"username": "ayan", "password": "pass1234"})
    check("old username no longer logs in", s == 401, f"{s}")
    s, rn_me = call("GET", "/api/auth/me", token=token_a)
    check("me shows new username", s == 200 and rn_me.get("username") == "ayan_new",
          f"{s} {rn_me}")

    print(f"\nRESULT: {PASS} passed, {FAIL} failed")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
