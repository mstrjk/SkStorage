package net.teacommontea.skstorage.bignum;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

public final class ExprBigNum extends SimpleExpression<BigNum> {

    static {
        Skript.registerExpression(ExprBigNum.class, BigNum.class, ExpressionType.COMBINED,
            "bignum of %string%",
            "bignum of %number%");
    }

    @SuppressWarnings("rawtypes") private Expression src;
    private boolean fromString;

    @Override
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult pr) {
        src = exprs[0];
        fromString = (matchedPattern == 0);
        return true;
    }

    @Override
    protected @Nullable BigNum[] get(Event event) {
        Object v = src.getSingle(event);
        if (v == null) return new BigNum[0];
        try {
            if (fromString) {
                return new BigNum[]{ BigNum.parse((String) v) };
            } else {
                return new BigNum[]{ BigNum.fromNumber((Number) v) };
            }
        } catch (Exception e) {
            return new BigNum[0];
        }
    }

    @Override
    public boolean isSingle() { return true; }

    @Override
    public Class<? extends BigNum> getReturnType() { return BigNum.class; }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "bignum from " + (src == null ? "?" : src.toString(event, debug));
    }
}
