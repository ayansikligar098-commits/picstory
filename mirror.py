#!/usr/bin/env python3
"""
Build a local Maven mirror of every dependency Fly Birds needs, using curl
(this VM blocks ALL direct TCP from Java, so Gradle itself cannot download
anything — but curl works through the egress proxy).

Downloads POMs + artifacts (jar/aar) for the full transitive closure of the
roots below, resolving parent POMs, properties, dependencyManagement and
BOM imports. Writes a standard Maven repo layout into ../local-maven
(relative to the project dir), which settings.gradle.kts then uses with
`--offline`.

Usage: python3 mirror.py [--check-only]
  --check-only : only report which artifacts are missing, don't download.
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import deque

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_DIR = os.path.normpath(os.path.join(PROJECT_DIR, "..", "local-maven"))

REPOS = [
    ("google", "https://dl.google.com/dl/android/maven2"),
    ("plugins", "https://plugins.gradle.org/m2"),
    ("central", "https://repo.maven.apache.org/maven2"),
]

# (group, artifact, version, type, classifier) — type/classifier may be None
# for "resolve from POM".
ROOTS = [
    # Gradle plugin markers (resolved from the plugin portal).
    ("com.android.application", "com.android.application.gradle.plugin", "8.5.2", None, None),
    ("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.android.gradle.plugin", "1.9.24", None, None),
    # App libraries.
    ("com.squareup.okhttp3", "okhttp", "4.12.0", None, None),
    ("com.squareup.okio", "okio", "3.6.0", None, None),
    ("org.jetbrains", "annotations", "13.0", None, None),
    # Kotlin stdlib for the app (the kotlin-android plugin adds it; make sure
    # the exact version the plugin uses is mirrored).
    ("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.24", None, None),
    # AGP 8.5.2's graph selects these versions (the Kotlin plugin POM
    # excludes kotlin-stdlib, so 1.9.24 never enters the :classpath graph).
    ("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.22", None, None),
    ("org.jetbrains.kotlin", "kotlin-stdlib-jdk7", "1.9.22", None, None),
    ("org.jetbrains.kotlin", "kotlin-stdlib-jdk8", "1.9.22", None, None),
    ("org.jetbrains.kotlin", "kotlin-reflect", "1.9.22", None, None),
    # Declared only in Gradle module metadata (not in POMs), so the
    # POM-only resolver misses them — mirror explicitly.
    ("org.jvnet.staxex", "stax-ex", "1.8.1", None, None),
    ("com.sun.xml.fastinfoset", "FastInfoset", "1.2.16", None, None),
]

SKIP_SCOPES = {"test", "provided", "system"}
CHECK_ONLY = "--check-only" in sys.argv


def log(msg):
    print(msg, flush=True)


def curl_get(url, out_path):
    """Download url to out_path with curl. Returns True on HTTP 200."""
    parent = os.path.dirname(out_path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    r = subprocess.run(
        ["curl", "-sS", "-f", "-L", "--max-time", "120", "-o", out_path, url],
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        if os.path.exists(out_path):
            os.remove(out_path)
        return False
    return True


def group_path(group):
    return group.replace(".", "/")


def pom_path(repo_dir, group, artifact, version):
    return os.path.join(repo_dir, group_path(group), artifact, version,
                        f"{artifact}-{version}.pom")


def artifact_path(repo_dir, group, artifact, version, ext, classifier=None):
    name = f"{artifact}-{version}"
    if classifier:
        name += f"-{classifier}"
    return os.path.join(repo_dir, group_path(group), artifact, version,
                        f"{name}.{ext}")


def fetch_from_repos(rel_path):
    """Try each repo in order; return (repo_name, url) of the first 200."""
    for name, base in REPOS:
        url = f"{base}/{rel_path}"
        tmp = os.path.join("/tmp", "mirror_probe")
        if curl_get(url, tmp):
            os.remove(tmp)
            return name, url
    return None, None


def version_key(v):
    """Rough semver-ish sort key: numeric runs compare numerically."""
    parts = []
    for chunk in re.split(r"[.\-+_]", v):
        parts.append((0, int(chunk), "") if chunk.isdigit() else (1, 0, chunk))
    return parts


def resolve_range(version, group, artifact):
    """Resolve a Maven version range. '[x]' is an exact pin; open ranges
    pick the highest available version from maven-metadata.xml."""
    m = re.match(r"^[\[\(]([^,\]\)]+)(?:,([^,\]\)]+))?[\]\)]$", version.strip())
    if not m:
        return version
    low, high = m.group(1).strip(), m.group(2)
    if high is None:
        return low
    high = high.strip()
    incl_low = version.strip().startswith("[")
    incl_high = version.strip().endswith("]")
    rel = f"{group_path(group)}/{artifact}/maven-metadata.xml"
    for name, base in REPOS:
        tmp = "/tmp/mirror_meta.xml"
        if curl_get(f"{base}/{rel}", tmp):
            try:
                root = ET.parse(tmp).getroot()
                versions = [e.text.strip() for e in root.iter()
                            if local_name(e.tag) == "version" and e.text]
            except Exception:
                continue
            def in_range(v):
                k = version_key(v)
                if version_key(low) > k:
                    return False
                if not incl_low and version_key(low) == k:
                    return False
                if version_key(high) < k:
                    return False
                if not incl_high and version_key(high) == k:
                    return False
                return True
            cands = sorted([v for v in versions if in_range(v)], key=version_key)
            if cands:
                log(f"  range {version} for {group}:{artifact} -> {cands[-1]}")
                return cands[-1]
    log(f"  WARN: could not resolve range {version} for {group}:{artifact}; using {low}")
    return low


def fetch_pom(group, artifact, version):
    """Return (pom_text, repo_base_url) or (None, None)."""
    rel = f"{group_path(group)}/{artifact}/{version}/{artifact}-{version}.pom"
    dest = pom_path(REPO_DIR, group, artifact, version)
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        with open(dest, encoding="utf-8", errors="replace") as f:
            return f.read(), None
    for name, base in REPOS:
        url = f"{base}/{rel}"
        if curl_get(url, dest):
            with open(dest, encoding="utf-8", errors="replace") as f:
                return f.read(), base
    return None, None


def local_name(tag):
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag


def children(elem, name):
    return [c for c in elem if local_name(c.tag) == name]


def child_text(elem, name):
    for c in elem:
        if local_name(c.tag) == name:
            return (c.text or "").strip()
    return ""


PROPS_RE = re.compile(r"\$\{([^}]+)\}")


def resolve_props(text, props):
    """Resolve ${...} placeholders against props (recursively, bounded)."""
    if text is None:
        return None
    for _ in range(10):
        m = PROPS_RE.search(text)
        if not m:
            return text
        key = m.group(1)
        if key not in props:
            return text  # leave unresolved; caller decides
        text = text[: m.start()] + props[key] + text[m.end():]
    return text


class Pom:
    def __init__(self, group, artifact, version):
        self.group = group
        self.artifact = artifact
        self.version = version
        self.props = {}
        self.dep_mgmt = {}  # (g,a) -> dict(version, scope, type, classifier)
        self.deps = []      # list of dicts

    def merge_parent(self, parent):
        for k, v in parent.props.items():
            self.props.setdefault(k, v)
        for k, v in parent.dep_mgmt.items():
            self.dep_mgmt.setdefault(k, v)
        # parent's own deps are NOT inherited for our purposes (we only
        # need them if this pom has no deps of its own — rare; skip).


def parse_pom(text, group, artifact, version, seen_boms):
    root = ET.fromstring(text)
    pom = Pom(group, artifact, version)
    pom.props["project.groupId"] = group
    pom.props["project.artifactId"] = artifact
    pom.props["project.version"] = version

    # parent first (its props/mgmt are defaults)
    parent_el = None
    for c in root:
        if local_name(c.tag) == "parent":
            parent_el = c
            break
    if parent_el is not None:
        pg = child_text(parent_el, "groupId")
        pa = child_text(parent_el, "artifactId")
        pv = child_text(parent_el, "version")
        if pg and pa and pv:
            ptext, _ = fetch_pom(pg, pa, pv)
            if ptext:
                parent = parse_pom(ptext, pg, pa, pv, seen_boms)
                pom.merge_parent(parent)
            else:
                log(f"  WARN: parent POM not found: {pg}:{pa}:{pv}")

    # properties
    for c in root:
        if local_name(c.tag) == "properties":
            for p in c:
                pom.props[local_name(p.tag)] = (p.text or "").strip()

    def apply_mgmt(mgmt_el):
        for dep in children(mgmt_el, "dependency"):
            g = child_text(dep, "groupId")
            a = child_text(dep, "artifactId")
            v = child_text(dep, "version")
            scope = child_text(dep, "scope") or "compile"
            typ = child_text(dep, "type") or "jar"
            cls = child_text(dep, "classifier") or None
            if not g or not a:
                continue
            if scope == "import" and typ == "pom":
                key = (g, a, resolve_props(v, pom.props) or v)
                if key in seen_boms:
                    continue
                seen_boms.add(key)
                btext, _ = fetch_pom(g, a, key[2])
                if btext:
                    bom = parse_pom(btext, g, a, key[2], seen_boms)
                    for k, val in bom.dep_mgmt.items():
                        pom.dep_mgmt.setdefault(k, val)
                    for k, val in bom.props.items():
                        pom.props.setdefault(k, val)
                else:
                    log(f"  WARN: BOM not found: {g}:{a}:{key[2]}")
                continue
            if (g, a) not in pom.dep_mgmt:
                pom.dep_mgmt[(g, a)] = {
                    "version": resolve_props(v, pom.props) if v else None,
                    "scope": scope, "type": typ, "classifier": cls,
                }

    for c in root:
        if local_name(c.tag) == "dependencyManagement":
            for deps_el in children(c, "dependencies"):
                apply_mgmt(deps_el)

    for c in root:
        if local_name(c.tag) == "dependencies":
            for dep in children(c, "dependency"):
                g = resolve_props(child_text(dep, "groupId"), pom.props)
                a = resolve_props(child_text(dep, "artifactId"), pom.props)
                v = child_text(dep, "version")
                scope = child_text(dep, "scope") or "compile"
                typ = child_text(dep, "type") or "jar"
                cls = child_text(dep, "classifier") or None
                optional = child_text(dep, "optional") == "true"
                if not g or not a:
                    continue
                pom.deps.append({
                    "group": g, "artifact": a, "version": v,
                    "scope": scope, "type": typ, "classifier": cls,
                    "optional": optional,
                })
    return pom


def main():
    os.makedirs(REPO_DIR, exist_ok=True)
    queue = deque()
    seen = set()
    missing = []
    downloaded = {"pom": 0, "artifact": 0}

    for r in ROOTS:
        queue.append(r)

    while queue:
        group, artifact, version, typ, classifier = queue.popleft()
        key = (group, artifact, version, typ, classifier)
        if key in seen:
            continue
        seen.add(key)
        coord = f"{group}:{artifact}:{version}"
        if classifier:
            coord += f":{classifier}"
        if typ:
            coord += f"@{typ}"

        ptext, _ = fetch_pom(group, artifact, version)
        if ptext is None:
            missing.append(f"POM {coord}")
            log(f"MISSING POM: {coord}")
            continue
        downloaded["pom"] += 1

        pom = parse_pom(ptext, group, artifact, version, set())

        # Artifact for THIS node: type comes from the dependency that
        # referenced it (typ), or from the pom's packaging.
        if typ is None:
            try:
                root = ET.fromstring(ptext)
                packaging = child_text(root, "packaging") or "jar"
            except Exception:
                packaging = "jar"
            typ = {"jar": "jar", "aar": "aar", "bundle": "jar",
                   "pom": None}.get(packaging, "jar")
        if typ is not None:
            dest = artifact_path(REPO_DIR, group, artifact, version, typ, classifier)
            if os.path.exists(dest) and os.path.getsize(dest) > 0:
                pass
            else:
                rel = (f"{group_path(group)}/{artifact}/{version}/"
                       f"{artifact}-{version}"
                       f"{'-' + classifier if classifier else ''}.{typ}")
                ok = False
                for name, base in REPOS:
                    if CHECK_ONLY:
                        break
                    if curl_get(f"{base}/{rel}", dest):
                        ok = True
                        downloaded["artifact"] += 1
                        break
                if not ok:
                    if CHECK_ONLY:
                        missing.append(f"ARTIFACT {coord}")
                        log(f"MISSING ARTIFACT: {coord}")
                    else:
                        missing.append(f"ARTIFACT {coord}")
                        log(f"MISSING ARTIFACT: {coord} (tried {rel})")
                        # keep going — some artifacts are truly optional

        # Queue transitive deps.
        for d in pom.deps:
            if d["scope"] in SKIP_SCOPES or d["optional"]:
                continue
            g, a = d["group"], d["artifact"]
            v = d["version"]
            if not v:
                mgmt = pom.dep_mgmt.get((g, a))
                if mgmt and mgmt.get("version"):
                    v = resolve_props(mgmt["version"], pom.props)
            if not v:
                log(f"  WARN: no version for {g}:{a} (via {coord})")
                continue
            v = resolve_props(v, pom.props) or v
            if "${" in v:
                log(f"  WARN: unresolved version {v} for {g}:{a} (via {coord})")
                continue
            if v.startswith("[") or v.startswith("("):
                v = resolve_range(v, g, a)
            # type/classifier: explicit dep wins, else depManagement.
            typ2 = d["type"] if d["type"] != "jar" else None
            cls2 = d["classifier"]
            mgmt = pom.dep_mgmt.get((g, a))
            if typ2 is None and mgmt and mgmt.get("type") not in (None, "jar"):
                typ2 = mgmt["type"]
            if cls2 is None and mgmt:
                cls2 = mgmt.get("classifier")
            # Only queue jar/aar (and pom-typed = metadata only, skip file).
            if d["type"] == "pom":
                typ2 = None
                # still need its POM for mgmt — queue with typ None and let
                # the loop fetch the POM; artifact download skipped.
                queue.append((g, a, v, None, None))
                # mark artifact-skip via a separate marker: use typ "pom"
                continue
            queue.append((g, a, v, typ2, cls2))

    log(f"\nDone. POMs: {downloaded['pom']}, artifacts: {downloaded['artifact']}, "
        f"missing: {len(missing)}")
    for m in missing:
        log(f"  MISSING: {m}")

    # aapt2 special case: Google Maven publishes ONLY the platform jar
    # (aapt2-<ver>-linux.jar) + POM — no plain .jar. Gradle's POM-only
    # resolution asks for the plain jar, so copy the linux binary there.
    aapt2_dir = os.path.join(
        REPO_DIR, "com", "android", "tools", "build", "aapt2", "8.5.2-11315950")
    linux_jar = os.path.join(aapt2_dir, "aapt2-8.5.2-11315950-linux.jar")
    plain_jar = os.path.join(aapt2_dir, "aapt2-8.5.2-11315950.jar")
    if os.path.exists(linux_jar) and not os.path.exists(plain_jar):
        import shutil
        shutil.copyfile(linux_jar, plain_jar)
        log("aapt2: copied -linux.jar to plain .jar")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
