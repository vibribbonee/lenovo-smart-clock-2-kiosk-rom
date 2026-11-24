#!/usr/bin/env python3
"""Pure-Python APK inspection (no Android SDK needed): package, versions, minSdk, native ABIs and the
signing certificate (APK Signature Scheme v2/v3 block, or v1 JAR signature as fallback).

Usage: apkcheck.py <file.apk> [--json]

Note: this reads the certificate the APK *declares*; it does not verify the signature cryptographically.
That's enough to catch a wrong or substituted download early — Android itself verifies the signature
when it scans the APK on the clock and refuses a tampered one.
"""
import hashlib, json, struct, sys, zipfile

sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))
from axml_dump import strings  # noqa: E402

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
V2_ID, V3_ID, V31_ID = 0x7109871A, 0xF05368C0, 0x1B93AD61


def _manifest(z):
    data = z.read("AndroidManifest.xml")
    pos, pool, out = 8, [], {}
    while pos < len(data):
        t, hsz, size = struct.unpack_from("<HHI", data, pos)
        if t == 0x0001:
            pool = strings(data, pos)
        elif t == 0x0102:
            _ns, name, astart, asize, acount = struct.unpack_from("<iiHHH", data, pos + 16)
            tag = pool[name]
            if tag in ("manifest", "uses-sdk"):
                a = pos + 16 + astart
                for i in range(acount):
                    _ans, aname, araw, _s, _r, vtype, vdata = struct.unpack_from("<iiiHBBI", data, a + i * asize)
                    key = pool[aname]
                    val = pool[araw] if araw >= 0 else (pool[vdata] if vtype == 0x03 else vdata)
                    if tag == "manifest" and key in ("package", "versionCode", "versionName"):
                        out[key] = val
                    if tag == "uses-sdk" and key in ("minSdkVersion", "targetSdkVersion"):
                        out[key] = int(val) if not isinstance(val, int) and str(val).isdigit() else val
        pos += size
    return out


def _lp(buf, off):
    n, = struct.unpack_from("<I", buf, off)
    return buf[off + 4:off + 4 + n], off + 4 + n


def _sig_block_certs(path):
    """First signer's certificates from the v3/v3.1/v2 block, or [] if there is none."""
    d = open(path, "rb").read()
    eocd = d.rfind(b"PK\x05\x06")
    if eocd < 0:
        return []
    cd_off, = struct.unpack_from("<I", d, eocd + 16)
    if d[cd_off - 16:cd_off] != APK_SIG_BLOCK_MAGIC:
        return []
    size, = struct.unpack_from("<Q", d, cd_off - 24)
    start = cd_off - size - 8
    pairs, p, end = {}, start + 8, cd_off - 24
    while p < end:
        ln, = struct.unpack_from("<Q", d, p)
        pid, = struct.unpack_from("<I", d, p + 8)
        pairs[pid] = d[p + 12:p + 8 + ln]
        p += 8 + ln
    for pid in (V31_ID, V3_ID, V2_ID):
        if pid not in pairs:
            continue
        signers, _ = _lp(pairs[pid], 0)
        signer, _ = _lp(signers, 0)
        signed, _ = _lp(signer, 0)
        _digests, o = _lp(signed, 0)
        certs_blob, _ = _lp(signed, o)
        certs, q = [], 0
        while q < len(certs_blob):
            c, q = _lp(certs_blob, q)
            certs.append(c)
        return certs
    return []


def _v1_cert(z):
    """Leaf cert from META-INF/*.RSA|EC|DSA (PKCS#7) — fallback for v1-only APKs."""
    for n in z.namelist():
        if n.startswith("META-INF/") and n.rsplit(".", 1)[-1] in ("RSA", "EC", "DSA"):
            blob = z.read(n)
            # The certificate is the first DER SEQUENCE after the "certificates [0]" tag (0xA0).
            i = blob.find(b"\xa0\x82")
            if i >= 0:
                j = i + 4
                if blob[j] == 0x30 and blob[j + 1] == 0x82:
                    ln = struct.unpack(">H", blob[j + 2:j + 4])[0]
                    return blob[j:j + 4 + ln]
    return None


def inspect(path):
    with zipfile.ZipFile(path) as z:
        info = _manifest(z)
        abis = sorted({n.split("/")[1] for n in z.namelist() if n.startswith("lib/") and n.count("/") >= 2})
        certs = _sig_block_certs(path)
        cert = certs[0] if certs else _v1_cert(z)
    info["abis"] = abis
    info["cert_sha256"] = hashlib.sha256(cert).hexdigest() if cert else None
    info["sha256"] = hashlib.sha256(open(path, "rb").read()).hexdigest()
    return info


def check(path, package, cert_sha256=None, need_abi=None, max_min_sdk=None):
    """Returns (ok, info, problems[]) with problems phrased for end users."""
    problems = []
    try:
        info = inspect(path)
    except (zipfile.BadZipFile, KeyError, struct.error) as e:
        return False, {}, [f"not a readable APK ({e.__class__.__name__}). Is it an .apkm/.xapk bundle? Download the plain .apk."]
    if info.get("package") != package:
        problems.append(f"this is '{info.get('package')}', expected '{package}'")
    if cert_sha256 and info.get("cert_sha256") != cert_sha256:
        problems.append("it is not signed by the expected developer certificate (wrong app or modified file)")
    if need_abi and info["abis"] and need_abi not in info["abis"]:
        problems.append(f"it has no {need_abi} code (has: {', '.join(info['abis'])}) — pick the armeabi-v7a variant")
    ms = info.get("minSdkVersion")
    if max_min_sdk and isinstance(ms, int) and ms > max_min_sdk:
        problems.append(f"it needs Android API {ms}; the clock runs Android 10 (API {max_min_sdk}) — pick an older version")
    return not problems, info, problems


if __name__ == "__main__":
    i = inspect(sys.argv[1])
    print(json.dumps(i, indent=2) if "--json" in sys.argv else i)
