package com.lazyexec.imebar;

import android.inputmethodservice.InputMethodService;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed entry. Scope: the keyboard app(s), e.g. Gboard. Hooks
 * {@code NavigationBarController.onWindowShown()} inside the keyboard's process and hands
 * the strip under the keys to {@link ImeBar}.
 */
public final class XposedInit implements IXposedHookLoadPackage {
    private static final String NBC = "android.inputmethodservice.NavigationBarController";
    private static boolean sHooked;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (sHooked || "android".equals(lp.packageName)
                || "com.lazyexec.imebar".equals(lp.packageName)) {
            return;
        }
        sHooked = true;
        ClassLoader cl = lp.classLoader;

        XposedHelpers.findAndHookMethod(NBC, cl, "onWindowShown", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    final Object ctl = p.thisObject;
                    InputMethodService ims =
                            (InputMethodService) XposedHelpers.getObjectField(ctl, "mService");
                    View frame = (View) XposedHelpers.getObjectField(ctl, "mNavigationBarFrame");
                    if (ims == null || frame == null) return;
                    ImeBar.onShown(ims, frame, () -> {
                        try {
                            return XposedHelpers.getFloatField(ctl, "mDarkIntensity");
                        } catch (Throwable t) {
                            return -1;
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
        XposedBridge.log("ImeBar: hooked " + lp.packageName);
    }
}
