#!/usr/bin/env python3
"""Read-only AVB (vbmeta) inspector: prints flags, rollback index and descriptors.
Usage: avb_info.py <vbmeta.img | image-with-AVB-footer> ..."""
import struct, sys
ALGS = {0:'NONE',1:'SHA256_RSA2048',2:'SHA256_RSA4096',3:'SHA256_RSA8192',4:'SHA512_RSA2048',5:'SHA512_RSA4096',6:'SHA512_RSA8192'}
def parse(name, data):
    assert data[:4] == b'AVB0', name
    auth, aux = struct.unpack_from('>QQ', data, 12); alg, = struct.unpack_from('>I', data, 28)
    doff, dsz = struct.unpack_from('>QQ', data, 96); rb, = struct.unpack_from('>Q', data, 112); flags, rbl = struct.unpack_from('>II', data, 120)
    rel = data[128:176].split(b'\0')[0].decode()
    print(f"{name}: alg={ALGS.get(alg, alg)} flags={flags} rollback_index={rb} location={rbl} ({rel})")
    p = 256 + auth + doff; end = p + dsz
    while p < end:
        tag, n = struct.unpack_from('>QQ', data, p); b = p + 16
        if tag == 4:
            loc, nl, kl, fl = struct.unpack_from('>IIII', data, b)
            print(f"   chain    -> {data[b+76:b+76+nl].decode()} (rollback location {loc})")
        elif tag == 2:
            sz, = struct.unpack_from('>Q', data, b); ha = data[b+8:b+40].split(b'\0')[0].decode()
            nl, sl, dl, fl = struct.unpack_from('>IIII', data, b+40)
            print(f"   hash     {data[b+116:b+116+nl].decode()} size={sz} {ha} flags={fl}")
        elif tag == 1:
            isz, = struct.unpack_from('>Q', data, b+4); fs, = struct.unpack_from('>Q', data, b+48)
            ha = data[b+56:b+88].split(b'\0')[0].decode(); nl, sl, rl, fl = struct.unpack_from('>IIII', data, b+88)
            print(f"   hashtree {data[b+164:b+164+nl].decode()} image={isz} fec={'yes' if fs else 'no'} {ha} flags={fl}")
        elif tag == 0:
            kl, vl = struct.unpack_from('>QQ', data, b)
            print(f"   prop     {data[b+16:b+16+kl].decode()}={data[b+17+kl:b+17+kl+vl].decode(errors='replace')}")
        elif tag == 3:
            fl, ln = struct.unpack_from('>II', data, b); print(f"   cmdline  {data[b+8:b+8+ln].decode()[:150]}")
        p = b + n
for f in sys.argv[1:]:
    d = open(f, 'rb').read()
    if d[:4] == b'AVB0':
        parse(f, d)
    elif d[-64:][:4] == b'AVBf':
        osz, vo, vs = struct.unpack_from('>QQQ', d[-64:], 12); parse(f + ' [footer]', d[vo:vo+vs])
    else:
        print(f"{f}: no AVB data")
