package io.github.lbsyzfs.colorosgmsunrestrict;

import android.os.Message;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private final AtomicBoolean installed = new AtomicBoolean(false);

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

                if (stateMethod == null || policyMethod == null) {
                    log(Log.ERROR, TAG,
                            "target methods not found at " + stage
                                    + "; ColorOS version may have changed");
                    return;
                }

                stateMethod.setAccessible(true);
                policyMethod.setAccessible(true);

                // ART may have inlined K into handleMessage and C into K. Deoptimizing the
                // callers before installing the hooks makes those calls observable again.
                deoptimizeCallers(classLoader, stateMethod);

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

                installed.set(true);
                log(Log.INFO, TAG,
                        "hooks installed at " + stage
                                + ": state=" + stateMethod.toGenericString()
                                + ", policy=" + policyMethod.toGenericString());
            } catch (ClassNotFoundException e) {
                log(Log.WARN, TAG,
                        TARGET_CLASS + " not available at " + stage + ", will retry if possible");
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "hook installation failed at " + stage, t);
            }
        }
    }

    private void deoptimizeCallers(ClassLoader classLoader, Method stateMethod) {
        Boolean handlerResult = null;
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
            // K is the caller of C, so deoptimizing K prevents an inlined C from bypassing
            // the policy hook. Use the resolved method in case an OTA changed its name.
            stateResult = deoptimize(stateMethod);
        } catch (Throwable t) {
            log(Log.ERROR, TAG,
                    "deoptimize K caller failed; C hook may miss an inlined call", t);
        }

        log(Log.INFO, TAG,
                "deoptimize complete: handleMessage=" + formatDeoptResult(handlerResult)
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
