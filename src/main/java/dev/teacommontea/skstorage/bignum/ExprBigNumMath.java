package dev.teacommontea.skstorage.bignum;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

public final class ExprBigNumMath {

    private ExprBigNumMath() {}

    static void register() {

        Class<?>[] all = {
            Abs.class, Negate.class, Floor.class, Ceil.class, Round.class, Truncate.class,
            Sqrt.class, Sign.class, Mod.class, Min.class, Max.class
        };
        for (Class<?> c : all) {
            try { Class.forName(c.getName(), true, ExprBigNumMath.class.getClassLoader()); }
            catch (ClassNotFoundException ignored) {}
        }
    }

    public abstract static class Unary extends SimpleExpression<BigNum> {
        @SuppressWarnings("rawtypes") protected Expression src;
        @Override
        public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult pr) {
            src = exprs[0];
            return true;
        }
        @Override
        protected @Nullable BigNum[] get(Event event) {
            Object v = src.getSingle(event);
            if (!(v instanceof BigNum b)) return new BigNum[0];
            try { return new BigNum[]{ apply(b) }; }
            catch (Exception e) { return new BigNum[0]; }
        }
        protected abstract BigNum apply(BigNum b);
        @Override public boolean isSingle() { return true; }
        @Override public Class<? extends BigNum> getReturnType() { return BigNum.class; }
    }

    public static class Abs extends Unary {
        static { Skript.registerExpression(Abs.class, BigNum.class, ExpressionType.COMBINED,
            "abs[olute] [value] of %bignum%"); }
        @Override protected BigNum apply(BigNum b) { return b.abs(); }
        @Override public String toString(@Nullable Event e, boolean d) { return "abs of " + src; }
    }

    public static class Negate extends Unary {
        static { Skript.registerExpression(Negate.class, BigNum.class, ExpressionType.COMBINED,
            "negat(e|ive|ion of) %bignum%"); }
        @Override protected BigNum apply(BigNum b) { return b.negate(); }
        @Override public String toString(@Nullable Event e, boolean d) { return "negation of " + src; }
    }

    public static class Floor extends Unary {
        static { Skript.registerExpression(Floor.class, BigNum.class, ExpressionType.COMBINED,
            "floor[ed] [value] of %bignum%"); }
        @Override protected BigNum apply(BigNum b) { return b; }
        @Override public String toString(@Nullable Event e, boolean d) { return "floor of " + src; }
    }

    public static class Ceil extends Unary {
        static { Skript.registerExpression(Ceil.class, BigNum.class, ExpressionType.COMBINED,
            "(ceil[ed]|ceiling) [value] of %bignum%"); }
        @Override protected BigNum apply(BigNum b) { return b; }
        @Override public String toString(@Nullable Event e, boolean d) { return "ceil of " + src; }
    }

    public static class Round extends Unary {
        static { Skript.registerExpression(Round.class, BigNum.class, ExpressionType.COMBINED,
            "round[ed] [value of] %bignum%"); }
        @Override protected BigNum apply(BigNum b) { return b; }
        @Override public String toString(@Nullable Event e, boolean d) { return "round " + src; }
    }

    public static class Truncate extends Unary {
        static { Skript.registerExpression(Truncate.class, BigNum.class, ExpressionType.COMBINED,
            "truncat(e[d]|ion of) %bignum%"); }
        @Override protected BigNum apply(BigNum b) { return b; }
        @Override public String toString(@Nullable Event e, boolean d) { return "trunc " + src; }
    }

    public static class Sqrt extends Unary {
        static { Skript.registerExpression(Sqrt.class, BigNum.class, ExpressionType.COMBINED,
            "(sqrt|square root) of %bignum%"); }
        @Override protected BigNum apply(BigNum b) { return b.isqrt(); }
        @Override public String toString(@Nullable Event e, boolean d) { return "sqrt " + src; }
    }

    public static class Sign extends SimpleExpression<Long> {
        static { Skript.registerExpression(Sign.class, Long.class, ExpressionType.COMBINED,
            "sign[um] of %bignum%"); }
        @SuppressWarnings("rawtypes") private Expression src;
        @Override
        public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult pr) {
            src = exprs[0];
            return true;
        }
        @Override
        protected @Nullable Long[] get(Event e) {
            Object v = src.getSingle(e);
            if (!(v instanceof BigNum b)) return new Long[0];
            return new Long[]{ (long) b.signum() };
        }
        @Override public boolean isSingle() { return true; }
        @Override public Class<? extends Long> getReturnType() { return Long.class; }
        @Override public String toString(@Nullable Event e, boolean d) { return "sign of " + src; }
    }

    public abstract static class Binary extends SimpleExpression<BigNum> {
        @SuppressWarnings("rawtypes") protected Expression a;
        @SuppressWarnings("rawtypes") protected Expression b;
        @Override
        public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult pr) {
            a = exprs[0]; b = exprs[1];
            return true;
        }
        @Override
        protected @Nullable BigNum[] get(Event event) {
            Object av = a.getSingle(event);
            Object bv = b.getSingle(event);
            BigNum ax = (av instanceof BigNum x) ? x : (av instanceof Number n ? BigNum.fromNumber(n) : null);
            BigNum bx = (bv instanceof BigNum x) ? x : (bv instanceof Number n ? BigNum.fromNumber(n) : null);
            if (ax == null || bx == null) return new BigNum[0];
            try { return new BigNum[]{ apply(ax, bx) }; }
            catch (Exception e) { return new BigNum[0]; }
        }
        protected abstract BigNum apply(BigNum a, BigNum b);
        @Override public boolean isSingle() { return true; }
        @Override public Class<? extends BigNum> getReturnType() { return BigNum.class; }
    }

    public static class Mod extends Binary {
        static { Skript.registerExpression(Mod.class, BigNum.class, ExpressionType.COMBINED,
            "%bignum% (mod|modulo) %bignum/number%",
            "%number% (mod|modulo) %bignum%"); }
        @Override protected BigNum apply(BigNum a, BigNum b) { return a.mod(b); }
        @Override public String toString(@Nullable Event e, boolean d) { return a + " mod " + b; }
    }

    public static class Min extends Binary {
        static { Skript.registerExpression(Min.class, BigNum.class, ExpressionType.COMBINED,
            "min[imum] of %bignum% and %bignum/number%"); }
        @Override protected BigNum apply(BigNum a, BigNum b) { return a.compareTo(b) <= 0 ? a : b; }
        @Override public String toString(@Nullable Event e, boolean d) { return "min(" + a + ", " + b + ")"; }
    }

    public static class Max extends Binary {
        static { Skript.registerExpression(Max.class, BigNum.class, ExpressionType.COMBINED,
            "max[imum] of %bignum% and %bignum/number%"); }
        @Override protected BigNum apply(BigNum a, BigNum b) { return a.compareTo(b) >= 0 ? a : b; }
        @Override public String toString(@Nullable Event e, boolean d) { return "max(" + a + ", " + b + ")"; }
    }
}
