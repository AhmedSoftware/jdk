/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.jpackage.internal;

import jdk.jpackage.internal.model.PackagerException;
import jdk.jpackage.internal.model.WinLauncher;
import jdk.jpackage.internal.model.Application;
import jdk.jpackage.internal.model.WinApplication;
import java.io.IOException;
import java.nio.file.Path;
import jdk.jpackage.internal.model.ApplicationLayout;

final class WinAppImageBuilder {

    static AppImageBuilder.Builder build() {
        return new AppImageBuilder.Builder().launcherCallback(new LauncherCallbackImpl());
    }

    private final static class LauncherCallbackImpl implements
            AppImageBuilder.LauncherCallback {

        @Override
        public void onLauncher(Application app,
                AppImageBuilder.LauncherContext ctx) throws IOException, PackagerException {
            Path iconTarget = null;
            var iconResource = app.createLauncherIconResource(ctx.launcher(),
                    name -> ctx.env().createResource(name));
            if (iconResource != null) {
                var iconDir = ctx.env().buildRoot().resolve("icons");
                iconTarget = iconDir.resolve(ctx.launcher().executableName() + ".ico");
                if (null == iconResource.saveToFile(iconTarget)) {
                    iconTarget = null;
                }
            }

            // Update branding of launcher executable
            new ExecutableRebrander((WinApplication) app,
                    (WinLauncher) ctx.launcher(),
                    name -> ctx.env().createResource(name)).execute(
                    ctx.env(), ctx.launcherExecutable(), iconTarget);
        }
    }

    final static ApplicationLayout APPLICATION_LAYOUT = ApplicationLayoutUtils.PLATFORM_APPLICATION_LAYOUT;
}
