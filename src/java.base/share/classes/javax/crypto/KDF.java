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

package javax.crypto;

import jdk.internal.javac.PreviewFeature;
import sun.security.jca.GetInstance;
import sun.security.jca.GetInstance.Instance;
import sun.security.util.Debug;

import java.security.InvalidAlgorithmParameterException;
import java.security.KDFParameters;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.Provider.Service;
import java.security.ProviderException;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import java.util.Iterator;
import java.util.Objects;

/**
 * This class provides the functionality of a Key Derivation Function (KDF),
 * which is a cryptographic algorithm for deriving additional keys from a secret
 * key and other data.
 * <p>
 * {@code KDF} objects are instantiated with the {@code getInstance} family of
 * methods. KDF algorithm names follow a naming convention of
 * <em>Algorithm</em>With<em>PRF</em>. For instance, a KDF implementation of
 * HKDF using HMAC-SHA256 has an algorithm name of "HKDFWithHmacSHA256". In some
 * cases the PRF portion of the algorithm field may be omitted if the KDF
 * algorithm has a fixed or default PRF.
 * <p>
 * If a provider is not specified in the {@code getInstance} method when
 * instantiating a {@code KDF} object, the provider is selected the first time
 * the {@code deriveKey} or {@code deriveData} method is called and a provider
 * is chosen that supports the parameters passed to the {@code deriveKey} or
 * {@code deriveData} method, for example the initial key material. However, if
 * {@code getProviderName} is called before calling the {@code deriveKey} or
 * {@code deriveData} methods, the first provider supporting the KDF algorithm
 * is chosen which may not be the desired one; therefore it is recommended not
 * to call {@code getProviderName} until after a key derivation operation. Once
 * a provider is selected, it cannot be changed.
 * <p>
 * API Usage Example:
 * {@snippet lang = java:
 *    KDF kdfHkdf = KDF.getInstance("HKDFWithHmacSHA256");
 *
 *    AlgorithmParameterSpec kdfParameterSpec =
 *             HKDFParameterSpec.ofExtract()
 *                              .addIKM(ikm)
 *                              .addSalt(salt).thenExpand(info, 42);
 *
 *    kdfHkdf.deriveKey("AES", kdfParameterSpec);
 *}
 *
 * @see SecretKey
 * @since 24
 */
@PreviewFeature(feature = PreviewFeature.Feature.KEY_DERIVATION)
public final class KDF {
    private static final Debug debug = Debug.getInstance("jca", "KDF");

    private static final Debug pdebug = Debug.getInstance("provider",
                                                          "Provider");
    private static final boolean skipDebug = Debug.isOn("engine=")
                                             && !Debug.isOn("kdf");

    // The provider
    private Provider provider;

    // The provider implementation (delegate)
    private KDFSpi spi;

    // The name of the KDF algorithm.
    private final String algorithm;

    // Additional KDF configuration parameters
    private final KDFParameters kdfParameters;

    // next service to try in provider selection
    // null once provider is selected
    private Service firstService;

    // remaining services to try in provider selection
    // null once provider is selected
    private Iterator<Service> serviceIterator;

    private final Object lock;

    /**
     * Instantiates a KDF object.
     *
     * @param keyDerivSpi
     *     the delegate
     * @param provider
     *     the provider
     * @param algorithm
     *     the algorithm
     * @param kdfParameters
     *     the algorithm parameters
     */
    private KDF(KDFSpi keyDerivSpi, Provider provider, String algorithm,
                KDFParameters kdfParameters) {
        this.spi = keyDerivSpi;
        this.provider = provider;
        this.algorithm = algorithm;
        this.kdfParameters = kdfParameters;
        lock = new Object();
    }

    private KDF(Service s, Iterator<Service> t, String algorithm,
                KDFParameters kdfParameters) {
        firstService = s;
        serviceIterator = t;
        this.algorithm = algorithm;
        this.kdfParameters = kdfParameters;
        lock = new Object();
    }

    /**
     * Returns the algorithm name of this {@code KDF} object.
     *
     * @return the algorithm name of this {@code KDF} object
     */
    public String getAlgorithm() {
        return this.algorithm;
    }

    /**
     * Returns the name of the provider.
     *
     * @return the name of the provider
     */
    public String getProviderName() {
        chooseFirstProvider();
        return provider.getName();
    }

    /**
     * Returns the {@code KDFParameters} used to initialize the object.
     *
     * @return the parameters used to initialize the object
     */
    public KDFParameters getKDFParameters() {
        return this.kdfParameters;
    }

    /**
     * Returns a {@code KDF} object that implements the specified algorithm.
     *
     * @param algorithm
     *     the key derivation algorithm to use
     *
     * @return a {@code KDF} object
     *
     * @throws NoSuchAlgorithmException
     *     if no {@code Provider} supports a {@code KDF} implementation for the
     *     specified algorithm
     * @throws NullPointerException
     *     if {@code algorithm} is {@code null}
     */
    public static KDF getInstance(String algorithm)
        throws NoSuchAlgorithmException {
        try {
            return getInstance(algorithm, (KDFParameters) null);
        } catch (InvalidAlgorithmParameterException e) {
            throw new NoSuchAlgorithmException(
                "Received an InvalidAlgorithmParameterException. Does this "
                + "algorithm require an AlgorithmParameterSpec?", e);
        }
    }

    /**
     * Returns a {@code KDF} object that implements the specified algorithm from
     * the specified security provider.
     *
     * @param algorithm
     *     the key derivation algorithm to use
     * @param provider
     *     the provider to use for this key derivation (may not be
     *     {@code null})
     *
     * @return a {@code KDF} object
     *
     * @throws NoSuchAlgorithmException
     *     if a provider is specified and it does not support the specified KDF
     *     algorithm, or if provider is {@code null} and there is no provider
     *     that supports a KDF implementation of the specified algorithm
     * @throws NoSuchProviderException
     *     if the specified provider is not registered in the security provider
     *     list
     * @throws NullPointerException
     *     if the algorithm is {@code null}
     */
    public static KDF getInstance(String algorithm, String provider)
        throws NoSuchAlgorithmException, NoSuchProviderException {
        Objects.requireNonNull(provider, "provider may not be null");
        try {
            return getInstance(algorithm, null, provider);
        } catch (InvalidAlgorithmParameterException e) {
            throw new NoSuchAlgorithmException(
                "Received an InvalidAlgorithmParameterException. Does this "
                + "algorithm require an AlgorithmParameterSpec?", e);
        }
    }

    /**
     * Returns a {@code KDF} object that implements the specified algorithm from
     * the specified security provider.
     *
     * @param algorithm
     *     the key derivation algorithm to use
     * @param provider
     *     the provider to use for this key derivation (may not be
     *     {@code null})
     *
     * @return a {@code KDF} object
     *
     * @throws NoSuchAlgorithmException
     *     if a provider is specified and it does not support the specified KDF
     *     algorithm, or if provider is {@code null} and there is no provider
     *     that supports a KDF implementation of the specified algorithm
     * @throws NullPointerException
     *     if the algorithm is {@code null}
     */
    public static KDF getInstance(String algorithm, Provider provider)
        throws NoSuchAlgorithmException {
        Objects.requireNonNull(provider, "provider may not be null");
        try {
            return getInstance(algorithm, null, provider);
        } catch (InvalidAlgorithmParameterException e) {
            throw new NoSuchAlgorithmException(
                "Received an InvalidAlgorithmParameterException. Does this "
                + "algorithm require an AlgorithmParameterSpec?", e);
        }
    }

    /**
     * Returns a {@code KDF} object that implements the specified algorithm and
     * is initialized with the specified parameters.
     *
     * @param algorithm
     *     the key derivation algorithm to use
     * @param kdfParameters
     *     the {@code KDFParameters} used to configure this KDF's algorithm or
     *     {@code null} if no additional parameters are provided
     *
     * @return a {@code KDF} object
     *
     * @throws NoSuchAlgorithmException
     *     if no {@code Provider} supports a {@code KDFSpi} implementation for
     *     the specified algorithm
     * @throws InvalidAlgorithmParameterException
     *     if the {@code AlgorithmParameterSpec} is an invalid value
     * @throws NullPointerException
     *     if the algorithm is {@code null}
     */
    public static KDF getInstance(String algorithm,
                                  KDFParameters kdfParameters)
        throws NoSuchAlgorithmException, InvalidAlgorithmParameterException {
        Objects.requireNonNull(algorithm, "null algorithm name");
        // make sure there is at least one service from a signed provider
        Iterator<Service> t = GetInstance.getServices("KDF", algorithm);
        while (t.hasNext()) {
            Service s = t.next();
            if (!JceSecurity.canUseProvider(s.getProvider())) {
                continue;
            }
            return new KDF(s, t, algorithm, kdfParameters);
        }
        throw new NoSuchAlgorithmException(
            "Algorithm " + algorithm + " not available");
    }

    /**
     * Returns a {@code KDF} object that implements the specified algorithm from
     * the specified provider and is initialized with the specified parameters.
     *
     * @param algorithm
     *     the key derivation algorithm to use
     * @param kdfParameters
     *     the {@code KDFParameters} used to configure this KDF's algorithm or
     *     {@code null} if no additional parameters are provided
     * @param provider
     *     the provider to use for this key derivation (may not be
     *     {@code null})
     *
     * @return a {@code KDF} object
     *
     * @throws NoSuchAlgorithmException
     *     if a provider is specified and it does not support the specified KDF
     *     algorithm, or if provider is {@code null} and there is no provider
     *     that supports a KDF implementation of the specified algorithm
     * @throws NoSuchProviderException
     *     if the specified provider is not registered in the security provider
     *     list
     * @throws InvalidAlgorithmParameterException
     *     if the {@code AlgorithmParameterSpec} is an invalid value
     * @throws NullPointerException
     *     if the algorithm is {@code null}
     */
    public static KDF getInstance(String algorithm,
                                  KDFParameters kdfParameters,
                                  String provider)
        throws NoSuchAlgorithmException, NoSuchProviderException,
               InvalidAlgorithmParameterException {
        Objects.requireNonNull(algorithm, "null algorithm name");
        Objects.requireNonNull(provider, "provider may not be null");
        try {
            Instance instance = GetInstance.getInstance("KDF", KDFSpi.class,
                                                        algorithm,
                                                        kdfParameters,
                                                        provider);
            if (!JceSecurity.canUseProvider(instance.provider)) {
                String msg = "JCE cannot authenticate the provider "
                             + instance.provider.getName();
                throw new NoSuchProviderException(msg);
            }
            return new KDF((KDFSpi) instance.impl, instance.provider, algorithm,
                           kdfParameters);

        } catch (NoSuchAlgorithmException nsae) {
            return handleException(nsae);
        }
    }

    /**
     * Returns a {@code KDF} object that implements the specified algorithm from
     * the specified provider and is initialized with the specified parameters.
     *
     * @param algorithm
     *     the key derivation algorithm to use
     * @param kdfParameters
     *     the {@code KDFParameters} used to configure this KDF's algorithm or
     *     {@code null} if no additional parameters are provided
     * @param provider
     *     the provider to use for this key derivation (may not be
     *     {@code null})
     *
     * @return a {@code KDF} object
     *
     * @throws NoSuchAlgorithmException
     *     if a provider is specified and it does not support the specified KDF
     *     algorithm, or if provider is {@code null} and there is no provider
     *     that supports a KDF implementation of the specified algorithm
     * @throws InvalidAlgorithmParameterException
     *     if the {@code AlgorithmParameterSpec} is an invalid value
     * @throws NullPointerException
     *     if the algorithm is {@code null}
     */
    public static KDF getInstance(String algorithm,
                                  KDFParameters kdfParameters,
                                  Provider provider)
        throws NoSuchAlgorithmException, InvalidAlgorithmParameterException {
        Objects.requireNonNull(algorithm, "null algorithm name");
        Objects.requireNonNull(provider, "provider may not be null");
        try {
            Instance instance = GetInstance.getInstance("KDF", KDFSpi.class,
                                                        algorithm,
                                                        kdfParameters,
                                                        provider);
            if (!JceSecurity.canUseProvider(instance.provider)) {
                String msg = "JCE cannot authenticate the provider "
                             + instance.provider.getName();
                throw new SecurityException(msg);
            }
            return new KDF((KDFSpi) instance.impl, instance.provider, algorithm,
                           kdfParameters);

        } catch (NoSuchAlgorithmException nsae) {
            return handleException(nsae);
        }
    }

    private static KDF handleException(NoSuchAlgorithmException e)
        throws NoSuchAlgorithmException, InvalidAlgorithmParameterException {
        Throwable cause = e.getCause();
        if (cause instanceof InvalidAlgorithmParameterException) {
            throw (InvalidAlgorithmParameterException) cause;
        }
        throw e;
    }

    /**
     * Derives a key, returned as a {@code SecretKey}.
     * <p>
     * The {@code deriveKey} method may be called multiple times at the same
     * time on a particular {@code KDF} instance.
     * <p>
     * Delayed provider selection is also supported such that the provider
     * performing the derive is not selected until the method is called. Once a
     * provider is selected, it cannot be changed.
     *
     * @param alg
     *     the algorithm of the resultant {@code SecretKey} object (may not be
     *     {@code null})
     * @param kdfParameterSpec
     *     derivation parameters
     *
     * @return a {@code SecretKey} object corresponding to a key built from the
     *     KDF output and according to the derivation parameters
     *
     * @throws InvalidAlgorithmParameterException
     *     if the information contained within the {@code KDFParameterSpec} is
     *     invalid or incorrect for the type of key to be derived
     * @throws NullPointerException
     *     if {@code alg} or {@code kdfParameterSpec} is null
     */
    public SecretKey deriveKey(String alg,
                               AlgorithmParameterSpec kdfParameterSpec)
        throws InvalidAlgorithmParameterException {

        synchronized (lock) {
            if (alg == null || alg.isEmpty()) {
                throw new NullPointerException(
                    "the algorithm for the SecretKey return value may not be "
                    + "null or empty");
            }
            Objects.requireNonNull(kdfParameterSpec);
            if (spi != null) {
                return spi.engineDeriveKey(alg, kdfParameterSpec);
            }

            Exception lastException = null;
            while ((firstService != null) || serviceIterator.hasNext()) {
                Service s;
                if (firstService != null) {
                    s = firstService;
                    firstService = null;
                } else {
                    s = serviceIterator.next();
                }
                if (!JceSecurity.canUseProvider(s.getProvider())) {
                    continue;
                }
                try {
                    KDFSpi spi = (KDFSpi) s.newInstance(kdfParameters);
                    SecretKey result = spi.engineDeriveKey(alg,
                                                           kdfParameterSpec);
                    provider = s.getProvider();
                    this.spi = spi;
                    firstService = null;
                    serviceIterator = null;
                    return result;
                } catch (Exception e) {
                    if (lastException == null) {
                        lastException = e;
                    }
                }
            }
            // no working provider found, fail
            if (lastException instanceof InvalidAlgorithmParameterException) {
                throw (InvalidAlgorithmParameterException) lastException;
            }
            if (lastException instanceof RuntimeException) {
                throw (RuntimeException) lastException;
            }
        }
        throw new InvalidAlgorithmParameterException(
            "No installed provider supports the deriveKey method with "
            + "these parameters");
    }

    /**
     * Obtains raw data from a key derivation function.
     * <p>
     * The {@code deriveData} method may be called multiple times at the same
     * time on a particular {@code KDF} instance.
     * <p>
     * Delayed provider selection is also supported such that the provider
     * performing the derive is not selected until the method is called. Once a
     * provider is selected, it cannot be changed.
     *
     * @param kdfParameterSpec
     *     derivation parameters
     *
     * @return a byte array containing a key built from the KDF output and
     *     according to the derivation parameters
     *
     * @throws InvalidAlgorithmParameterException
     *     if the information contained within the {@code KDFParameterSpec} is
     *     invalid or incorrect for the type of key to be derived
     * @throws UnsupportedOperationException
     *     if the derived key material is not extractable
     * @throws NullPointerException
     *     if {@code kdfParameterSpec} is null
     */
    public byte[] deriveData(AlgorithmParameterSpec kdfParameterSpec)
        throws InvalidAlgorithmParameterException {

        synchronized (lock) {
            Objects.requireNonNull(kdfParameterSpec);
            if (spi != null) {
                return spi.engineDeriveData(kdfParameterSpec);
            }

            Exception lastException = null;
            while ((firstService != null) || serviceIterator.hasNext()) {
                Service s;
                if (firstService != null) {
                    s = firstService;
                    firstService = null;
                } else {
                    s = serviceIterator.next();
                }
                if (!JceSecurity.canUseProvider(s.getProvider())) {
                    continue;
                }
                try {
                    KDFSpi spi = (KDFSpi) s.newInstance(kdfParameters);
                    byte[] result = spi.engineDeriveData(kdfParameterSpec);
                    provider = s.getProvider();
                    this.spi = spi;
                    firstService = null;
                    serviceIterator = null;
                    return result;
                } catch (Exception e) {
                    if (lastException == null) {
                        lastException = e;
                    }
                }
            }
            // no working provider found, fail
            if (lastException instanceof InvalidAlgorithmParameterException) {
                throw (InvalidAlgorithmParameterException) lastException;
            }
            if (lastException instanceof RuntimeException) {
                throw (RuntimeException) lastException;
            }
        }
        throw new InvalidAlgorithmParameterException(
            "No installed provider supports the deriveData method with"
            + " these parameters");
    }

    // max number of debug warnings to print from chooseFirstProvider()
    private static int warnCount = 10;

    /**
     * Choose the Spi from the first provider available. Used if delayed
     * provider selection is not possible because init() is not the first method
     * called.
     */
    void chooseFirstProvider() {
        if ((spi != null) || (serviceIterator == null)) {
            return;
        }
        synchronized (lock) {
            if (spi != null) {
                return;
            }
            Exception lastException = null;
            while ((firstService != null) || serviceIterator.hasNext()) {
                Service s;
                if (firstService != null) {
                    s = firstService;
                    firstService = null;
                } else {
                    s = serviceIterator.next();
                }
                if (!JceSecurity.canUseProvider(s.getProvider())) {
                    continue;
                }
                try {
                    Object obj = s.newInstance(kdfParameters);
                    if (!(obj instanceof KDFSpi)) {
                        continue;
                    }
                    spi = (KDFSpi) obj;
                    provider = s.getProvider();
                    // not needed any more
                    firstService = null;
                    serviceIterator = null;
                    return;
                } catch (NoSuchAlgorithmException e) {
                    lastException = e;
                }
            }
            ProviderException e = new ProviderException(
                "Could not construct KDFSpi instance");
            if (lastException != null) {
                e.initCause(lastException);
            }
            throw e;
        }
    }
}