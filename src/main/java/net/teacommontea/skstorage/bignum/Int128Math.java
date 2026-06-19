package net.teacommontea.skstorage.bignum;

final class Int128Math {

    private Int128Math() {}

    static final long MIN_HI = Long.MIN_VALUE;
    static final long MIN_LO = 0L;
    static final long MAX_HI = Long.MAX_VALUE;
    static final long MAX_LO = -1L;

    static int compareUnsigned(long a, long b) {
        return Long.compareUnsigned(a, b);
    }

    static int compare(long aHi, long aLo, long bHi, long bLo) {
        int c = Long.compare(aHi, bHi);
        if (c != 0) return c;
        return Long.compareUnsigned(aLo, bLo);
    }

    static long[] add(long aHi, long aLo, long bHi, long bLo) {
        long lo = aLo + bLo;

        long carry = (Long.compareUnsigned(lo, aLo) < 0) ? 1L : 0L;
        long hi = aHi + bHi + carry;
        return new long[]{hi, lo};
    }

    static long[] subtract(long aHi, long aLo, long bHi, long bLo) {
        long lo = aLo - bLo;
        long borrow = (Long.compareUnsigned(aLo, bLo) < 0) ? 1L : 0L;
        long hi = aHi - bHi - borrow;
        return new long[]{hi, lo};
    }

    static long[] negate(long hi, long lo) {

        long nlo = ~lo + 1L;
        long carry = (nlo == 0L) ? 1L : 0L;
        long nhi = ~hi + carry;
        return new long[]{nhi, nlo};
    }

    static long[] abs(long hi, long lo) {
        if (hi >= 0) return new long[]{hi, lo};
        return negate(hi, lo);
    }

    static long[] multiply(long aHi, long aLo, long bHi, long bLo) {

        long lo = aLo * bLo;
        long hi = Math.unsignedMultiplyHigh(aLo, bLo)
                + aHi * bLo
                + aLo * bHi;
        return new long[]{hi, lo};
    }

    static long[] divide(long aHi, long aLo, long bHi, long bLo) {
        if (bHi == 0 && bLo == 0) throw new ArithmeticException("/ by zero");

        boolean negA = aHi < 0;
        boolean negB = bHi < 0;
        long[] a = negA ? negate(aHi, aLo) : new long[]{aHi, aLo};
        long[] b = negB ? negate(bHi, bLo) : new long[]{bHi, bLo};

        long[] q = unsignedDivide(a[0], a[1], b[0], b[1]);

        if (negA ^ negB) {
            return negate(q[0], q[1]);
        }
        return q;
    }

    private static long[] unsignedDivide(long aHi, long aLo, long bHi, long bLo) {

        if (Long.compare(bHi, aHi) > 0 ||
            (bHi == aHi && Long.compareUnsigned(bLo, aLo) > 0)) {
            return new long[]{0L, 0L};
        }

        if (bHi == 0 && aHi == 0) {
            return new long[]{0L, Long.divideUnsigned(aLo, bLo)};
        }

        long qHi = 0L, qLo = 0L;
        long rHi = 0L, rLo = 0L;
        for (int i = 127; i >= 0; i--) {

            rHi = (rHi << 1) | (rLo >>> 63);
            rLo <<= 1;

            long bit = (i >= 64) ? ((aHi >>> (i - 64)) & 1L) : ((aLo >>> i) & 1L);
            rLo |= bit;

            if (Long.compare(rHi, bHi) > 0 ||
                (rHi == bHi && Long.compareUnsigned(rLo, bLo) >= 0)) {
                long[] sub = subtract(rHi, rLo, bHi, bLo);
                rHi = sub[0]; rLo = sub[1];
                if (i >= 64) {
                    qHi |= (1L << (i - 64));
                } else {
                    qLo |= (1L << i);
                }
            }
        }
        return new long[]{qHi, qLo};
    }

    static long[] mod(long aHi, long aLo, long bHi, long bLo) {
        long[] q = divide(aHi, aLo, bHi, bLo);
        long[] qb = multiply(q[0], q[1], bHi, bLo);
        return subtract(aHi, aLo, qb[0], qb[1]);
    }

    static long[] fromLong(long v) {
        return new long[]{v < 0 ? -1L : 0L, v};
    }

    static boolean fitsInLong(long hi, long lo) {

        return (lo < 0) ? (hi == -1L) : (hi == 0L);
    }

    static String toDecimalString(long hi, long lo) {
        if (hi == 0 && lo == 0) return "0";
        boolean negative = hi < 0;
        long[] v = negative ? negate(hi, lo) : new long[]{hi, lo};

        StringBuilder tail = new StringBuilder();
        long[] ten18 = new long[]{0L, 1_000_000_000_000_000_000L};
        while (v[0] != 0 || Long.compareUnsigned(v[1], 1_000_000_000_000_000_000L) >= 0) {
            long[] q = unsignedDivide(v[0], v[1], ten18[0], ten18[1]);
            long[] qb = multiply(q[0], q[1], ten18[0], ten18[1]);
            long[] r = subtract(v[0], v[1], qb[0], qb[1]);

            String chunk = Long.toUnsignedString(r[1]);

            tail.insert(0, chunk);
            for (int i = chunk.length(); i < 18; i++) tail.insert(0, '0');
            v = q;
        }

        StringBuilder out = new StringBuilder();
        if (negative) out.append('-');
        out.append(Long.toUnsignedString(v[1]));
        out.append(tail);
        return out.toString();
    }

    static long[] parseDecimal(String s) {
        if (s == null) throw new NumberFormatException("null");
        s = s.trim();
        if (s.isEmpty()) throw new NumberFormatException("empty");
        boolean negative = false;
        int idx = 0;
        char c0 = s.charAt(0);
        if (c0 == '+' || c0 == '-') {
            negative = (c0 == '-');
            idx = 1;
        }
        if (idx >= s.length()) throw new NumberFormatException("no digits: " + s);
        long[] v = new long[]{0L, 0L};
        long[] ten = new long[]{0L, 10L};
        while (idx < s.length()) {
            char c = s.charAt(idx++);
            if (c == '_') continue;
            if (c < '0' || c > '9') throw new NumberFormatException("bad char '" + c + "' in: " + s);
            long[] m = multiply(v[0], v[1], ten[0], ten[1]);
            long[] a = add(m[0], m[1], 0L, c - '0');
            v = a;
        }
        if (negative) v = negate(v[0], v[1]);
        return v;
    }
}
