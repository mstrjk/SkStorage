package net.teacommontea.skstorage.bignum;

import org.skriptlang.skript.lang.arithmetic.Arithmetics;
import org.skriptlang.skript.lang.arithmetic.Operator;
import org.skriptlang.skript.lang.converter.Converters;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;

public final class SkipMath {

    private static final MathContext MC = MathContext.DECIMAL128;

    private SkipMath() {}

    public static void install(java.util.logging.Logger log) {
        try {
            registerBigDecimalOps();
            registerNumberConverter();
            clearArithmeticCaches(log);
            log.info("skip_math: BigDecimal arithmetic installed (EXPERIMENTAL, validate on your server).");
        } catch (Throwable t) {
            log.severe("skip_math: failed to install BigDecimal arithmetic: " + t);
        }
    }

    private static void registerBigDecimalOps() {
        Arithmetics.registerOperation(Operator.ADDITION, BigDecimal.class,
            (a, b) -> a.add(b, MC));
        Arithmetics.registerOperation(Operator.SUBTRACTION, BigDecimal.class,
            (a, b) -> a.subtract(b, MC));
        Arithmetics.registerOperation(Operator.MULTIPLICATION, BigDecimal.class,
            (a, b) -> a.multiply(b, MC));
        Arithmetics.registerOperation(Operator.DIVISION, BigDecimal.class,
            (a, b) -> b.signum() == 0 ? BigDecimal.ZERO : a.divide(b, MC));
        Arithmetics.registerOperation(Operator.EXPONENTIATION, BigDecimal.class,
            (a, b) -> a.pow(b.intValue(), MC));
    }

    private static void registerNumberConverter() {
        if (!Converters.converterExists(Number.class, BigDecimal.class)) {
            Converters.registerConverter(Number.class, BigDecimal.class,
                n -> {
                    if (n instanceof BigDecimal bd) return bd;
                    if (n instanceof java.math.BigInteger bi) return new BigDecimal(bi);
                    double d = n.doubleValue();
                    if (Double.isNaN(d) || Double.isInfinite(d)) return null;
                    return BigDecimal.valueOf(d);
                });
        }
    }

    private static void clearArithmeticCaches(java.util.logging.Logger log) {
        for (String field : new String[]{"CACHED_OPERATIONS", "CACHED_CONVERTED_OPERATIONS"}) {
            try {
                Field f = Arithmetics.class.getDeclaredField(field);
                f.setAccessible(true);
                Object value = f.get(null);
                if (value instanceof Map<?, ?> map) {
                    map.clear();
                }
            } catch (NoSuchFieldException e) {
                log.warning("skip_math: Arithmetics." + field + " not found on this Skript; " +
                    "BigDecimal ops may not take effect until first use.");
            } catch (Throwable t) {
                log.warning("skip_math: could not clear Arithmetics." + field + ": " + t.getMessage());
            }
        }
    }
}
