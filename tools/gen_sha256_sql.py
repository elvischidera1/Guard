"""Generates the SHA-256 CTE of src/main/resources/com/statsig/androidsdk/sql/01_hashing.sql.

Prints the WITH RECURSIVE ... sha256_state part of the sha256_output view. XOR is written
arithmetically because SQLite has no XOR operator. The get_value block (02_values.sql) carries a
copy of the view's CTEs over :name instead of hash_input: keep the two in sync.
"""
K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
     0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
     0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
     0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
     0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
     0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
     0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
     0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2]
K_VALUES = ','.join(f'({i},{k})' for i, k in enumerate(K))
M = "4294967295"
def rotr(x, n): return f"((({x} >> {n}) | ({x} << {32-n})) & {M})"
def shr(x, n): return f"({x} >> {n})"
def xor3(x, y, z):
    # 32-bit XOR of three values without an XOR operator: x^y^z = x+y+z-2(xy+xz+yz)+4xyz (bitwise products)
    return f"({x} + {y} + {z} - 2 * (({x}) & ({y})) - 2 * (({x}) & ({z})) - 2 * (({y}) & ({z})) + 4 * (({x}) & ({y}) & ({z})))"
def S0(a): return xor3(rotr(a,2), rotr(a,13), rotr(a,22))
def S1(e): return xor3(rotr(e,6), rotr(e,11), rotr(e,25))
def s0(w): return xor3(rotr(w,7), rotr(w,18), shr(w,3))
def s1(w): return xor3(rotr(w,17), rotr(w,19), shr(w,10))
ch = f"((e & f) | ((~e & {M}) & g))"
maj = "((a & b) | (a & c) | (b & c))"
T1 = f"(h + {S1('e')} + {ch} + k.k + w15)"
T2 = f"({S0('a')} + {maj})"
nextw = f"(({s1('w14')} + w9 + {s0('w1')} + w0) & {M})"
H = ['h0','h1','h2','h3','h4','h5','h6','h7']
S = ['a','b','c','d','e','f','g','h']
init = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]

def word(bexpr, texpr):
    # 8 hex digits of the padded message -> 32-bit integer
    pos = f"(({bexpr}) * 128 + ({texpr}) * 8)"
    terms = [f"((instr('0123456789ABCDEF', substr(m.hex, {pos} + {i+1}, 1)) - 1) << {28-4*i})" for i in range(8)]
    return "(" + " | ".join(terms) + ")"

cols = "input, blk, t, nblocks, " + ", ".join(H) + ", " + ", ".join(S) + ", " + ", ".join(f"w{i}" for i in range(16))
seed_state = ", ".join(str(v) for v in init)
seed = f"SELECT m.input, 0, 0, m.nblocks, {seed_state}, {seed_state}, " + ", ".join("0" for _ in range(15)) + f", {word('0','0')} FROM sha256_msg m"
# Recursive step. Rows t = 0..63 run a round; row t = 64 folds the block into h0..h7 and starts
# the next block.
def c(round_expr, fin_expr): return f"CASE WHEN s.t < 64 THEN {round_expr} ELSE {fin_expr} END"
step = []
step.append("s.input")
step.append("CASE WHEN s.t < 64 THEN s.blk ELSE s.blk + 1 END")
step.append("CASE WHEN s.t < 64 THEN s.t + 1 ELSE 0 END")
step.append("s.nblocks")
for i, hname in enumerate(H):
    step.append(c(f"s.{hname}", f"(s.{hname} + s.{S[i]}) & {M}"))
round_state = {
    'a': f"({T1} + {T2}) & {M}", 'b': "a", 'c': "b", 'd': "c",
    'e': f"(d + {T1}) & {M}", 'f': "e", 'g': "f", 'h': "g",
}
for i, sname in enumerate(S):
    step.append(c(round_state[sname], f"(s.{H[i]} + s.{sname}) & {M}"))
# window: during rounds shift in W[t+1]; at t=64 load word 0 of the next block
for i in range(15):
    step.append(c(f"w{i+1}", "0"))
wnext = f"CASE WHEN s.t < 15 THEN {word('s.blk', 's.t + 1')} ELSE {nextw} END"
step.append(c(wnext, f"CASE WHEN s.blk + 1 < s.nblocks THEN {word('s.blk + 1', '0')} ELSE 0 END"))
step_sql = ",\n      ".join(step)
# unqualified names inside expressions refer to s.* columns
sql = f"""WITH RECURSIVE
  sha256_msg(input, hex, nblocks) AS (
    SELECT input,
      hex(CAST(input AS BLOB)) || '80'
        || hex(zeroblob(((55 - length(CAST(input AS BLOB))) % 64 + 64) % 64))
        || printf('%016X', length(CAST(input AS BLOB)) * 8),
      (length(CAST(input AS BLOB)) + 8) / 64 + 1
    FROM sha256_input
  ),
  sha256_k(t, k) AS (VALUES {K_VALUES}),
  sha256_state({cols}) AS (
    {seed}
    UNION ALL
    SELECT
      {step_sql}
    FROM sha256_state s
      JOIN sha256_msg m ON m.input = s.input
      LEFT JOIN sha256_k k ON k.t = s.t
    WHERE s.blk < s.nblocks
  )
SELECT input, {" || ".join(f"printf('%08x', {h})" for h in H)} AS digest_hex
FROM sha256_state WHERE blk = nblocks"""
# bare column names a..h/w* inside CASE refer to s since m and k don't have them
sql = sql.replace("FROM sha256_input", "FROM hash_input WHERE algo IN ('sha256', 'bucket')")
sql = sql.replace("sha256_msg(input, hex, nblocks)", "sha256_msg(algo, input, hex, nblocks)")
sql = sql.replace("SELECT input,\n      hex(", "SELECT algo, input,\n      hex(", 1)
sql = sql.replace("sha256_state(input,", "sha256_state(algo, input,")
sql = sql.replace("SELECT m.input, 0, 0", "SELECT m.algo, m.input, 0, 0")
sql = sql.replace("SELECT\n      s.input,", "SELECT\n      s.algo,\n      s.input,")
sql = sql.replace("JOIN sha256_msg m ON m.input = s.input", "JOIN sha256_msg m ON m.input = s.input AND m.algo = s.algo")
print(sql[:sql.index("SELECT input, printf")].rstrip())
