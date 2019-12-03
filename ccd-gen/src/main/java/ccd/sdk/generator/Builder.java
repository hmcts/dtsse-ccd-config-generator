package ccd.sdk.generator;

import com.google.common.collect.Sets;
import net.jodah.typetools.TypeResolver;
import net.sf.cglib.proxy.Callback;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.objenesis.ObjenesisHelper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public class Builder<T, P> {

    private final Class<P> p;
    private Class<T> persistentClass;
    public Builder(Class<T> c, Class<P> p) {
        this.persistentClass = c;
        this.p = p;
    }

    public static <T, P> Builder<T, P> builder(Class<T> t, Class<P> p) {
        return new Builder(t, p);
    }

    public Builder<T, P> event(P s1, P s2) {
        return this;
    }

    public <U> Builder<T, P> field(Function<T, List<U>> c, Consumer<U> u) {
        Class<?> typeArg = TypeResolver.resolveRawArgument(Consumer.class, u.getClass());

        final MethodInterceptor hashCodeAlwaysNull = new MethodInterceptor() {

            @Override
            public Object intercept(final Object object, final Method method,
                                    final Object[] args, final MethodProxy methodProxy)
                    throws Throwable {
                System.out.println(method.getName());
                return methodProxy.invokeSuper(object, args);
            }
        };
        final U proxy = createProxy(typeArg, hashCodeAlwaysNull);
        u.accept(proxy);
        return this;
    }

    public Builder<T, P> field(Consumer<T> c, DisplayContext mandatory) {
        Set<String> invoked = Sets.newHashSet();
        final MethodInterceptor hashCodeAlwaysNull = new MethodInterceptor() {

            @Override
            public Object intercept(final Object object, final Method method,
                                    final Object[] args, final MethodProxy methodProxy)
                    throws Throwable {
                invoked.add(method.getName());
                return methodProxy.invokeSuper(object, args);
            }
        };
        final T proxy = createProxy(persistentClass, hashCodeAlwaysNull);
        c.accept(proxy);
        for (String s : invoked) {
            System.out.println(s);
        }

        return this;
    }

    @SuppressWarnings("unchecked")
    private static <T> T createProxy(final Class classToMock,
                                     final MethodInterceptor interceptor) {
        final Enhancer enhancer = new Enhancer();
        enhancer.setUseCache(false); //important
        enhancer.setSuperclass(classToMock);
        enhancer.setCallbackType(interceptor.getClass());

        final Class<?> proxyClass = enhancer.createClass();
        Enhancer.registerCallbacks(proxyClass, new Callback[] { interceptor });
        return (T) ObjenesisHelper.newInstance(proxyClass);
    }
}
