/*
 * Copyright (c) 2014, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package org.openjdk.bench.java.math;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.math.BigInteger;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3)
public class BigIntegerSquareRoot {

    private BigInteger[] hugeArray, largeArray, smallArray;
    private static final int TESTSIZE = 1000;

    @Setup
    public void setup() {
        Random r = new Random(1123);

        hugeArray = new BigInteger[TESTSIZE]; /*
         * Huge numbers larger than
         * MAX_LONG
         */
        largeArray = new BigInteger[TESTSIZE]; /*
         * Large numbers less than
         * MAX_LONG but larger than
         * MAX_INT
         */
        smallArray = new BigInteger[TESTSIZE]; /*
         * Small number less than
         * MAX_INT
         */

        for (int i = 0; i < TESTSIZE; i++) {
            long value = Math.abs((long) r.nextInt());

            hugeArray[i] = new BigInteger("" + (value + (long) Integer.MAX_VALUE)
                    + (value + (long) Integer.MAX_VALUE));
            largeArray[i] = new BigInteger("" + (value + (long) Integer.MAX_VALUE));
            smallArray[i] = new BigInteger("" + (value / 1000));
        }
    }

    /** Test BigInteger.sqrtAndRemainder() with huge numbers larger than MAX_LONG */
    @Benchmark
    @OperationsPerInvocation(TESTSIZE)
    public void testHugeSqrtAndRemainder(Blackhole bh) {
        for (BigInteger s : hugeArray) {
            bh.consume(s.sqrtAndRemainder());
        }
    }

    /** Test BigInteger.sqrtAndRemainder() with large numbers less than MAX_LONG but larger than MAX_INT */
    @Benchmark
    @OperationsPerInvocation(TESTSIZE)
    public void testLargeSqrtAndRemainder(Blackhole bh) {
        for (BigInteger s : largeArray) {
            bh.consume(s.sqrtAndRemainder());
        }
    }

    /** Test BigInteger.sqrtAndRemainder() with small numbers less than MAX_INT */
    @Benchmark
    @OperationsPerInvocation(TESTSIZE)
    public void testSmallSqrtAndRemainder(Blackhole bh) {
        for (BigInteger s : smallArray) {
            bh.consume(s.sqrtAndRemainder());
        }
    }
}
