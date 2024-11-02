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
import java.util.Optional;

public interface Package {

    Application app();

    PackageType type();

    default StandardPackageType asStandardPackageType() {
        if (type() instanceof StandardPackageType stdType) {
            return stdType;
        } else {
            return null;
        }
    }

    /**
     * Returns platform-specific package name.
     *
     * The value should be valid file system name as it will be used to create
     * files/directories in the file system.
     */
    String packageName();

    String description();

    String version();

    String aboutURL();

    Path licenseFile();

    Path predefinedAppImage();

    /**
     * Returns source app image layout.
     */
    default AppImageLayout appImageLayout() {
        return app().imageLayout();
    }

    default ApplicationLayout asApplicationLayout() {
        return app().asApplicationLayout();
    }

    /**
     * Returns app image layout inside of the package.
     */
    default AppImageLayout packageLayout() {
        return appImageLayout().resolveAt(relativeInstallDir());
    }

    default ApplicationLayout asPackageApplicationLayout() {
        if (packageLayout() instanceof ApplicationLayout layout) {
            return layout;
        } else {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Returns app image layout of the installed package.
     */
    default AppImageLayout installedPackageLayout() {
        Path root = relativeInstallDir();
        if (type() instanceof StandardPackageType type) {
            switch (type) {
                case LINUX_DEB, LINUX_RPM, MAC_DMG, MAC_PKG -> {
                    root = Path.of("/").resolve(root);
                }
            }
        }
        return appImageLayout().resolveAt(root);
    }

    default ApplicationLayout asInstalledPackageApplicationLayout() {
        if (installedPackageLayout() instanceof ApplicationLayout layout) {
            return layout;
        } else {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Returns package file name.
     */
    default String packageFileName() {
        return String.format("%s-%s", packageName(), version());
    }

    default String packageFileSuffix() {
        if (type() instanceof StandardPackageType type) {
            return type.suffix();
        } else {
            throw new UnsupportedOperationException();
        }
    }

    default String packageFileNameWithSuffix() {
        return packageFileName() + Optional.ofNullable(packageFileSuffix()).orElse("");
    }

    default boolean isRuntimeInstaller() {
        return app().isRuntime();
    }

    /**
     * Returns relative path to the package installation directory.
     *
     * On Windows it should be relative to %ProgramFiles% and relative
     * to the system root ('/') on other platforms.
     */
    Path relativeInstallDir();

    record Impl(Application app, PackageType type, String packageName,
            String description, String version, String aboutURL, Path licenseFile,
            Path predefinedAppImage, Path relativeInstallDir) implements Package {
    }

    class Unsupported implements Package {

        @Override
        public Application app() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PackageType type() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String packageName() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String description() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String version() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String aboutURL() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path licenseFile() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path predefinedAppImage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path relativeInstallDir() {
            throw new UnsupportedOperationException();
        }

    }

    class Proxy<T extends Package> extends ProxyBase<T> implements Package {

        Proxy(T target) {
            super(target);
        }

        @Override
        public Application app() {
            return target.app();
        }

        @Override
        public PackageType type() {
            return target.type();
        }

        @Override
        public String packageName() {
            return target.packageName();
        }

        @Override
        public String description() {
            return target.description();
        }

        @Override
        public String version() {
            return target.version();
        }

        @Override
        public String aboutURL() {
            return target.aboutURL();
        }

        @Override
        public Path licenseFile() {
            return target.licenseFile();
        }

        @Override
        public Path predefinedAppImage() {
            return target.predefinedAppImage();
        }

        @Override
        public Path relativeInstallDir() {
            return target.relativeInstallDir();
        }
    }
}
