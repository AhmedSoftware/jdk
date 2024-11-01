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
package jdk.jpackage.internal.model;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static java.util.stream.Collectors.toMap;
import jdk.jpackage.internal.resources.ResourceLocator;

public interface WinLauncher extends Launcher {

    @Override
    default String executableSuffix() {
        return ".exe";
    }

    boolean isConsole();

    @Override
    default InputStream executableResource() {
        return ResourceLocator.class.getResourceAsStream(
                isConsole() ? "jpackageapplauncher.exe" : "jpackageapplauncherw.exe");
    }

    @Override
    default Map<String, String> extraAppImageFileData() {
        return Optional.ofNullable(shortcuts()).orElseGet(Set::of).stream().collect(
                toMap(WinShortcut::name, v -> Boolean.toString(true)));
    }

    @Override
    default String defaultIconResourceName() {
        return "JavaApp.ico";
    }

    enum WinShortcut {
        WIN_SHORTCUT_DESKTOP("shortcut"),
        WIN_SHORTCUT_START_MENU("menu"),
        ;

        WinShortcut(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        private final String name;
    }

    Set<WinShortcut> shortcuts();

    class Impl extends Launcher.Proxy<Launcher> implements WinLauncher {
        public Impl(Launcher launcher, boolean isConsole, Set<WinShortcut> shortcuts) {
            super(launcher);
            this.isConsole = isConsole;
            this.shortcuts = shortcuts;
        }

        @Override
        public boolean isConsole() {
            return isConsole;
        }

        @Override
        public Set<WinShortcut> shortcuts() {
            return shortcuts;
        }

        private final boolean isConsole;
        private final Set<WinShortcut> shortcuts;
    }
}
