"""ES256 签名与 DPoP（纯 Python，无第三方依赖）

为什么要自己写：CodeArts 换 token 的接口（华为 STS）要求 DPoP 头，
也就是一个 ES256（P-256）签名的 JWT，公钥以 JWK 形式塞在头部。
Chaquopy 里的 Python 默认没有 cryptography / ecdsa，而为了这一个签名
往 APK 里塞 10MB 的轮子不值得 —— 曲线运算本身只有几十行。

实现范围：
  · secp256r1（P-256）的点加 / 倍乘
  · 密钥生成（随机 d，Q = dG）
  · ECDSA 签名，输出 JWS 要的 raw r||s（各 32 字节）
  · 组装 DPoP JWS：header{alg,typ,jwk} . payload{htm,htu,iat,jti} . sig

安全边界说清楚：这里没有做常数时间、没有做侧信道防护。
它只用来给本机一次性的登录换取签一个 proof，不用于长期密钥保护。
"""

import base64
import hashlib
import secrets
import time

# ---- secp256r1 参数（FIPS 186-4 / SEC 2）
_P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
_A = _P - 3
_B = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
_GX = 0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296
_GY = 0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5
_N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551

_G = (_GX, _GY)


def _inv(a, m):
    return pow(a, m - 2, m)


def _add(p, q):
    if p is None:
        return q
    if q is None:
        return p
    x1, y1 = p
    x2, y2 = q
    if x1 == x2:
        if (y1 + y2) % _P == 0:
            return None
        lam = (3 * x1 * x1 + _A) * _inv(2 * y1, _P) % _P
    else:
        lam = (y2 - y1) * _inv(x2 - x1, _P) % _P
    x3 = (lam * lam - x1 - x2) % _P
    y3 = (lam * (x1 - x3) - y1) % _P
    return (x3, y3)


def _mul(k, p):
    r = None
    while k:
        if k & 1:
            r = _add(r, p)
        p = _add(p, p)
        k >>= 1
    return r


def _b64u(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def generate_key():
    """返回 (d, Q)。d 是私钥整数，Q 是公钥点。"""
    while True:
        d = secrets.randbelow(_N - 1) + 1
        q = _mul(d, _G)
        if q is not None:
            return d, q


def _int_to_32(n):
    return n.to_bytes(32, "big")


def public_jwk(q):
    """公钥 JWK（DPoP 头部要带这个）。"""
    return {
        "kty": "EC",
        "crv": "P-256",
        "x": _b64u(_int_to_32(q[0])),
        "y": _b64u(_int_to_32(q[1])),
    }


def private_jwk(d, q):
    j = public_jwk(q)
    j["d"] = _b64u(_int_to_32(d))
    return j


def sign_raw(d, msg):
    """ECDSA-SHA256 签名，返回 JWS 用的 raw r||s（64 字节）。"""
    z = int.from_bytes(hashlib.sha256(msg).digest(), "big")
    while True:
        k = secrets.randbelow(_N - 1) + 1
        r_pt = _mul(k, _G)
        if r_pt is None:
            continue
        r = r_pt[0] % _N
        if r == 0:
            continue
        s = (_inv(k, _N) * (z + r * d)) % _N
        if s == 0:
            continue
        # 低 s 规范化（避免 malleability，也是多数实现的习惯）
        if s > _N // 2:
            s = _N - s
        return _int_to_32(r) + _int_to_32(s)


def dpop_jws(d, q, htm, htu):
    """组装 DPoP proof：header.payload.signature。"""
    header = {"alg": "ES256", "typ": "dpop+jwt", "jwk": public_jwk(q)}
    payload = {
        "htm": htm,
        "htu": htu,
        "iat": int(time.time()),
        # jti 只要唯一，不重复即可
        "jti": secrets.token_hex(16),
    }
    import json as _json
    h = _b64u(_json.dumps(header, separators=(",", ":"), sort_keys=True).encode())
    p = _b64u(_json.dumps(payload, separators=(",", ":"), sort_keys=True).encode())
    signing_input = (h + "." + p).encode()
    sig = sign_raw(d, signing_input)
    return h + "." + p + "." + _b64u(sig)
