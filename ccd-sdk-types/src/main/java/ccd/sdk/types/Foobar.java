package ccd.sdk.types;

import net.jodah.typetools.TypeResolver;

public class Foobar<T> {
    public Class<T> getTypeArg() {
        Foobar<T> f = this;
        Class<T> typeArg = (Class<T>) TypeResolver.resolveRawArgument(Foobar.class, f.getClass());
        return typeArg;
    }
}
