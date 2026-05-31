package dev.teacommontea.skstorage.bignum;

import java.util.Objects;

public final class BigNum implements Comparable<BigNum> {

    public static final BigNum ZERO = new BigNum(0L, 0L);
    public static final BigNum ONE = new BigNum(0L, 1L);
    public static final BigNum MIN_VALUE = new BigNum(Long.MIN_VALUE, 0L);
    public static final BigNum MAX_VALUE = new BigNum(Long.MAX_VALUE, -1L);

    private final long hi;
    private final long lo;

    BigNum(long hi, long lo) {
        this.hi = hi;
        this.lo = lo;
    }

    public long hi() { return hi; }
    public long lo() { return lo; }

    public static BigNum of(long v) {
        long[] r = Int128Math.fromLong(v);
        return new BigNum(r[0], r[1]);
    }

    public static BigNum fromNumber(Number n) {
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
            return of(n.longValue());
        }
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new ArithmeticException("Cannot convert " + d + " to BigNum");
        }
        if (d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
            return of((long) d);
        }

        return parse(new java.math.BigDecimal(d).toBigInteger().toString());
    }

    public static BigNum parse(String s) {
        if (s == null) throw new NumberFormatException("null");
        String t = s.trim();
        int e = -1;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == 'e' || c == 'E') { e = i; break; }
        }
        if (e < 0) {
            long[] r = Int128Math.parseDecimal(t);
            return new BigNum(r[0], r[1]);
        }

        String mantissa = t.substring(0, e);
        String expStr = t.substring(e + 1);
        int exp = Integer.parseInt(expStr);
        int dot = mantissa.indexOf('.');
        String digits;
        int afterDot;
        if (dot < 0) {
            digits = mantissa;
            afterDot = 0;
        } else {
            digits = mantissa.substring(0, dot) + mantissa.substring(dot + 1);
            afterDot = mantissa.length() - dot - 1;
        }
        int netExp = exp - afterDot;
        if (netExp < 0) {
            throw new NumberFormatException("Non-integer scientific value: " + s);
        }
        StringBuilder sb = new StringBuilder(digits.length() + netExp);
        sb.append(digits);
        for (int i = 0; i < netExp; i++) sb.append('0');
        long[] r = Int128Math.parseDecimal(sb.toString());
        return new BigNum(r[0], r[1]);
    }

    public BigNum add(BigNum other) {
        long[] r = Int128Math.add(hi, lo, other.hi, other.lo);
        return new BigNum(r[0], r[1]);
    }

    public BigNum subtract(BigNum other) {
        long[] r = Int128Math.subtract(hi, lo, other.hi, other.lo);
        return new BigNum(r[0], r[1]);
    }

    public BigNum multiply(BigNum other) {
        long[] r = Int128Math.multiply(hi, lo, other.hi, other.lo);
        return new BigNum(r[0], r[1]);
    }

    public BigNum divide(BigNum other) {
        long[] r = Int128Math.divide(hi, lo, other.hi, other.lo);
        return new BigNum(r[0], r[1]);
    }

    public BigNum mod(BigNum other) {
        long[] r = Int128Math.mod(hi, lo, other.hi, other.lo);
        return new BigNum(r[0], r[1]);
    }

    public BigNum negate() {
        long[] r = Int128Math.negate(hi, lo);
        return new BigNum(r[0], r[1]);
    }

    public BigNum abs() {
        long[] r = Int128Math.abs(hi, lo);
        return new BigNum(r[0], r[1]);
    }

    public BigNum pow(int exp) {
        if (exp < 0) throw new ArithmeticException("negative exponent not supported on BigNum");
        if (exp == 0) return ONE;
        BigNum base = this;
        BigNum result = ONE;
        int iter = 0;
        while (exp > 0 && iter < 256) {
            if ((exp & 1) == 1) result = result.multiply(base);
            exp >>>= 1;
            if (exp > 0) base = base.multiply(base);
            iter++;
        }
        return result;
    }

    public BigNum isqrt() {
        if (isNegative()) throw new ArithmeticException("sqrt of negative BigNum: " + this);
        if (isZero()) return ZERO;

        BigNum two = of(2L);
        BigNum x = this;
        BigNum prev;
        int iter = 0;
        do {
            prev = x;
            x = x.add(divide(x)).divide(two);
            iter++;
        } while (x.compareTo(prev) < 0 && iter < 200);

        if (x.multiply(x).compareTo(this) <= 0) return x;
        return prev;
    }

    public int signum() {
        if (isZero()) return 0;
        return hi < 0 ? -1 : 1;
    }

    public boolean isZero() { return hi == 0 && lo == 0; }
    public boolean isNegative() { return hi < 0; }
    public boolean fitsInLong() { return Int128Math.fitsInLong(hi, lo); }
    public long toLongExact() {
        if (!fitsInLong()) throw new ArithmeticException("BigNum out of long range: " + this);
        return lo;
    }

    public double toDouble() {
        if (fitsInLong()) return (double) lo;

        if (hi < 0) {
            long[] n = Int128Math.negate(hi, lo);
            return -(0x1.0p64 * (double) n[0] + unsignedToDouble(n[1]));
        }
        return 0x1.0p64 * (double) hi + unsignedToDouble(lo);
    }

    private static double unsignedToDouble(long v) {
        if (v >= 0) return (double) v;

        return ((double) (v >>> 1)) * 2.0 + (double) (v & 1L);
    }

    @Override
    public int compareTo(BigNum o) {
        return Int128Math.compare(hi, lo, o.hi, o.lo);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BigNum b)) return false;
        return hi == b.hi && lo == b.lo;
    }

    @Override
    public int hashCode() {
        return Objects.hash(hi, lo);
    }

    @Override
    public String toString() {
        return Int128Math.toDecimalString(hi, lo);
    }
}
