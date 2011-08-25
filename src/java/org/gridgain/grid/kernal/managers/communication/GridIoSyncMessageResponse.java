// Copyright (C) GridGain Systems, Inc. Licensed under GPLv3, http://www.gnu.org/licenses/gpl.html

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.managers.communication;

import org.gridgain.grid.typedef.internal.*;
import java.io.*;

/**
 * Response message participating in synchronous communication. It wraps actual
 * result generated by synchronous request handler.
 *
 * @author 2005-2011 Copyright (C) GridGain Systems, Inc.
 * @version 3.5.0c.24082011
 */
class GridIoSyncMessageResponse implements Externalizable {
    /** */
    private long reqId;

    /** */
    private Object res;

    /** */
    private Throwable ex;

    /** */
    public GridIoSyncMessageResponse() {
        // No-op.
    }

    /**
     * @param reqId Request id.
     * @param ex Exception occurred while handling request.
     */
    GridIoSyncMessageResponse(long reqId, Throwable ex) {
        this.reqId = reqId;
        this.ex = ex;
    }

    /**
     * @param reqId Request id.
     * @param res Actual result carried by this object.
     */
    GridIoSyncMessageResponse(long reqId, Object res) {
        this.reqId = reqId;
        this.res = res;
    }

    /**
     * @return Request id.
     */
    public long getRequestId() {
        return reqId;
    }

    /**
     * @return Result.
     */
    public Object getResult() {
        return res;
    }

    /**
     * @return Exception occurred while handling request.
     */
    public Throwable getException() {
        return ex;
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        out.writeLong(reqId);
        out.writeObject(ex);
        out.writeObject(res);
    }

    /** {@inheritDoc} */
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        reqId = in.readLong();
        ex = (Throwable)in.readObject();
        res = in.readObject();
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridIoSyncMessageResponse.class, this);
    }
}
