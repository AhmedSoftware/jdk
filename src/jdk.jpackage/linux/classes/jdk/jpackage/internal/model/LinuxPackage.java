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

import java.nio.file.Path;
import jdk.jpackage.internal.util.DynamicProxy;

public interface LinuxPackage extends Package, LinuxPackageMixin {

    @Override
    AppImageLayout packageLayout();

    @Override
    default String packageFileName() {
        String packageFileNameTemlate;
        switch (asStandardPackageType()) {
            case LINUX_DEB -> {
                packageFileNameTemlate = "%s_%s-%s_%s";
            }
            case LINUX_RPM -> {
                packageFileNameTemlate = "%s-%s-%s.%s";
            }
            default -> {
                throw new UnsupportedOperationException();
            }
        }

        return String.format(packageFileNameTemlate, packageName(), version(), release(), arch());
    }

    // This override is needed to make Package.packageFileNameWithSuffix() invoke
    // LinuxPackage.packageFileName() instead of the default Package.packageFileName().
    @Override
    default String packageFileNameWithSuffix() {
        return Package.super.packageFileNameWithSuffix();
    }

    default boolean isInstallDirInUsrTree() {
        return !relativeInstallDir().getFileName().equals(Path.of(packageName()));
    }

    public static LinuxPackage create(Package pkg, LinuxPackageMixin mixin) {
        return DynamicProxy.createProxyFromPieces(LinuxPackage.class, pkg, mixin);
    }
}
