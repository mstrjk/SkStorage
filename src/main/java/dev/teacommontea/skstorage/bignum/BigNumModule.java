package dev.teacommontea.skstorage.bignum;

import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.classes.Parser;
import ch.njol.skript.classes.Serializer;
import ch.njol.skript.lang.ParseContext;
import ch.njol.skript.registrations.Classes;
import ch.njol.yggdrasil.Fields;
import org.skriptlang.skript.lang.arithmetic.Arithmetics;
import org.skriptlang.skript.lang.arithmetic.Operator;
import org.skriptlang.skript.lang.comparator.Comparators;
import org.skriptlang.skript.lang.comparator.Relation;
import org.skriptlang.skript.lang.converter.Converters;

import java.io.StreamCorruptedException;

public final class BigNumModule {

    private BigNumModule() {}

    public static void register() {
        registerClassInfo();
        registerArithmetic();
        registerComparator();
        registerExpressions();

    }

    private static void registerExpressions() {

        try { Class.forName(ExprBigNum.class.getName(), true, BigNumModule.class.getClassLoader()); }
        catch (ClassNotFoundException ignored) {}
        ExprBigNumMath.register();
    }

    private static void registerClassInfo() {
        Classes.registerClass(new ClassInfo<>(BigNum.class, "bignum")
            .user("bignums?")
            .name("BigNum")
            .description(
                "A signed 128-bit integer for currency-scale values.",
                "Range is roughly +/- 1.7e38 with exact arithmetic. Use for",
                "balances, statistics, or any counter that can exceed 2^53.")
            .since("1.3.0")
            .parser(new Parser<BigNum>() {
                @Override
                public boolean canParse(ParseContext context) {

                    return context == ParseContext.PARSE;
                }
                @Override
                public BigNum parse(String s, ParseContext context) {
                    try {
                        return BigNum.parse(s);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                @Override
                public String toString(BigNum b, int flags) {
                    return b.toString();
                }
                @Override
                public String toVariableNameString(BigNum b) {
                    return b.toString();
                }
            })
            .serializer(new Serializer<BigNum>() {
                @Override
                public Fields serialize(BigNum b) {
                    Fields f = new Fields();
                    f.putPrimitive("hi", b.hi());
                    f.putPrimitive("lo", b.lo());
                    return f;
                }
                @Override
                protected boolean canBeInstantiated() {
                    return false;
                }
                @Override
                public boolean mustSyncDeserialization() {
                    return false;
                }
                @Override
                protected BigNum deserialize(Fields f) throws StreamCorruptedException {
                    long hi = f.getPrimitive("hi", long.class);
                    long lo = f.getPrimitive("lo", long.class);
                    return new BigNum(hi, lo);
                }
            })
        );
    }

    private static void registerArithmetic() {

        Arithmetics.registerOperation(Operator.ADDITION,        BigNum.class, (a, b) -> a.add(b));
        Arithmetics.registerOperation(Operator.SUBTRACTION,     BigNum.class, (a, b) -> a.subtract(b));
        Arithmetics.registerOperation(Operator.MULTIPLICATION,  BigNum.class, (a, b) -> a.multiply(b));
        Arithmetics.registerOperation(Operator.DIVISION,        BigNum.class, (a, b) -> a.divide(b));
        Arithmetics.registerOperation(Operator.EXPONENTIATION,  BigNum.class, (a, b) -> {
            long e = b.fitsInLong() ? b.toLongExact() : Integer.MAX_VALUE;
            return a.pow((int) Math.min(Integer.MAX_VALUE, Math.max(0L, e)));
        });

        Arithmetics.registerOperation(Operator.ADDITION, BigNum.class, Number.class,
            (a, n) -> a.add(BigNum.fromNumber(n)),
            (n, a) -> a.add(BigNum.fromNumber(n)));
        Arithmetics.registerOperation(Operator.SUBTRACTION, BigNum.class, Number.class,
            (a, n) -> a.subtract(BigNum.fromNumber(n)),
            (n, a) -> BigNum.fromNumber(n).subtract(a));
        Arithmetics.registerOperation(Operator.MULTIPLICATION, BigNum.class, Number.class,
            (a, n) -> a.multiply(BigNum.fromNumber(n)),
            (n, a) -> a.multiply(BigNum.fromNumber(n)));
        Arithmetics.registerOperation(Operator.DIVISION, BigNum.class, Number.class,
            (a, n) -> a.divide(BigNum.fromNumber(n)),
            (n, a) -> BigNum.fromNumber(n).divide(a));

        Arithmetics.registerDifference(BigNum.class, (a, b) -> a.subtract(b).abs());

        Arithmetics.registerDefaultValue(BigNum.class, () -> BigNum.ZERO);
    }

    private static void registerComparator() {
        Comparators.registerComparator(BigNum.class, BigNum.class,
            (a, b) -> Relation.get(a.compareTo(b)));
        Comparators.registerComparator(BigNum.class, Number.class,
            (a, n) -> Relation.get(a.compareTo(BigNum.fromNumber(n))));
    }

    private static void registerConverter() {
        Converters.registerConverter(Number.class, BigNum.class, BigNum::fromNumber);
    }
}
