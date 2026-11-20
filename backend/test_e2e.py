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
    s, users_dn = call("GET", "/api/users?q=Sharma", token=token_a)
    check("search matches display name (Sharma -> priya)",
          s == 200 and any(u["username"] == "priya" for u in users_dn), f"{s} {users_dn}")
    s, users_dn2 = call("GET", "/api/users?q=verma", token=token_a)
    check("search matches display name case-insensitively (verma -> rahul)",
          s == 200 and any(u["username"] == "rahul" for u in users_dn2), f"{s} {users_dn2}")
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
    # v2.4: with no CLOUDINARY_* env vars the upload MUST stay local —
    # a /media/ URL proves the Cloudinary branch did not trigger.
    check("upload fallback is local /media/ (no Cloudinary env)",
          up.get("url", "").startswith("/media/"), f"{up}")
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
    s, vstory = call("POST", "/api/stories", token=token_p,
                     body={"video_url": "https://example.com/story.mp4"})
    check("priya video story created (kind video, video_url kept)",
          s == 200 and vstory.get("kind") == "video"
          and vstory.get("video_url") == "https://example.com/story.mp4"
          and vstory.get("image_url") == "", f"{s} {vstory}")
    s, nostory = call("POST", "/api/stories", token=token_p, body={})
    check("story with no media rejected (400)", s == 400, f"{s} {nostory}")
    s, stories = call("GET", "/api/stories", token=token_a)
    check("stories list shows followed author + own (priya, ayan)",
          s == 200 and any(g["user"]["username"] == "priya" for g in stories)
          and any(g["user"]["username"] == "ayan" for g in stories), f"{s} {stories}")
    s, stories_r0 = call("GET", "/api/stories", token=token_r)
    check("stories hidden from non-follower (rahul follows nobody -> empty)",
          s == 200 and stories_r0 == [], f"{s} {stories_r0}")
    s, stories_p0 = call("GET", "/api/stories", token=token_p)
    check("stories list for priya shows only her own group",
          s == 200 and len(stories_p0) == 1
          and stories_p0[0]["user"]["username"] == "priya", f"{s} {stories_p0}")
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
    s, stories2 = call("GET", "/api/stories", token=token_a)
    check("expired story hidden from list (ayan group gone, priya stays)",
          s == 200 and not any(g["user"]["username"] == "ayan" for g in stories2)
          and any(g["user"]["username"] == "priya" for g in stories2),
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

    print("== presence (online/offline) + story likes + story delete ==")
    s, prof_on = call("GET", f"/api/users/{priya_id}", token=token_a)
    check("profile shows priya online (recent request)",
          s == 200 and prof_on.get("online") is True
          and prof_on.get("last_seen_at"), f"{s} {prof_on}")
    con = sqlite3.connect(DB_PATH)
    con.execute("UPDATE users SET last_seen_at = datetime('now', '-10 minutes') "
                "WHERE id = ?", (priya_id,))
    con.commit()
    con.close()
    s, prof_off = call("GET", f"/api/users/{priya_id}", token=token_a)
    check("profile shows priya offline (last seen 10 min ago)",
          s == 200 and prof_off.get("online") is False, f"{s} {prof_off}")
    call("GET", "/api/auth/me", token=token_p)  # priya acts again -> online
    s, prof_on2 = call("GET", f"/api/users/{priya_id}", token=token_a)
    check("profile shows priya online again after she acts",
          s == 200 and prof_on2.get("online") is True, f"{s} {prof_on2}")

    s, slike = call("POST", f"/api/stories/{story_id}/like", token=token_a)
    check("ayan likes priya story", s == 200 and slike.get("liked") is True
          and slike.get("like_count") == 1, f"{s} {slike}")
    s, slike2 = call("POST", f"/api/stories/{story_id}/like", token=token_a)
    check("story like toggle off", s == 200 and slike2.get("liked") is False
          and slike2.get("like_count") == 0, f"{s} {slike2}")
    call("POST", f"/api/stories/{story_id}/like", token=token_a)
    s, stories_l = call("GET", "/api/stories", token=token_a)
    pg = next((g for g in stories_l if g["user"]["username"] == "priya"), None)
    # Find the liked photo story by id (not by position: priya's group
    # also holds the newer video story, which sorts first).
    check("story list shows like_count + liked_by_me",
          s == 200 and pg is not None and any(
              st["id"] == story_id and st.get("like_count") == 1
              and st.get("liked_by_me") is True for st in pg["stories"]),
          f"{s} {stories_l}")
    s, slike_ghost = call("POST", "/api/stories/99999/like", token=token_a)
    check("like on nonexistent story is 404", s == 404, f"{s}")

    s, story3 = call("POST", "/api/stories", token=token_a,
                     body={"image_url": img_url})
    check("ayan creates a story to delete", s == 200 and story3.get("id"),
          f"{s} {story3}")
    story3_id = story3["id"]
    s, del_no = call("DELETE", f"/api/stories/{story3_id}", token=token_p)
    check("cannot delete someone else's story", s == 403, f"{s} {del_no}")
    s, hl3 = call("POST", "/api/highlights", token=token_a,
                  body={"name": "To Delete", "story_ids": [story3_id]})
    check("highlight from story3 created", s == 200 and hl3.get("id"), f"{s} {hl3}")
    hl3_id = hl3["id"]
    s, del_yes = call("DELETE", f"/api/stories/{story3_id}", token=token_a)
    check("owner deletes own story", s == 200, f"{s} {del_yes}")
    s, hl3_after = call("GET", f"/api/highlights/{hl3_id}", token=token_a)
    check("highlight skips the deleted story (empty, count 0)",
          s == 200 and hl3_after.get("stories") == []
          and hl3_after.get("story_count") == 0, f"{s} {hl3_after}")
    s, del_gone = call("DELETE", f"/api/stories/{story3_id}", token=token_a)
    check("deleted story delete again is 404", s == 404, f"{s} {del_gone}")
    s, stories3 = call("GET", "/api/stories", token=token_a)
    check("deleted story gone from list (no ayan group, priya stays)",
          s == 200 and not any(g["user"]["username"] == "ayan" for g in stories3)
          and any(g["user"]["username"] == "priya" for g in stories3),
          f"{s} {stories3}")
    s, del_ghost = call("DELETE", "/api/stories/99999", token=token_a)
    check("delete nonexistent story is 404", s == 404, f"{s}")

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
    s, thread_read = call("GET", f"/api/dm/{priya_id}", token=token_a)
    check("sender sees read_at set after recipient reads",
          s == 200 and len(thread_read) == 1
          and thread_read[0].get("read_at"), f"{s} {thread_read}")
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

    print("== Seen re-verification (v2.0: Seen ONLY when recipient opens the chat) ==")
    s, seen_dm = call("POST", f"/api/dm/{priya_id}", token=token_a,
                     body={"body": "Seen check 1"})
    check("ayan sends fresh Seen-test DM",
          s == 200 and seen_dm.get("body") == "Seen check 1", f"{s} {seen_dm}")

    def seen_read_at():
        # Sender's view of the test message: None = "Sent", set = "Seen".
        s2, th = call("GET", f"/api/dm/{priya_id}", token=token_a)
        m = next((x for x in th if x.get("body") == "Seen check 1"), None)
        return m.get("read_at") if m else "MISSING"

    call("GET", f"/api/dm/{ayan_id}", token=token_p)  # priya reads the thread
    check("recipient read the thread, no markRead yet -> still Sent",
          seen_read_at() is None)
    # Recipient opens only the DM list (not the chat).
    call("GET", "/api/dm/conversations", token=token_p)
    check("recipient opened only the DM list -> still Sent",
          seen_read_at() is None)
    # Sender polls his own chat (open + markRead as the sender).
    call("GET", f"/api/dm/{priya_id}", token=token_a)
    call("POST", f"/api/dm/{priya_id}/read", token=token_a)
    check("sender polled + marked his own chat -> still Sent",
          seen_read_at() is None)
    # Recipient finally opens the chat (marks read).
    s, rd2 = call("POST", f"/api/dm/{ayan_id}/read", token=token_p)
    check("recipient opens chat (markRead) -> read 1", s == 200 and rd2.get("read") == 1,
          f"{s} {rd2}")
    check("sender now sees Seen (read_at set)",
          seen_read_at() is not None, f"{seen_read_at()}")

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

    print("== notifications (v2.0, isolated user nina) ==")
    s, nina = call("POST", "/api/auth/register",
                   body={"username": "nina", "display_name": "Nina Kapoor",
                         "password": "pass1234"})
    check("register nina", s == 200 and "token" in nina, f"{s} {nina}")
    token_n = nina.get("token")
    nina_id = nina["user"]["id"]

    def nina_unread():
        s2, u = call("GET", "/api/notifications/unread-count", token=token_n)
        return u.get("unread") if s2 == 200 else -1

    def nina_has(ntype, actor_username):
        s2, ns = call("GET", "/api/notifications", token=token_n)
        return (s2 == 200 and any(
            n.get("type") == ntype and n.get("actor", {}).get("username") == actor_username
            for n in ns), ns)

    s, fol_n = call("POST", f"/api/users/{nina_id}/follow", token=token_a)
    check("ayan follows nina", s == 200 and fol_n.get("following") is True, f"{s} {fol_n}")
    ok, _ = nina_has("follow", "ayan_new")
    check("nina got a follow notification from ayan_new", ok)
    check("nina unread count is 1", nina_unread() == 1, f"{nina_unread()}")
    s, npost = call("POST", "/api/posts", token=token_n,
                    body={"caption": "Nina ki photo", "image_url": img_url})
    check("nina creates a photo post", s == 200 and npost.get("id"), f"{s} {npost}")
    npost_id = npost["id"]
    s, nlike = call("POST", f"/api/posts/{npost_id}/like", token=token_a)
    check("ayan likes nina post", s == 200 and nlike.get("liked") is True, f"{s} {nlike}")
    ok, _ = nina_has("post_like", "ayan_new")
    check("nina got a post_like notification", ok)
    check("nina unread count is 2", nina_unread() == 2, f"{nina_unread()}")
    s, ncom = call("POST", f"/api/posts/{npost_id}/comments", token=token_a,
                   body={"text": "Bahut sundar!"})
    check("ayan comments on nina post", s == 200 and ncom.get("id"), f"{s} {ncom}")
    ok, _ = nina_has("post_comment", "ayan_new")
    check("nina got a post_comment notification", ok)
    check("nina unread count is 3", nina_unread() == 3, f"{nina_unread()}")
    s, nstory = call("POST", "/api/stories", token=token_n,
                     body={"image_url": img_url})
    check("nina creates a story", s == 200 and nstory.get("id"), f"{s} {nstory}")
    nstory_id = nstory["id"]
    s, nslike = call("POST", f"/api/stories/{nstory_id}/like", token=token_a)
    check("ayan likes nina story", s == 200 and nslike.get("liked") is True, f"{s} {nslike}")
    ok, _ = nina_has("story_like", "ayan_new")
    check("nina got a story_like notification", ok)
    check("nina unread count is 4", nina_unread() == 4, f"{nina_unread()}")
    s, ndm1 = call("POST", f"/api/dm/{nina_id}", token=token_a,
                   body={"body": "Hi Nina!"})
    check("ayan DMs nina", s == 200 and ndm1.get("id"), f"{s} {ndm1}")
    # v2.3 (a): a DM must NOT create a heart notification.
    ok, _ = nina_has("dm", "ayan_new")
    check("v2.3: NO dm notification after DM", not ok)
    check("v2.3: heart unread count still 4 after DM",
          nina_unread() == 4, f"{nina_unread()}")
    # v2.3 (b): the DM itself must still show in the DM badge.
    s, dmu = call("GET", "/api/dm/unread", token=token_n)
    check("v2.3: DM badge unread is 1 after DM",
          s == 200 and dmu.get("unread") == 1, f"{s} {dmu}")
    s, ndm2 = call("POST", f"/api/dm/{nina_id}", token=token_a,
                   body={"body": "Aur batao?"})
    check("ayan DMs nina again", s == 200 and ndm2.get("id"), f"{s} {ndm2}")
    check("v2.3: heart unread count still 4 after second DM",
          nina_unread() == 4, f"{nina_unread()}")
    s, ndm3 = call("POST", f"/api/dm/{nina_id}", token=token_a,
                   body={"body": "📸 Story reply: mast story!"})
    check("ayan sends a story-reply DM", s == 200 and ndm3.get("id"), f"{s} {ndm3}")
    ok, _ = nina_has("story_reply", "ayan_new")
    check("v2.3: NO story_reply notification (it is a DM)", not ok)
    check("v2.3: heart unread count still 4 after story-reply DM",
          nina_unread() == 4, f"{nina_unread()}")
    s, dmu = call("GET", "/api/dm/unread", token=token_n)
    check("v2.3: DM badge unread is 3 after all three DMs",
          s == 200 and dmu.get("unread") == 3, f"{s} {dmu}")
    s, nlist = call("GET", "/api/notifications", token=token_n)
    check("notifications list newest first, actor included",
          s == 200 and len(nlist) == 4 and nlist[0].get("type") == "story_like"
          and nlist[0].get("actor", {}).get("username") == "ayan_new", f"{s} {nlist}")
    follow_notif = next((n for n in nlist if n.get("type") == "follow"), None)
    s, rd_one = call("POST", f"/api/notifications/{follow_notif['id']}/read", token=token_n)
    check("mark one notification read", s == 200, f"{s} {rd_one}")
    check("nina unread count is 3 after marking one read",
          nina_unread() == 3, f"{nina_unread()}")
    s, rd_all = call("POST", "/api/notifications/read", token=token_n)
    check("mark all notifications read", s == 200 and rd_all.get("read") == 3,
          f"{s} {rd_all}")
    check("nina unread count is 0 after mark-all-read", nina_unread() == 0,
          f"{nina_unread()}")
    # v2.3 (e): legacy dm/story_reply rows must stay out of the heart centre.
    import datetime
    _con = sqlite3.connect(DB_PATH)
    _cur = _con.cursor()
    _now = datetime.datetime.utcnow().isoformat()
    _cur.execute(
        "INSERT INTO notifications (user_id, actor_id, type, ref_id, body,"
        " is_read, created_at) VALUES (?, ?, 'dm', ?, 'legacy dm', 0, ?)",
        (nina_id, ayan_id, ayan_id, _now))
    _cur.execute(
        "INSERT INTO notifications (user_id, actor_id, type, ref_id, body,"
        " is_read, created_at) VALUES (?, ?, 'story_reply', ?, 'legacy reply', 0, ?)",
        (nina_id, ayan_id, ayan_id, _now))
    _con.commit()
    s, nlist2 = call("GET", "/api/notifications", token=token_n)
    check("v2.3: legacy dm/story_reply rows excluded from list",
          s == 200 and all(n.get("type") not in ("dm", "story_reply")
                           for n in nlist2), f"{s} {nlist2}")
    check("v2.3: legacy dm/story_reply rows excluded from unread count",
          nina_unread() == 0, f"{nina_unread()}")
    _cur.execute("DELETE FROM notifications WHERE user_id = ?"
                 " AND type IN ('dm', 'story_reply')", (nina_id,))
    _con.commit()
    _con.close()
    s, rd_ghost = call("POST", "/api/notifications/99999/read", token=token_n)
    check("mark nonexistent notification read is 404", s == 404, f"{s}")
    s, rd_other = call("POST", f"/api/notifications/{follow_notif['id']}/read",
                       token=token_a)
    check("cannot mark someone else's notification read (404)", s == 404, f"{s}")
    s, noauth_notif = call("GET", "/api/notifications")
    check("notifications without token -> 401", s == 401, f"{s}")

    print("== typing indicator (v2.0) ==")
    s, ty = call("POST", f"/api/dm/{priya_id}/typing", token=token_a)
    check("ayan typing heartbeat to priya", s == 200 and ty.get("typing") is True,
          f"{s} {ty}")
    s, ty2 = call("GET", f"/api/dm/{ayan_id}/typing", token=token_p)
    check("priya sees ayan typing", s == 200 and ty2.get("typing") is True,
          f"{s} {ty2}")
    s, ty3 = call("GET", f"/api/dm/{priya_id}/typing", token=token_a)
    check("ayan does not see priya typing (she is not)",
          s == 200 and ty3.get("typing") is False, f"{s} {ty3}")
    con = sqlite3.connect(DB_PATH)
    con.execute("UPDATE typing_states SET updated_at = datetime('now', '-30 seconds') "
                "WHERE typer_id = ?", (ayan_id,))
    con.commit()
    con.close()
    s, ty4 = call("GET", f"/api/dm/{ayan_id}/typing", token=token_p)
    check("typing expires after 10s (stale heartbeat -> false)",
          s == 200 and ty4.get("typing") is False, f"{s} {ty4}")
    call("POST", f"/api/dm/{priya_id}/typing", token=token_a)
    s, ty5 = call("GET", f"/api/dm/{ayan_id}/typing", token=token_p)
    check("fresh heartbeat -> typing true again",
          s == 200 and ty5.get("typing") is True, f"{s} {ty5}")
    s, ty_ghost = call("POST", "/api/dm/99999/typing", token=token_a)
    check("typing heartbeat to nonexistent user is 404", s == 404, f"{s}")
    s, ty_ghost2 = call("GET", "/api/dm/99999/typing", token=token_a)
    check("typing status for nonexistent user is 404", s == 404, f"{s}")
    s, ty_noauth = call("GET", f"/api/dm/{priya_id}/typing")
    check("typing status without token -> 401", s == 401, f"{s}")

    print("== DM photo/video messages (v2.0) ==")
    s, up_dm = upload_png(token_a)
    check("image upload for DM", s == 200 and up_dm.get("url"), f"{s} {up_dm}")
    dm_img_url = up_dm.get("url", "")
    s, dm_img = call("POST", f"/api/dm/{nina_id}", token=token_a,
                     body={"body": "", "media_url": dm_img_url, "media_kind": "image"})
    check("ayan sends a photo DM (no text)",
          s == 200 and dm_img.get("media_url") == dm_img_url
          and dm_img.get("media_kind") == "image", f"{s} {dm_img}")
    s, th_img = call("GET", f"/api/dm/{ayan_id}", token=token_n)
    check("nina thread shows the photo DM",
          s == 200 and any(m.get("media_url") == dm_img_url
                           and m.get("media_kind") == "image" for m in th_img),
          f"{s} {th_img}")
    s, dm_vid = call("POST", f"/api/dm/{nina_id}", token=token_a,
                     body={"body": "Ye video dekho",
                           "media_url": "/media/test-reel.mp4", "media_kind": "video"})
    check("ayan sends a video DM with caption",
          s == 200 and dm_vid.get("media_kind") == "video"
          and dm_vid.get("media_url") == "/media/test-reel.mp4"
          and dm_vid.get("body") == "Ye video dekho", f"{s} {dm_vid}")
    s, dm_bad_kind = call("POST", f"/api/dm/{nina_id}", token=token_a,
                          body={"body": "hi", "media_url": "/media/x.jpg",
                                "media_kind": "audio"})
    check("bad media_kind is 400", s == 400, f"{s}")
    s, dm_empty2 = call("POST", f"/api/dm/{nina_id}", token=token_a,
                        body={"body": "   ", "media_url": "", "media_kind": "text"})
    check("DM with neither text nor media is 400", s == 400, f"{s}")

    print("== Reels / video posts (v2.0) ==")
    s, reel = call("POST", "/api/posts", token=token_n,
                   body={"caption": "Mera pehla reel!",
                         "video_url": "/media/test-reel.mp4"})
    check("nina creates a Reel (video post)",
          s == 200 and reel.get("id") and reel.get("video_url") == "/media/test-reel.mp4",
          f"{s} {reel}")
    reel_id = reel["id"]
    s, feed_reel = call("GET", "/api/posts", token=token_a)
    check("followers feed shows the Reel with video_url",
          s == 200 and any(p["id"] == reel_id
                           and p.get("video_url") == "/media/test-reel.mp4"
                           for p in feed_reel), f"{s} {feed_reel}")
    s, ex_reel = call("GET", "/api/explore", token=token_r)
    check("explore shows the Reel with video_url",
          s == 200 and any(p["id"] == reel_id
                           and p.get("video_url") == "/media/test-reel.mp4"
                           for p in ex_reel), f"{s} {ex_reel}")
    s, reel_detail = call("GET", f"/api/posts/{reel_id}", token=token_a)
    check("post detail returns video_url",
          s == 200 and reel_detail.get("video_url") == "/media/test-reel.mp4",
          f"{s} {reel_detail}")
    s, no_media_post = call("POST", "/api/posts", token=token_n,
                            body={"caption": "no media at all"})
    check("post with neither photo nor video is 400", s == 400, f"{s}")

    print("== block / unblock (v3.1) ==")
    s, vikram = call("POST", "/api/auth/register",
                     body={"username": "vikram", "display_name": "Vikram Singh",
                           "password": "pass1234"})
    check("register vikram", s == 200 and "token" in vikram, f"{s} {vikram}")
    token_v = vikram.get("token")
    vikram_id = vikram["user"]["id"]
    s, meera = call("POST", "/api/auth/register",
                    body={"username": "meera", "display_name": "Meera Joshi",
                          "password": "pass1234"})
    check("register meera", s == 200 and "token" in meera, f"{s} {meera}")
    token_m = meera.get("token")
    meera_id = meera["user"]["id"]
    s, fol_vm = call("POST", f"/api/users/{meera_id}/follow", token=token_v)
    check("vikram follows meera", s == 200 and fol_vm.get("following") is True,
          f"{s} {fol_vm}")
    s, fol_mv = call("POST", f"/api/users/{vikram_id}/follow", token=token_m)
    check("meera follows vikram", s == 200 and fol_mv.get("following") is True,
          f"{s} {fol_mv}")
    s, mpost = call("POST", "/api/posts", token=token_m,
                    body={"caption": "Meera ki photo", "image_url": img_url})
    check("meera creates a post", s == 200 and mpost.get("id"), f"{s} {mpost}")
    mpost_id = mpost["id"]
    s, mstory = call("POST", "/api/stories", token=token_m,
                     body={"image_url": img_url})
    check("meera creates a story", s == 200 and mstory.get("id"), f"{s} {mstory}")
    s, feed_v = call("GET", "/api/posts", token=token_v)
    check("vikram feed shows meera post before block",
          s == 200 and any(p["id"] == mpost_id for p in feed_v), f"{s} {feed_v}")
    s, stories_v = call("GET", "/api/stories", token=token_v)
    check("vikram stories show meera group before block",
          s == 200 and any(g["user"]["username"] == "meera" for g in stories_v),
          f"{s} {stories_v}")
    s, dm_pre = call("POST", f"/api/dm/{meera_id}", token=token_v,
                     body={"body": "Hi Meera, block se pehle"})
    check("DM works before block", s == 200 and dm_pre.get("id"), f"{s} {dm_pre}")

    s, blk = call("POST", f"/api/users/{meera_id}/block", token=token_v)
    check("vikram blocks meera", s == 200 and blk.get("blocked") is True, f"{s} {blk}")
    s, blk_list = call("GET", "/api/users/blocked", token=token_v)
    check("blocked list shows meera",
          s == 200 and any(u["username"] == "meera" for u in blk_list),
          f"{s} {blk_list}")
    s, prof_blk = call("GET", f"/api/users/{meera_id}", token=token_v)
    check("profile shows blocked_by_me true",
          s == 200 and prof_blk.get("blocked_by_me") is True, f"{s} {prof_blk}")
    s, prof_notblk = call("GET", f"/api/users/{vikram_id}", token=token_m)
    check("meera sees vikram blocked_by_me false",
          s == 200 and prof_notblk.get("blocked_by_me") is False, f"{s} {prof_notblk}")
    s, fol_after = call("GET", f"/api/users/{meera_id}/followers", token=token_v)
    check("block removed vikram -> meera follow",
          s == 200 and not any(u["username"] == "vikram" for u in fol_after),
          f"{s} {fol_after}")
    s, fing_after = call("GET", f"/api/users/{vikram_id}/following", token=token_v)
    check("block removed meera from vikram following",
          s == 200 and not any(u["username"] == "meera" for u in fing_after),
          f"{s} {fing_after}")
    s, fol_rev = call("GET", f"/api/users/{vikram_id}/followers", token=token_m)
    check("block removed meera -> vikram follow (reverse direction)",
          s == 200 and not any(u["username"] == "meera" for u in fol_rev),
          f"{s} {fol_rev}")
    s, prof_cnt = call("GET", f"/api/users/{meera_id}", token=token_v)
    check("meera followers_count back to 0 after block",
          s == 200 and prof_cnt.get("followers_count") == 0, f"{s} {prof_cnt}")
    s, feed_v2 = call("GET", "/api/posts", token=token_v)
    check("blocked user post hidden from feed",
          s == 200 and not any(p["id"] == mpost_id for p in feed_v2),
          f"{s} {feed_v2}")
    s, stories_v2 = call("GET", "/api/stories", token=token_v)
    check("blocked user stories hidden from list",
          s == 200 and not any(g["user"]["username"] == "meera" for g in stories_v2),
          f"{s} {stories_v2}")
    s, dm_blk1 = call("POST", f"/api/dm/{meera_id}", token=token_v,
                      body={"body": "Hi Meera"})
    check("DM to blocked user is 403", s == 403, f"{s} {dm_blk1}")
    s, dm_blk2 = call("POST", f"/api/dm/{vikram_id}", token=token_m,
                      body={"body": "Hi Vikram"})
    check("DM from blocked user is 403 (reverse direction)", s == 403,
          f"{s} {dm_blk2}")
    s, blk2 = call("POST", f"/api/users/{meera_id}/block", token=token_v)
    check("block again is idempotent (still blocked)",
          s == 200 and blk2.get("blocked") is True, f"{s} {blk2}")
    s, blk_self = call("POST", f"/api/users/{vikram_id}/block", token=token_v)
    check("self-block is 400", s == 400, f"{s} {blk_self}")
    s, blk_ghost = call("POST", "/api/users/99999/block", token=token_v)
    check("block nonexistent user is 404", s == 404, f"{s} {blk_ghost}")
    s, blk_noauth = call("POST", f"/api/users/{meera_id}/block")
    check("block without token is 401", s == 401, f"{s} {blk_noauth}")

    s, unblk = call("POST", f"/api/users/{meera_id}/unblock", token=token_v)
    check("vikram unblocks meera", s == 200 and unblk.get("blocked") is False,
          f"{s} {unblk}")
    s, blk_list2 = call("GET", "/api/users/blocked", token=token_v)
    check("blocked list empty after unblock", s == 200 and blk_list2 == [],
          f"{s} {blk_list2}")
    s, prof_unblk = call("GET", f"/api/users/{meera_id}", token=token_v)
    check("profile shows blocked_by_me false after unblock",
          s == 200 and prof_unblk.get("blocked_by_me") is False, f"{s} {prof_unblk}")
    s, dm_after = call("POST", f"/api/dm/{meera_id}", token=token_v,
                       body={"body": "Hi Meera, unblock ke baad"})
    check("DM works again after unblock", s == 200 and dm_after.get("id"),
          f"{s} {dm_after}")
    s, unblk_ghost = call("POST", "/api/users/99999/unblock", token=token_v)
    check("unblock nonexistent user is 404", s == 404, f"{s} {unblk_ghost}")
    s, unblk2 = call("POST", f"/api/users/{meera_id}/unblock", token=token_v)
    check("unblock when not blocked is idempotent (still false)",
          s == 200 and unblk2.get("blocked") is False, f"{s} {unblk2}")

    print(f"\nRESULT: {PASS} passed, {FAIL} failed")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
