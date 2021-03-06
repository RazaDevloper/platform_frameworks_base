/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.net;

import static android.net.IpSecManager.INVALID_RESOURCE_ID;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import dalvik.system.CloseGuard;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;

/**
 * This class represents a transform, which roughly corresponds to an IPsec Security Association.
 *
 * <p>Transforms are created using {@link IpSecTransform.Builder}. Each {@code IpSecTransform}
 * object encapsulates the properties and state of an IPsec security association. That includes,
 * but is not limited to, algorithm choice, key material, and allocated system resources.
 *
 * @see <a href="https://tools.ietf.org/html/rfc4301">RFC 4301, Security Architecture for the
 *     Internet Protocol</a>
 */
public final class IpSecTransform implements AutoCloseable {
    private static final String TAG = "IpSecTransform";

    /** @hide */
    public static final int MODE_TRANSPORT = 0;

    /** @hide */
    public static final int MODE_TUNNEL = 1;

    /** @hide */
    public static final int ENCAP_NONE = 0;

    /**
     * IPsec traffic will be encapsulated within UDP, but with 8 zero-value bytes between the UDP
     * header and payload. This prevents traffic from being interpreted as ESP or IKEv2.
     *
     * @hide
     */
    public static final int ENCAP_ESPINUDP_NON_IKE = 1;

    /**
     * IPsec traffic will be encapsulated within UDP as per
     * <a href="https://tools.ietf.org/html/rfc3948">RFC 3498</a>.
     *
     * @hide
     */
    public static final int ENCAP_ESPINUDP = 2;

    /** @hide */
    @IntDef(value = {ENCAP_NONE, ENCAP_ESPINUDP, ENCAP_ESPINUDP_NON_IKE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface EncapType {}

    private IpSecTransform(Context context, IpSecConfig config) {
        mContext = context;
        mConfig = config;
        mResourceId = INVALID_RESOURCE_ID;
    }

    private IIpSecService getIpSecService() {
        IBinder b = ServiceManager.getService(android.content.Context.IPSEC_SERVICE);
        if (b == null) {
            throw new RemoteException("Failed to connect to IpSecService")
                    .rethrowAsRuntimeException();
        }

        return IIpSecService.Stub.asInterface(b);
    }

    /**
     * Checks the result status and throws an appropriate exception if the status is not Status.OK.
     */
    private void checkResultStatus(int status)
            throws IOException, IpSecManager.ResourceUnavailableException,
                    IpSecManager.SpiUnavailableException {
        switch (status) {
            case IpSecManager.Status.OK:
                return;
                // TODO: Pass Error string back from bundle so that errors can be more specific
            case IpSecManager.Status.RESOURCE_UNAVAILABLE:
                throw new IpSecManager.ResourceUnavailableException(
                        "Failed to allocate a new IpSecTransform");
            case IpSecManager.Status.SPI_UNAVAILABLE:
                Log.wtf(TAG, "Attempting to use an SPI that was somehow not reserved");
                // Fall through
            default:
                throw new IllegalStateException(
                        "Failed to Create a Transform with status code " + status);
        }
    }

    private IpSecTransform activate()
            throws IOException, IpSecManager.ResourceUnavailableException,
                    IpSecManager.SpiUnavailableException {
        synchronized (this) {
            try {
                IIpSecService svc = getIpSecService();
                IpSecTransformResponse result =
                        svc.createTransportModeTransform(mConfig, new Binder());
                int status = result.status;
                checkResultStatus(status);
                mResourceId = result.resourceId;

                /* Keepalive will silently fail if not needed by the config; but, if needed and
                 * it fails to start, we need to bail because a transform will not be reliable
                 * to use if keepalive is expected to offload and fails.
                 */
                // FIXME: if keepalive fails, we need to fail spectacularly
                startKeepalive(mContext);
                Log.d(TAG, "Added Transform with Id " + mResourceId);
                mCloseGuard.open("build");
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        return this;
    }

    /**
     * Deactivate this {@code IpSecTransform} and free allocated resources.
     *
     * <p>Deactivating a transform while it is still applied to a socket will result in errors on
     * that socket. Make sure to remove transforms by calling {@link
     * IpSecManager#removeTransportModeTransforms}. Note, removing an {@code IpSecTransform} from a
     * socket will not deactivate it (because one transform may be applied to multiple sockets).
     *
     * <p>It is safe to call this method on a transform that has already been deactivated.
     */
    public void close() {
        Log.d(TAG, "Removing Transform with Id " + mResourceId);

        // Always safe to attempt cleanup
        if (mResourceId == INVALID_RESOURCE_ID) {
            mCloseGuard.close();
            return;
        }
        try {
            /* Order matters here because the keepalive is best-effort but could fail in some
             * horrible way to be removed if the wifi (or cell) subsystem has crashed, and we
             * still want to clear out the transform.
             */
            IIpSecService svc = getIpSecService();
            svc.deleteTransportModeTransform(mResourceId);
            stopKeepalive();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        } finally {
            mResourceId = INVALID_RESOURCE_ID;
            mCloseGuard.close();
        }
    }

    /** Check that the transform was closed properly. */
    @Override
    protected void finalize() throws Throwable {
        if (mCloseGuard != null) {
            mCloseGuard.warnIfOpen();
        }
        close();
    }

    /* Package */
    IpSecConfig getConfig() {
        return mConfig;
    }

    private final IpSecConfig mConfig;
    private int mResourceId;
    private final Context mContext;
    private final CloseGuard mCloseGuard = CloseGuard.get();
    private ConnectivityManager.PacketKeepalive mKeepalive;
    private int mKeepaliveStatus = ConnectivityManager.PacketKeepalive.NO_KEEPALIVE;
    private Object mKeepaliveSyncLock = new Object();
    private ConnectivityManager.PacketKeepaliveCallback mKeepaliveCallback =
            new ConnectivityManager.PacketKeepaliveCallback() {

                @Override
                public void onStarted() {
                    synchronized (mKeepaliveSyncLock) {
                        mKeepaliveStatus = ConnectivityManager.PacketKeepalive.SUCCESS;
                        mKeepaliveSyncLock.notifyAll();
                    }
                }

                @Override
                public void onStopped() {
                    synchronized (mKeepaliveSyncLock) {
                        mKeepaliveStatus = ConnectivityManager.PacketKeepalive.NO_KEEPALIVE;
                        mKeepaliveSyncLock.notifyAll();
                    }
                }

                @Override
                public void onError(int error) {
                    synchronized (mKeepaliveSyncLock) {
                        mKeepaliveStatus = error;
                        mKeepaliveSyncLock.notifyAll();
                    }
                }
            };

    /* Package */
    void startKeepalive(Context c) {
        if (mConfig.getNattKeepaliveInterval() != 0) {
            Log.wtf(TAG, "Keepalive not yet supported.");
        }
    }

    /** @hide */
    @VisibleForTesting
    public int getResourceId() {
        return mResourceId;
    }

    /* Package */
    void stopKeepalive() {
        return;
    }

    /** This class is used to build {@link IpSecTransform} objects. */
    public static class Builder {
        private Context mContext;
        private IpSecConfig mConfig;

        /**
         * Set the encryption algorithm.
         *
         * <p>Encryption is mutually exclusive with authenticated encryption.
         *
         * @param algo {@link IpSecAlgorithm} specifying the encryption to be applied.
         */
        public IpSecTransform.Builder setEncryption(@NonNull IpSecAlgorithm algo) {
            // TODO: throw IllegalArgumentException if algo is not an encryption algorithm.
            Preconditions.checkNotNull(algo);
            mConfig.setEncryption(algo);
            return this;
        }

        /**
         * Set the authentication (integrity) algorithm.
         *
         * <p>Authentication is mutually exclusive with authenticated encryption.
         *
         * @param algo {@link IpSecAlgorithm} specifying the authentication to be applied.
         */
        public IpSecTransform.Builder setAuthentication(@NonNull IpSecAlgorithm algo) {
            // TODO: throw IllegalArgumentException if algo is not an authentication algorithm.
            Preconditions.checkNotNull(algo);
            mConfig.setAuthentication(algo);
            return this;
        }

        /**
         * Set the authenticated encryption algorithm.
         *
         * <p>The Authenticated Encryption (AE) class of algorithms are also known as
         * Authenticated Encryption with Associated Data (AEAD) algorithms, or Combined mode
         * algorithms (as referred to in
         * <a href="https://tools.ietf.org/html/rfc4301">RFC 4301</a>).
         *
         * <p>Authenticated encryption is mutually exclusive with encryption and authentication.
         *
         * @param algo {@link IpSecAlgorithm} specifying the authenticated encryption algorithm to
         *     be applied.
         */
        public IpSecTransform.Builder setAuthenticatedEncryption(@NonNull IpSecAlgorithm algo) {
            Preconditions.checkNotNull(algo);
            mConfig.setAuthenticatedEncryption(algo);
            return this;
        }

        /**
         * Set the {@link Network} which will carry tunneled traffic.
         *
         * <p>Restricts the transformed traffic to a particular {@link Network}. This is required
         * for tunnel mode, otherwise tunneled traffic would be sent on the default network.
         *
         * @hide
         */
        @SystemApi
        public IpSecTransform.Builder setUnderlyingNetwork(@NonNull Network net) {
            Preconditions.checkNotNull(net);
            mConfig.setNetwork(net);
            return this;
        }

        /**
         * Add UDP encapsulation to an IPv4 transform.
         *
         * <p>This allows IPsec traffic to pass through a NAT.
         *
         * @see <a href="https://tools.ietf.org/html/rfc3948">RFC 3948, UDP Encapsulation of IPsec
         *     ESP Packets</a>
         * @see <a href="https://tools.ietf.org/html/rfc7296#section-2.23">RFC 7296 section 2.23,
         *     NAT Traversal of IKEv2</a>
         * @param localSocket a socket for sending and receiving encapsulated traffic
         * @param remotePort the UDP port number of the remote host that will send and receive
         *     encapsulated traffic. In the case of IKEv2, this should be port 4500.
         */
        public IpSecTransform.Builder setIpv4Encapsulation(
                @NonNull IpSecManager.UdpEncapsulationSocket localSocket, int remotePort) {
            Preconditions.checkNotNull(localSocket);
            mConfig.setEncapType(ENCAP_ESPINUDP);
            if (localSocket.getResourceId() == INVALID_RESOURCE_ID) {
                throw new IllegalArgumentException("Invalid UdpEncapsulationSocket");
            }
            mConfig.setEncapSocketResourceId(localSocket.getResourceId());
            mConfig.setEncapRemotePort(remotePort);
            return this;
        }

        // TODO: Decrease the minimum keepalive to maybe 10?
        // TODO: Probably a better exception to throw for NATTKeepalive failure
        // TODO: Specify the needed NATT keepalive permission.
        /**
         * Set NAT-T keepalives to be sent with a given interval.
         *
         * <p>This will set power-efficient keepalive packets to be sent by the system. If NAT-T
         * keepalive is requested but cannot be activated, then creation of an {@link
         * IpSecTransform} will fail when calling the build method.
         *
         * @param intervalSeconds the maximum number of seconds between keepalive packets. Must be
         *     between 20s and 3600s.
         * @hide
         */
        @SystemApi
        public IpSecTransform.Builder setNattKeepalive(int intervalSeconds) {
            mConfig.setNattKeepaliveInterval(intervalSeconds);
            return this;
        }

        /**
         * Build a transport mode {@link IpSecTransform}.
         *
         * <p>This builds and activates a transport mode transform. Note that an active transform
         * will not affect any network traffic until it has been applied to one or more sockets.
         *
         * @see IpSecManager#applyTransportModeTransform
         * @param sourceAddress the source {@code InetAddress} of traffic on sockets that will use
         *     this transform; this address must belong to the Network used by all sockets that
         *     utilize this transform; if provided, then only traffic originating from the
         *     specified source address will be processed.
         * @param spi a unique {@link IpSecManager.SecurityParameterIndex} to identify transformed
         *     traffic
         * @throws IllegalArgumentException indicating that a particular combination of transform
         *     properties is invalid
         * @throws IpSecManager.ResourceUnavailableException indicating that too many transforms
         *     are active
         * @throws IpSecManager.SpiUnavailableException indicating the rare case where an SPI
         *     collides with an existing transform
         * @throws IOException indicating other errors
         */
        public IpSecTransform buildTransportModeTransform(
                @NonNull InetAddress sourceAddress,
                @NonNull IpSecManager.SecurityParameterIndex spi)
                throws IpSecManager.ResourceUnavailableException,
                        IpSecManager.SpiUnavailableException, IOException {
            Preconditions.checkNotNull(sourceAddress);
            Preconditions.checkNotNull(spi);
            if (spi.getResourceId() == INVALID_RESOURCE_ID) {
                throw new IllegalArgumentException("Invalid SecurityParameterIndex");
            }
            mConfig.setMode(MODE_TRANSPORT);
            mConfig.setSourceAddress(sourceAddress.getHostAddress());
            mConfig.setSpiResourceId(spi.getResourceId());
            // FIXME: modifying a builder after calling build can change the built transform.
            return new IpSecTransform(mContext, mConfig).activate();
        }

        /**
         * Build and return an {@link IpSecTransform} object as a Tunnel Mode Transform. Some
         * parameters have interdependencies that are checked at build time.
         *
         * @param sourceAddress the {@link InetAddress} that provides the source address for this
         *     IPsec tunnel. This is almost certainly an address belonging to the {@link Network}
         *     that will originate the traffic, which is set as the {@link #setUnderlyingNetwork}.
         * @param spi a unique {@link IpSecManager.SecurityParameterIndex} to identify transformed
         *     traffic
         * @throws IllegalArgumentException indicating that a particular combination of transform
         *     properties is invalid.
         * @throws IpSecManager.ResourceUnavailableException indicating that too many transforms
         *     are active
         * @throws IpSecManager.SpiUnavailableException indicating the rare case where an SPI
         *     collides with an existing transform
         * @throws IOException indicating other errors
         * @hide
         */
        public IpSecTransform buildTunnelModeTransform(
                @NonNull InetAddress sourceAddress,
                @NonNull IpSecManager.SecurityParameterIndex spi)
                throws IpSecManager.ResourceUnavailableException,
                        IpSecManager.SpiUnavailableException, IOException {
            Preconditions.checkNotNull(sourceAddress);
            Preconditions.checkNotNull(spi);
            if (spi.getResourceId() == INVALID_RESOURCE_ID) {
                throw new IllegalArgumentException("Invalid SecurityParameterIndex");
            }
            mConfig.setMode(MODE_TUNNEL);
            mConfig.setSourceAddress(sourceAddress.getHostAddress());
            mConfig.setSpiResourceId(spi.getResourceId());
            return new IpSecTransform(mContext, mConfig);
        }

        /**
         * Create a new IpSecTransform.Builder.
         *
         * @param context current context
         */
        public Builder(@NonNull Context context) {
            Preconditions.checkNotNull(context);
            mContext = context;
            mConfig = new IpSecConfig();
        }
    }
}
