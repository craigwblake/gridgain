// Copyright (C) GridGain Systems Licensed under GPLv3, http://www.gnu.org/licenses/gpl.html

/*
 * ________               ______                    ______   _______
 * __  ___/_____________ ____  /______ _________    __/__ \  __  __ \
 * _____ \ _  ___/_  __ `/__  / _  __ `/__  ___/    ____/ /  _  / / /
 * ____/ / / /__  / /_/ / _  /  / /_/ / _  /        _  __/___/ /_/ /
 * /____/  \___/  \__,_/  /_/   \__,_/  /_/         /____/_(_)____/
 *
 */
 
package org.gridgain.scalar.lang

import org.gridgain.grid.util.{GridUtils => U}
import org.gridgain.grid.lang.GridReducer3
import collection._

/**
 * Peer deploy aware adapter for Java's `GridReducer3`.
 *
 * @author 2012 Copyright (C) GridGain Systems
 * @version 4.0.0c.22032012
 */
class ScalarReducer3[E1, E2, E3, R](private val r: (Seq[E1], Seq[E2], Seq[E3]) => R)
    extends GridReducer3[E1, E2, E3, R] {
    assert(r != null)

    peerDeployLike(U.peerDeployAware(r))

    private val buf1 = new mutable.ListBuffer[E1]
    private val buf2 = new mutable.ListBuffer[E2]
    private val buf3 = new mutable.ListBuffer[E3]

    /**
     * Delegates to passed in function.
     */
    def apply = r(buf1.toSeq, buf2.toSeq, buf3.toSeq)

    /**
     * Collects given values.
     *
     * @param e1 Value to collect for later reduction.
     * @param e2 Value to collect for later reduction.
     * @param e3 Value to collect for later reduction.
     */
    def collect(e1: E1, e2: E2, e3: E3) = {
        buf1 += e1
        buf2 += e2
        buf3 += e3

        true
    }
}