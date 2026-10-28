package io.github.opencubicchunks.cubicchunks.lab;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Optional fork capabilities: the same lab binary must also run against frozen older CC baselines. */
final class LabCoreFeatures {
    static final long UNAVAILABLE=Long.MIN_VALUE;
    private LabCoreFeatures() { }
    static Method optionalMethod(Class<?> type,String name,Class<?>... arguments) {
        try{return type.getMethod(name,arguments);}catch(NoSuchMethodException unavailable){return null;}
    }
    static Object invoke(Method method,Object receiver,Object... arguments) {
        try{return method.invoke(receiver,arguments);}
        catch(ReflectiveOperationException error){throw new IllegalStateException("Lab capability invocation failed: "+method,error);}
    }
    static String installObserver(InvocationHandler observer) {
        if(!Boolean.getBoolean("cubiclab.diagnostics"))return "disabled";
        ClassLoader loader=LabCoreFeatures.class.getClassLoader();
        try {
            Class<?> api=Class.forName("io.github.opencubicchunks.cubicchunks.core.network.CubeSendDiagnostics",false,loader);
            Class<?> listener=Class.forName(api.getName()+"$Observer",false,loader);
            Object proxy=Proxy.newProxyInstance(loader,new Class<?>[]{listener},observer);
            invoke(api.getMethod("setObserver",listener),null,proxy);
            return "installed";
        } catch(ClassNotFoundException unavailable){return "unavailable-in-core";}
        catch(NoSuchMethodException incompatible){return "unavailable-api-signature";}
    }
    static Long optionalLong(Method getter,Object receiver) {
        return getter==null?null:((Number)invoke(getter,receiver)).longValue();
    }
}
