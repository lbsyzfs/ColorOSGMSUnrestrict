package io.github.lbsyzfs.colorosgmsunrestrict;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Disables ColorOS 16's dynamic GMS network restriction in OPlus Battery.
 *
 * Reverse-engineered target:
 * com.oplus.battery.restrictdynamicfeature.google.GoogleRestrictionController
 *
 * K(boolean restricted, boolean listChanged, int bootState)
 * C(boolean restricted, Set<String> userChangedPackages)
 *
 * ColorOS uses C(true, ...) to call OplusNetworkingControlManager#setUidPolicy(uid, 4)
 * for the Google network restriction list. Forcing the first argument to false keeps the
 * OEM state machine intact while driving it through its own unrestrict path:
 * setUidPolicy(uid, 0), restrict_enable=false, google_restric_info=0.
 */
public final class ColorOSGMSUnrestrict extends XposedModule {
    private static final String TAG = "ColorOSGMSUnrestrict";
    private static final String TARGET_PACKAGE = "com.oplus.battery";
    private static final String TARGET_CLASS =
            "com.oplus.battery.restrictdynamicfeature.google.GoogleRestrictionController";
    private static final String TARGET_HANDLER_CLASS = TARGET_CLASS + "$b";
    private static final String RESTRICTION_SETTING = "google_restric_info";

    private final AtomicBoolean installed = new AtomicBoolean(false);
    private final AtomicBoolean startupCleanupHookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean applicationReady = new AtomicBoolean(false);
    private final AtomicBoolean uidPolicyCleared = new AtomicBoolean(false);
    private final AtomicBoolean persistedStateCleared = new AtomicBoolean(false);
    private final AtomicReference<Object> controllerInstance = new AtomicReference<>();
    private volatile Method cleanupPolicyMethod;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG,
                "module loaded, process=" + param.getProcessName()
                        + ", api=" + getApiVersion()
                        + ", framework=" + getFrameworkName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }

        tryInstall(param.getDefaultClassLoader(), "onPackageLoaded");
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }

        if (!installed.get()) {
            tryInstall(param.getClassLoader(), "onPackageReady");
        }

        installStartupCleanupHook(
                param.getClassLoader(),
                param.getApplicationInfo().className
        );
    }

    private void tryInstall(ClassLoader classLoader, String stage) {
        if (installed.get()) {
            return;
        }

        synchronized (installed) {
            if (installed.get()) {
                return;
            }

            try {
                Class<?> controller = classLoader.loadClass(TARGET_CLASS);

                Method stateMethod = findMethod(
                        controller,
                        "K",
                        boolean.class,
                        boolean.class,
                        int.class
                );

                Method policyMethod = findMethod(
                        controller,
                        "C",
                        boolean.class,
                        Set.class
                );

                // The nested Handler calls compiler-generated access bridges w/t rather
                // than K/C directly. ART may inline K/C into those tiny bridges, bypassing
                // hooks on the private methods even after handleMessage is deoptimized.
                Method stateBridge = findMethod(
                        controller,
                        "w",
                        controller,
                        boolean.class,
                        boolean.class,
                        int.class
                );

                Method policyBridge = findMethod(
                        controller,
                        "t",
                        controller,
                        boolean.class,
                        Set.class
                );

                if (stateMethod == null || policyMethod == null
                        || stateBridge == null || policyBridge == null) {
                    log(Log.ERROR, TAG,
                            "target methods not found at " + stage
                                    + "; ColorOS version may have changed");
                    return;
                }

                stateMethod.setAccessible(true);
                policyMethod.setAccessible(true);
                stateBridge.setAccessible(true);
                policyBridge.setAccessible(true);

                cleanupPolicyMethod = policyMethod;
                installControllerLifecycleHooks(controller);

                deoptimizeCallers(classLoader, stateBridge, policyBridge, stateMethod);

                var stateBridgeHandle = hook(stateBridge)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object[] args = chain.getArgs().toArray();
                            boolean wasRestricted = Boolean.TRUE.equals(args[1]);
                            args[1] = Boolean.FALSE;

                            if (wasRestricted) {
                                log(Log.INFO, TAG,
                                        "state bridge: restricted=true -> false, method="
                                                + chain.getExecutable().getName());
                            }

                            return chain.proceed(args);
                        });

                try {
                    var policyBridgeHandle = hook(policyBridge)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object[] args = chain.getArgs().toArray();
                                boolean wasRestricted = Boolean.TRUE.equals(args[1]);
                                args[1] = Boolean.FALSE;

                                if (wasRestricted) {
                                    log(Log.INFO, TAG,
                                            "policy bridge: blocked request -> unrestrict path, method="
                                                    + chain.getExecutable().getName());
                                }

                                return chain.proceed(args);
                            });

                    try {
                        var stateHandle = hook(stateMethod)
                                .setExceptionMode(ExceptionMode.PROTECTIVE)
                                .intercept(chain -> {
                                    Object[] args = chain.getArgs().toArray();
                                    boolean wasRestricted = Boolean.TRUE.equals(args[0]);
                                    args[0] = Boolean.FALSE;

                                    if (wasRestricted) {
                                        log(Log.INFO, TAG,
                                                "K: restricted=true -> false, method="
                                                        + chain.getExecutable().getName());
                                    }

                                    return chain.proceed(args);
                                });

                        try {
                            hook(policyMethod)
                                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                                    .intercept(chain -> {
                                        Object[] args = chain.getArgs().toArray();
                                        boolean wasRestricted = Boolean.TRUE.equals(args[0]);
                                        args[0] = Boolean.FALSE;

                                        if (wasRestricted) {
                                            log(Log.INFO, TAG,
                                                    "C: blocked UID policy request -> unrestrict path, method="
                                                            + chain.getExecutable().getName());
                                        }

                                        return chain.proceed(args);
                                    });
                        } catch (Throwable t) {
                            stateHandle.unhook();
                            throw t;
                        }
                    } catch (Throwable t) {
                        policyBridgeHandle.unhook();
                        throw t;
                    }
                } catch (Throwable t) {
                    stateBridgeHandle.unhook();
                    throw t;
                }

                installed.set(true);
                log(Log.INFO, TAG,
                        "hooks installed at " + stage
                                + ": state=" + stateMethod.toGenericString()
                                + ", policy=" + policyMethod.toGenericString()
                                + ", stateBridge=" + stateBridge.toGenericString()
                                + ", policyBridge=" + policyBridge.toGenericString());
            } catch (ClassNotFoundException e) {
                log(Log.WARN, TAG,
                        TARGET_CLASS + " not available at " + stage + ", will retry if possible");
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "hook installation failed at " + stage, t);
            }
        }
    }

    private void installControllerLifecycleHooks(Class<?> controller) {
        int hookCount = 0;

        for (Constructor<?> constructor : controller.getDeclaredConstructors()) {
            try {
                constructor.setAccessible(true);
                hook(constructor)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object instance = chain.getThisObject();

                            if (instance != null) {
                                controllerInstance.compareAndSet(null, instance);
                                if (applicationReady.get()) {
                                    clearUidPolicies();
                                }
                            }

                            return result;
                        });
                hookCount++;
            } catch (Throwable t) {
                log(Log.ERROR, TAG,
                        "controller constructor hook failed: " + constructor.toGenericString(), t);
            }
        }

        log(Log.INFO, TAG, "controller constructor hooks installed: " + hookCount);
    }

    private void installStartupCleanupHook(ClassLoader classLoader, String applicationClassName) {
        if (!startupCleanupHookInstalled.compareAndSet(false, true)) {
            return;
        }

        try {
            Class<?> applicationClass = applicationClassName == null
                    ? Application.class
                    : classLoader.loadClass(applicationClassName);
            Method onCreate = applicationClass.getMethod("onCreate");

            hook(onCreate)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object application = chain.getThisObject();

                        applicationReady.set(true);
                        if (application instanceof Context context) {
                            clearPersistedState(context);
                        } else {
                            log(Log.ERROR, TAG,
                                    "startup cleanup has no application Context");
                        }
                        clearUidPolicies();

                        return result;
                    });

            log(Log.INFO, TAG,
                    "startup cleanup hook installed: " + onCreate.toGenericString());
        } catch (Throwable t) {
            startupCleanupHookInstalled.set(false);
            log(Log.ERROR, TAG, "startup cleanup hook installation failed", t);
        }
    }

    @SuppressLint("MissingPermission")
    private void clearPersistedState(Context context) {
        if (persistedStateCleared.get()) {
            return;
        }

        synchronized (persistedStateCleared) {
            if (persistedStateCleared.get()) {
                return;
            }

            try {
                int previous = Settings.Secure.getInt(
                        context.getContentResolver(),
                        RESTRICTION_SETTING,
                        -1
                );
                boolean updated = Settings.Secure.putInt(
                        context.getContentResolver(),
                        RESTRICTION_SETTING,
                        0
                );
                int current = Settings.Secure.getInt(
                        context.getContentResolver(),
                        RESTRICTION_SETTING,
                        -1
                );

                if (updated && current == 0) {
                    persistedStateCleared.set(true);
                    log(Log.INFO, TAG,
                            "persisted restriction state cleared: " + previous + " -> " + current);
                } else {
                    log(Log.ERROR, TAG,
                            "persisted restriction state clear failed: updated=" + updated
                                    + ", previous=" + previous + ", current=" + current);
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "persisted restriction state clear failed", t);
            }
        }
    }

    private void clearUidPolicies() {
        if (uidPolicyCleared.get()) {
            return;
        }

        Object instance = controllerInstance.get();
        Method policyMethod = cleanupPolicyMethod;
        if (instance == null || policyMethod == null) {
            return;
        }

        synchronized (uidPolicyCleared) {
            if (uidPolicyCleared.get()) {
                return;
            }

            try {
                policyMethod.invoke(instance, false, Collections.emptySet());
                uidPolicyCleared.set(true);
                log(Log.INFO, TAG, "persisted UID policies cleared through C(false, emptySet)");
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "persisted UID policy cleanup failed", t);
            }
        }
    }

    private void deoptimizeCallers(
            ClassLoader classLoader,
            Method stateBridge,
            Method policyBridge,
            Method stateMethod
    ) {
        Boolean handlerResult = null;
        Boolean stateBridgeResult = null;
        Boolean policyBridgeResult = null;
        Boolean stateResult = null;

        try {
            Class<?> handlerClass = classLoader.loadClass(TARGET_HANDLER_CLASS);
            Method handleMessage = handlerClass.getDeclaredMethod("handleMessage", Message.class);
            handleMessage.setAccessible(true);
            handlerResult = deoptimize(handleMessage);
        } catch (Throwable t) {
            log(Log.ERROR, TAG,
                    "deoptimize handleMessage failed; K hook may miss an inlined call", t);
        }

        try {
            stateBridgeResult = deoptimize(stateBridge);
        } catch (Throwable t) {
            log(Log.ERROR, TAG,
                    "deoptimize state bridge failed; K hook may miss an inlined call", t);
        }

        try {
            policyBridgeResult = deoptimize(policyBridge);
        } catch (Throwable t) {
            log(Log.ERROR, TAG,
                    "deoptimize policy bridge failed; C hook may miss an inlined call", t);
        }

        try {
            // K is the caller of C, so deoptimizing K prevents an inlined C from bypassing
            // the policy hook. Use the resolved method in case an OTA changed its name.
            stateResult = deoptimize(stateMethod);
        } catch (Throwable t) {
            log(Log.ERROR, TAG,
                    "deoptimize K caller failed; C hook may miss an inlined call", t);
        }

        log(Log.INFO, TAG,
                "deoptimize complete: handleMessage=" + formatDeoptResult(handlerResult)
                        + ", stateBridge=" + formatDeoptResult(stateBridgeResult)
                        + ", policyBridge=" + formatDeoptResult(policyBridgeResult)
                        + ", K=" + formatDeoptResult(stateResult));
    }

    private static String formatDeoptResult(Boolean result) {
        return result == null ? "unavailable" : result.toString();
    }

    /**
     * First tries the method name from the analyzed ColorOS 16 build. If an OTA only changes
     * obfuscated names, falls back to a unique method with the same return/parameter signature.
     * Ambiguous signature matches are rejected rather than hooking an unrelated method.
     */
    private Method findMethod(Class<?> clazz, String preferredName, Class<?>... parameterTypes) {
        try {
            Method exact = clazz.getDeclaredMethod(preferredName, parameterTypes);
            if (exact.getReturnType() == void.class) {
                return exact;
            }
        } catch (NoSuchMethodException ignored) {
        }

        Method candidate = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getReturnType() != void.class) {
                continue;
            }
            if (!Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                continue;
            }

            if (candidate != null) {
                log(Log.ERROR, TAG,
                        "signature fallback is ambiguous: "
                                + candidate.toGenericString() + " / " + method.toGenericString());
                return null;
            }
            candidate = method;
        }

        if (candidate != null) {
            log(Log.WARN, TAG,
                    "method name changed: " + preferredName + " -> " + candidate.getName()
                            + ", using signature fallback");
        }
        return candidate;
    }
}
