"""Interpreteur 68000 minimal : juste ce qu'il faut pour executer l'intro.

On ne transcrit pas le moteur de rendu, on le FAIT TOURNER. Transcrire une dizaine
de routines a la main serait fragile ; executer le code d'origine ne l'est pas.

Le decodage vient de Capstone ; les operandes sont relues depuis le texte, Capstone
ne remplissant pas les adresses absolues dans ses structures.
"""
import re
from capstone import *

MASK = {1: 0xFF, 2: 0xFFFF, 4: 0xFFFFFFFF}
SIGN = {1: 0x80, 2: 0x8000, 4: 0x80000000}


def sext(v, sz):
    v &= MASK[sz]
    return v - (1 << (sz * 8)) if v & SIGN[sz] else v


class Mem:
    """Memoire par regions de 256 Mo : une par hunk, plus la pile et les registres materiels."""

    CUSTOM = 0xDFF000

    def __init__(self):
        self.reg = {}
        self.custom = {}
        self.vpos = 0

    def add(self, base, data):
        self.reg[base >> 28] = (base, data)

    def _find(self, a):
        r = self.reg.get(a >> 28)
        if r is None:
            raise MemoryError("adresse hors zone : 0x%08X" % a)
        return r[1], a - r[0]

    def rb(self, a):
        if (a & 0xFFF000) == self.CUSTOM and a >> 28 == 0xD:
            return self.cread(a) >> 8
        b, o = self._find(a)
        return b[o]

    def wb(self, a, v):
        if a >> 28 == 0xD:
            return
        b, o = self._find(a)
        b[o] = v & 0xFF

    def rw(self, a):
        if a >> 28 == 0xD:
            return self.cread(a)
        b, o = self._find(a)
        return (b[o] << 8) | b[o + 1]

    def ww(self, a, v):
        if a >> 28 == 0xD:
            return self.cwrite(a, v)
        b, o = self._find(a)
        b[o] = (v >> 8) & 0xFF
        b[o + 1] = v & 0xFF

    def rl(self, a):
        if a >> 28 == 0xD:
            return (self.cread(a) << 16) | self.cread(a + 2)
        b, o = self._find(a)
        return (b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]

    def wl(self, a, v):
        if a >> 28 == 0xD:
            self.cwrite(a, (v >> 16) & 0xFFFF)
            return self.cwrite(a + 2, v & 0xFFFF)
        b, o = self._find(a)
        b[o] = (v >> 24) & 0xFF
        b[o + 1] = (v >> 16) & 0xFF
        b[o + 2] = (v >> 8) & 0xFF
        b[o + 3] = v & 0xFF

    def read(self, a, sz):
        return self.rb(a) if sz == 1 else self.rw(a) if sz == 2 else self.rl(a)

    def write(self, a, v, sz):
        (self.wb if sz == 1 else self.ww if sz == 2 else self.wl)(a, v)

    # --- registres materiels : on n'emule que ce dont le code depend ---
    def cread(self, a):
        off = a & 0xFFF
        if off == 0x004:                  # VPOSR : le faisceau avance tout seul
            self.vpos = (self.vpos + 1) & 0x1FF
            return 0x8000 | ((self.vpos >> 8) & 1)
        if off == 0x006:                  # VHPOSR
            return ((self.vpos & 0xFF) << 8) | 0x40
        if off == 0x002:                  # DMACONR : blitter jamais occupe
            return 0x0000
        return self.custom.get(off, 0)

    def cwrite(self, a, v):
        self.custom[a & 0xFFF] = v


class CPU:
    def __init__(self, mem, code_base, code):
        self.m = mem
        self.d = [0] * 8
        self.a = [0] * 8
        self.pc = 0
        self.X = self.N = self.Z = self.V = self.C = 0
        self.code_base = code_base
        self.code = code
        self.md = Cs(CS_ARCH_M68K, CS_MODE_BIG_ENDIAN | CS_MODE_M68K_000)
        self.cache = {}
        self.steps = 0
        self.trace = None

    # ------------------------------------------------------------------ decodage
    def fetch(self, pc):
        ins = self.cache.get(pc)
        if ins is None:
            off = pc - self.code_base
            i = next(self.md.disasm(self.code[off:off + 16], pc), None)
            if i is None:
                raise RuntimeError("instruction indecodable en 0x%X" % pc)
            mn = i.mnemonic.split('.')
            sz = {'b': 1, 'w': 2, 'l': 4, 's': 2}.get(mn[1] if len(mn) > 1 else '', None)
            ops = [o.strip() for o in re.split(r',\s*(?![^()]*\))', i.op_str)] if i.op_str else []
            ins = (mn[0], sz, ops, i.size, i.address + i.size)
            self.cache[pc] = ins
        return ins

    # ------------------------------------------------------------------ operandes
    RE_ABS = re.compile(r'^(-?)\$([0-9a-f]+)\.([lw])$')
    RE_DISP = re.compile(r'^(-?)\$?([0-9a-f]*)\((a\d)\)$')
    RE_IDX = re.compile(r'^(-?)\$?([0-9a-f]*)\((a\d),\s*([da]\d)\.([wl])(?:\s*\*\s*(\d))?\)$')
    RE_IND = re.compile(r'^\((a\d)\)$')
    RE_POST = re.compile(r'^\((a\d)\)\+$')
    RE_PRE = re.compile(r'^-\((a\d)\)$')

    def ea(self, s, sz):
        """Renvoie ('reg', kind, n) ou ('mem', adresse). Applique les (pre/post)increments."""
        if re.fullmatch(r'd\d', s):
            return ('d', int(s[1]))
        if re.fullmatch(r'a\d', s):
            return ('a', int(s[1]))
        if s.startswith('#'):
            t = s[1:]
            v = int(t[1:], 16) if t.startswith('$') else int(t, 16) if t.startswith('0x') else int(t)
            return ('i', v & 0xFFFFFFFF)
        m = self.RE_IND.fullmatch(s)
        if m:
            return ('m', self.a[int(m.group(1)[1])])
        m = self.RE_POST.fullmatch(s)
        if m:
            n = int(m.group(1)[1])
            at = self.a[n]
            step = sz if sz else 4
            if n == 7 and step == 1:
                step = 2
            self.a[n] = (at + step) & 0xFFFFFFFF
            return ('m', at)
        m = self.RE_PRE.fullmatch(s)
        if m:
            n = int(m.group(1)[1])
            step = sz if sz else 4
            if n == 7 and step == 1:
                step = 2
            self.a[n] = (self.a[n] - step) & 0xFFFFFFFF
            return ('m', self.a[n])
        m = self.RE_ABS.fullmatch(s)
        if m:
            v = int(m.group(2), 16)
            if m.group(3) == 'w':
                v = sext(v, 2)
            if m.group(1):
                v = -v
            return ('m', v & 0xFFFFFFFF)
        m = self.RE_IDX.fullmatch(s)
        if m:
            disp = int(m.group(2), 16) if m.group(2) else 0
            if m.group(1):
                disp = -disp
            base = self.a[int(m.group(3)[1])]
            r = m.group(4)
            idx = self.d[int(r[1])] if r[0] == 'd' else self.a[int(r[1])]
            idx = sext(idx, 2) if m.group(5) == 'w' else sext(idx, 4)
            scale = int(m.group(6)) if m.group(6) else 1
            return ('m', (base + disp + idx * scale) & 0xFFFFFFFF)
        m = self.RE_DISP.fullmatch(s)
        if m:
            disp = int(m.group(2), 16) if m.group(2) else 0
            if m.group(1):
                disp = -disp
            return ('m', (self.a[int(m.group(3)[1])] + disp) & 0xFFFFFFFF)
        m = re.fullmatch(r'\$([0-9a-f]+)\(pc\)', s)          # Capstone donne la cible RESOLUE
        if m:
            return ('m', int(m.group(1), 16))
        m = re.fullmatch(r'\$([0-9a-f]+)\(pc,\s*([da]\d)\.([wl])(?:\s*\*\s*(\d))?\)', s)
        if m:
            r = m.group(2)
            idx = self.d[int(r[1])] if r[0] == 'd' else self.a[int(r[1])]
            idx = sext(idx, 2 if m.group(3) == 'w' else 4)
            sc = int(m.group(4)) if m.group(4) else 1
            return ('m', (int(m.group(1), 16) + idx * sc) & 0xFFFFFFFF)
        if re.fullmatch(r'\$[0-9a-f]+', s):                  # cible de branchement
            return ('i', int(s[1:], 16))
        raise RuntimeError("operande non gere : %r" % s)

    def get(self, e, sz):
        k, v = e
        if k == 'd':
            return self.d[v] & MASK[sz]
        if k == 'a':
            return self.a[v] & MASK[sz]
        if k == 'i':
            return v & MASK[sz]
        return self.m.read(v, sz)

    def put(self, e, val, sz):
        k, v = e
        if k == 'd':
            self.d[v] = (self.d[v] & ~MASK[sz] | (val & MASK[sz])) & 0xFFFFFFFF
        elif k == 'a':
            self.a[v] = (sext(val, sz) if sz != 4 else val) & 0xFFFFFFFF
        else:
            self.m.write(v, val, sz)

    # ------------------------------------------------------------------ drapeaux
    def nz(self, v, sz):
        v &= MASK[sz]
        self.N = 1 if v & SIGN[sz] else 0
        self.Z = 1 if v == 0 else 0
        self.V = self.C = 0

    def cond(self, c):
        Z, N, V, C = self.Z, self.N, self.V, self.C
        return {'t': 1, 'f': 0, 'hi': (not C) and (not Z), 'ls': C or Z, 'cc': not C, 'hs': not C,
                'cs': C, 'lo': C, 'ne': not Z, 'eq': Z, 'vc': not V, 'vs': V,
                'pl': not N, 'mi': N, 'ge': N == V, 'lt': N != V,
                'gt': (N == V) and not Z, 'le': (N != V) or Z, 'ra': 1}[c]

    # ------------------------------------------------------------------ liste de registres
    @staticmethod
    def reglist(s):
        out = []
        for part in s.split('/'):
            if '-' in part:
                a, b = part.split('-')
                k = a[0]
                for n in range(int(a[1]), int(b[1]) + 1):
                    out.append((k, n))
            else:
                out.append((part[0], int(part[1])))
        return out

    SCC = ('hi', 'ls', 'cc', 'cs', 'ne', 'eq', 'vc', 'vs', 'pl', 'mi', 'ge', 'lt', 'gt', 'le')

    # ------------------------------------------------------------------ execution
    def step(self):
        op, sz, ops, _, nxt = self.fetch(self.pc)
        self.pc = nxt
        self.steps += 1
        s = sz or 2

        if op in ('move', 'movea'):
            v = self.get(self.ea(ops[0], s), s)
            t = self.ea(ops[1], s)
            if op == 'move':
                self.nz(v, s)
                self.put(t, v, s)
            else:
                self.put(t, sext(v, s) & 0xFFFFFFFF, 4)
            return
        if op == 'moveq':
            v = sext(self.ea(ops[0], 4)[1], 1) & 0xFFFFFFFF
            self.d[int(ops[1][1])] = v
            self.nz(v, 4)
            return
        if op == 'lea':
            self.a[int(ops[1][1])] = self.ea(ops[0], 4)[1]
            return
        if op == 'pea':
            v = self.ea(ops[0], 4)[1]
            self.a[7] = (self.a[7] - 4) & 0xFFFFFFFF
            self.m.wl(self.a[7], v)
            return
        if op == 'clr':
            self.put(self.ea(ops[0], s), 0, s)
            self.N = self.V = self.C = 0
            self.Z = 1
            return
        if op == 'st' or op == 'sf' or (op[0] == 's' and op[1:] in self.SCC):
            c = 't' if op == 'st' else 'f' if op == 'sf' else op[1:]
            self.put(self.ea(ops[0], 1), 0xFF if self.cond(c) else 0, 1)
            return
        if op == 'swap':
            n = int(ops[0][1])
            v = ((self.d[n] << 16) | (self.d[n] >> 16)) & 0xFFFFFFFF
            self.d[n] = v
            self.nz(v, 4)
            return
        if op == 'ext':
            n = int(ops[0][1])
            if s == 2:
                v = (self.d[n] & 0xFFFF0000) | (sext(self.d[n], 1) & 0xFFFF)
            else:
                v = sext(self.d[n], 2) & 0xFFFFFFFF
            self.d[n] = v
            self.nz(v, s)
            return
        if op == 'exg':
            x, y = ops
            gx = self.d if x[0] == 'd' else self.a
            gy = self.d if y[0] == 'd' else self.a
            i, j = int(x[1]), int(y[1])
            gx[i], gy[j] = gy[j], gx[i]
            return

        if op in ('add', 'addi', 'addq', 'adda', 'sub', 'subi', 'subq', 'suba',
                  'cmp', 'cmpi', 'cmpa'):
            addr = op in ('adda', 'suba', 'cmpa')
            w = 4 if addr else s
            src = self.get(self.ea(ops[0], s), s)
            if addr:
                src = sext(src, s) & 0xFFFFFFFF
            te = self.ea(ops[1], w)
            dst = self.get(te, w)
            if op[0] == 'a':
                r = dst + src
                if not addr:
                    self.C = self.X = 1 if r > MASK[w] else 0
                    self.V = 1 if (~(dst ^ src) & (dst ^ r)) & SIGN[w] else 0
            else:
                r = dst - src
                if not addr:
                    self.C = 1 if (src & MASK[w]) > (dst & MASK[w]) else 0
                    if op[0] == 's':
                        self.X = self.C
                    self.V = 1 if ((dst ^ src) & (dst ^ r)) & SIGN[w] else 0
            r &= MASK[w]
            if not addr:
                self.N = 1 if r & SIGN[w] else 0
                self.Z = 1 if r == 0 else 0
            if op[0] != 'c':
                self.put(te, r, w)
            return

        if op in ('and', 'andi', 'or', 'ori', 'eor', 'eori'):
            src = self.get(self.ea(ops[0], s), s)
            te = self.ea(ops[1], s)
            dst = self.get(te, s)
            r = (dst & src) if op[0] == 'a' else (dst | src) if op[0] == 'o' else (dst ^ src)
            r &= MASK[s]
            self.nz(r, s)
            self.put(te, r, s)
            return

        if op in ('addx', 'subx'):
            src = self.get(self.ea(ops[0], s), s)
            te = self.ea(ops[1], s)
            dst = self.get(te, s)
            r = dst + src + self.X if op == 'addx' else dst - src - self.X
            self.C = self.X = 1 if (r > MASK[s] or r < 0) else 0
            r &= MASK[s]
            self.N = 1 if r & SIGN[s] else 0
            if r:
                self.Z = 0
            self.put(te, r, s)
            return

        if op in ('neg', 'negx', 'not'):
            e = self.ea(ops[0], s)
            v = self.get(e, s)
            if op == 'not':
                r = ~v & MASK[s]
                self.C = self.V = 0
            else:
                r = (-v - (self.X if op == 'negx' else 0)) & MASK[s]
                self.C = self.X = 1 if v else 0
                self.V = 0
            self.N = 1 if r & SIGN[s] else 0
            self.Z = 1 if r == 0 else 0
            self.put(e, r, s)
            return

        if op == 'tst':
            self.nz(self.get(self.ea(ops[0], s), s), s)
            return

        if op in ('lsl', 'lsr', 'asl', 'asr', 'rol', 'ror', 'roxl', 'roxr'):
            if len(ops) == 1:
                e = self.ea(ops[0], 2)
                cnt, w = 1, 2
            else:
                ce = self.ea(ops[0], 4)
                cnt = (self.d[ce[1]] & 63) if ce[0] == 'd' else ce[1]
                e = self.ea(ops[1], s)
                w = s
            v = self.get(e, w)
            bits = w * 8
            for _ in range(cnt):
                if op in ('lsl', 'asl'):
                    self.C = self.X = (v >> (bits - 1)) & 1
                    v = (v << 1) & MASK[w]
                elif op == 'lsr':
                    self.C = self.X = v & 1
                    v >>= 1
                elif op == 'asr':
                    self.C = self.X = v & 1
                    v = (v >> 1) | (v & SIGN[w])
                elif op == 'rol':
                    self.C = (v >> (bits - 1)) & 1
                    v = ((v << 1) | self.C) & MASK[w]
                elif op == 'ror':
                    self.C = v & 1
                    v = (v >> 1) | (self.C << (bits - 1))
                elif op == 'roxl':
                    nc = (v >> (bits - 1)) & 1
                    v = ((v << 1) | self.X) & MASK[w]
                    self.C = self.X = nc
                else:
                    nc = v & 1
                    v = (v >> 1) | (self.X << (bits - 1))
                    self.C = self.X = nc
            if cnt == 0:
                self.C = 0
            self.N = 1 if v & SIGN[w] else 0
            self.Z = 1 if v == 0 else 0
            self.V = 0
            self.put(e, v, w)
            return

        if op in ('muls', 'mulu'):
            src = self.get(self.ea(ops[0], 2), 2)
            n = int(ops[1][1])
            dst = self.d[n] & 0xFFFF
            r = (sext(src, 2) * sext(dst, 2)) if op == 'muls' else (src * dst)
            r &= 0xFFFFFFFF
            self.d[n] = r
            self.nz(r, 4)
            return

        if op in ('divs', 'divu'):
            src = self.get(self.ea(ops[0], 2), 2)
            n = int(ops[1][1])
            if src == 0:
                raise ZeroDivisionError("division par zero en 0x%X" % self.pc)
            dst = self.d[n]
            if op == 'divs':
                a, b = sext(dst, 4), sext(src, 2)
                q = abs(a) // abs(b)
                if (a < 0) != (b < 0):
                    q = -q
                rem = a - q * b
                if not (-32768 <= q <= 32767):
                    self.V = 1
                    return
            else:
                q, rem = dst // src, dst % src
                if q > 0xFFFF:
                    self.V = 1
                    return
            self.V = 0
            self.d[n] = ((rem & 0xFFFF) << 16) | (q & 0xFFFF)
            self.nz(q, 2)
            return

        if op in ('btst', 'bset', 'bclr', 'bchg'):
            se = self.ea(ops[0], 4)
            te = self.ea(ops[1], 4 if ops[1][0] == 'd' else 1)
            w = 4 if te[0] == 'd' else 1
            bit = (se[1] if se[0] == 'i' else self.get(se, 4)) % (32 if w == 4 else 8)
            v = self.get(te, w)
            self.Z = 0 if (v >> bit) & 1 else 1
            if op == 'bset':
                v |= 1 << bit
            elif op == 'bclr':
                v &= ~(1 << bit)
            elif op == 'bchg':
                v ^= 1 << bit
            if op != 'btst':
                self.put(te, v, w)
            return

        if op == 'movem':
            if re.fullmatch(r'[da]\d([/-][da]\d)*', ops[0]):            # registres -> memoire
                regs, dst = self.reglist(ops[0]), ops[1]
                if dst.startswith('-('):
                    n = int(dst[3])
                    addr = self.a[n]
                    for k, i in reversed(regs):
                        addr -= s
                        self.m.write(addr, (self.d if k == 'd' else self.a)[i], s)
                    self.a[n] = addr & 0xFFFFFFFF
                else:
                    addr = self.ea(dst, s)[1]
                    for k, i in regs:
                        self.m.write(addr, (self.d if k == 'd' else self.a)[i], s)
                        addr += s
            else:                                                       # memoire -> registres
                regs = self.reglist(ops[1])
                post = ops[0].endswith(')+')
                addr = self.a[int(ops[0][2])] if post else self.ea(ops[0], s)[1]
                for k, i in regs:
                    v = sext(self.m.read(addr, s), s) & 0xFFFFFFFF
                    if k == 'd':
                        self.d[i] = v
                    else:
                        self.a[i] = v
                    addr += s
                if post:
                    self.a[int(ops[0][2])] = addr & 0xFFFFFFFF
            return

        if op in ('bra', 'bsr', 'jmp', 'jsr'):
            tgt = self.ea(ops[0], 4)[1]
            if op in ('bsr', 'jsr'):
                self.a[7] = (self.a[7] - 4) & 0xFFFFFFFF
                self.m.wl(self.a[7], self.pc)
            self.pc = tgt
            return
        if op == 'rts':
            self.pc = self.m.rl(self.a[7])
            self.a[7] = (self.a[7] + 4) & 0xFFFFFFFF
            return
        if op[:2] == 'db':
            c = op[2:]
            if c not in ('ra', 'f') and self.cond(c):
                return
            n = int(ops[0][1])
            v = (self.d[n] - 1) & 0xFFFF
            self.d[n] = (self.d[n] & 0xFFFF0000) | v
            if v != 0xFFFF:
                self.pc = self.ea(ops[1], 4)[1]
            return
        if op[0] == 'b' and op[1:] in self.SCC:
            if self.cond(op[1:]):
                self.pc = self.ea(ops[0], 4)[1]
            return
        if op == 'nop':
            return
        raise RuntimeError("opcode non gere : %s %s (en 0x%X)" % (op, ops, self.pc))

    END = 0x0FFFFFF0

    def call(self, addr, limit=400_000_000):
        """Appelle une routine et rend la main a son rts."""
        self.a[7] = (self.a[7] - 4) & 0xFFFFFFFF
        self.m.wl(self.a[7], self.END)
        self.pc = addr
        while self.pc != self.END:
            if self.steps > limit:
                raise RuntimeError("trop d'instructions (pc=0x%X)" % self.pc)
            self.step()
