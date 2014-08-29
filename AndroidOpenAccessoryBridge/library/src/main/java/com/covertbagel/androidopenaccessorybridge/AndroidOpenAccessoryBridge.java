/*
 * Copyright 2014 Christopher Blay <chris.b.blay@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.covertbagel.androidopenaccessorybridge;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class AndroidOpenAccessoryBridge {

    private static final String TAG =
            AndroidOpenAccessoryBridge.class.getSimpleName();
    private static final long CONNECT_COOLDOWN_MS = 100;
    private static final long READ_COOLDOWN_MS = 100;

    private Listener mListener;
    private UsbManager mUsbManager;
    private BufferHolder mReadBuffer;
    private InternalThread mInternalThread;
    private boolean mIsShutdown;
    private boolean mIsAttached;
    private FileOutputStream mOutputStream;
    private FileInputStream mInputStream;
    private ParcelFileDescriptor mParcelFileDescriptor;

    public AndroidOpenAccessoryBridge(Context context, Listener listener) {
        if (BuildConfig.DEBUG && (context == null || listener == null)) {
            throw new AssertionError("Arguments context and listener must not be null");
        }
        mListener = listener;
        mUsbManager =
                (UsbManager) context.getSystemService(Context.USB_SERVICE);
        mReadBuffer = new BufferHolder();
        mInternalThread = new InternalThread();
        mInternalThread.start();
    }

    public synchronized boolean write(BufferHolder bufferHolder) {
        if (BuildConfig.DEBUG && (mIsShutdown || mOutputStream == null)) {
            throw new AssertionError("Can't write if shutdown or output stream is null");
        }
        return bufferHolder.write(mOutputStream);
    }

    @SuppressLint("HandlerLeak")
    private class InternalThread extends Thread {

        private static final int STOP_THREAD = 1;
        private static final int MAYBE_READ = 2;

        private Handler mHandler;

        @Override
        public void run() {
            Looper.prepare();
            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                    case STOP_THREAD:
                        Looper.myLooper().quit();
                        break;
                    case MAYBE_READ:
                        if (mReadBuffer.read(mInputStream)) {
                            if (mReadBuffer.size == 0) {
                                mHandler.sendEmptyMessage(STOP_THREAD);
                            } else {
                                mListener.onAoabRead(mReadBuffer);
                                mReadBuffer.reset();
                                mHandler.sendEmptyMessage(MAYBE_READ);
                            }
                        } else {
                            mHandler.sendEmptyMessageDelayed(
                                    MAYBE_READ, READ_COOLDOWN_MS);
                        }
                        break;
                    }
                }
            };
            detectAccessory();
            Looper.loop();
            detachAccessory();
            mIsShutdown = true;
            mListener.onAoabShutdown();

            // Clean stuff up
            mHandler = null;
            mListener = null;
            mUsbManager = null;
            mReadBuffer = null;
            mInternalThread = null;
        }

        private void detectAccessory() {
            while (!mIsAttached) {
                if (mIsShutdown) {
                    mHandler.sendEmptyMessage(STOP_THREAD);
                    return;
                }
                try {
                    Thread.sleep(CONNECT_COOLDOWN_MS);
                } catch (InterruptedException e) {
                    // pass
                }
                final UsbAccessory[] accessoryList =
                        mUsbManager.getAccessoryList();
                if (accessoryList == null || accessoryList.length == 0) {
                    continue;
                }
                if (accessoryList.length > 1) {
                    Log.w(TAG, "Multiple accessories attached!? Trying first");
                }
                maybeAttachAccessory(accessoryList[0]);
            }
        }

        private void maybeAttachAccessory(final UsbAccessory accessory) {
            final ParcelFileDescriptor parcelFileDescriptor =
                    mUsbManager.openAccessory(accessory);
            if (parcelFileDescriptor != null) {
                final FileDescriptor fileDescriptor =
                        parcelFileDescriptor.getFileDescriptor();
                mIsAttached = true;
                mOutputStream = new FileOutputStream(fileDescriptor);
                mInputStream = new FileInputStream(fileDescriptor);
                mParcelFileDescriptor = parcelFileDescriptor;
                mHandler.sendEmptyMessage(MAYBE_READ);
            }
        }

        private void detachAccessory() {
            if (mIsAttached) {
                mIsAttached = false;
            }
            if (mInputStream != null) {
                closeQuietly(mInputStream);
                mInputStream = null;
            }
            if (mOutputStream != null) {
                closeQuietly(mOutputStream);
                mOutputStream = null;
            }
            if (mParcelFileDescriptor != null) {
                closeQuietly(mParcelFileDescriptor);
                mParcelFileDescriptor = null;
            }
        }

        private void closeQuietly(Closeable closable) {
            try {
                closable.close();
            } catch (IOException e) {
                // pass
            }
        }

    }

    public static interface Listener {
        public void onAoabRead(BufferHolder bufferHolder);
        public void onAoabShutdown();
    }

    public static final class BufferHolder {

        private final byte[] mSizeBytes;

        public final ByteBuffer buffer;
        public int size;

        public BufferHolder() {
            mSizeBytes = new byte[2];
            buffer = ByteBuffer.allocate(65535);
        }

        public void reset() {
            buffer.clear();
            size = 0;
        }

        @Override
        public String toString() {
            return new String(buffer.array(), 0, size);
        }

        private boolean read(final FileInputStream inputStream) {
            if (size <= 0) {
                final int bytesRead;
                try {
                    bytesRead = inputStream.read(mSizeBytes);
                } catch (IOException e) {
                    Log.d(TAG, "IOException while reading size bytes", e);
                    return false;
                }
                if (bytesRead != mSizeBytes.length) {
                    Log.d(TAG, "Incorrect number of bytes read while reading size bytes");
                    return false;
                }
                size = readSizeBytes();
            }
            final int bytesRead;
            try {
                bytesRead = inputStream.read(buffer.array(), 0, size);
            } catch (IOException e) {
                Log.d(TAG, "IOException while reading data bytes", e);
                return false;
            }
            if (bytesRead != size) {
                Log.d(TAG, "Incorrect number of bytes read while reading data bytes");
                return false;
            }
            return true;
        }

        private boolean write(final FileOutputStream outputStream) {
            writeSizeBytes(size);
            try {
                outputStream.write(mSizeBytes);
                outputStream.write(buffer.array(), 0, size);
                outputStream.flush();
                return true;
            } catch (IOException e) {
                Log.d(TAG, "IOException while writing size+data bytes", e);
                return false;
            }
        }

        private int readSizeBytes() {
            return ((mSizeBytes[0] & 0xff) << 8) | (mSizeBytes[1] & 0xff);
        }

        private void writeSizeBytes(final int value) {
            if (BuildConfig.DEBUG && (value <= 0 || value > 0xffff)) {
                throw new AssertionError("Size value out of bounds");
            }
            mSizeBytes[0] = (byte) ((value & 0xff00) >> 8);
            mSizeBytes[1] = (byte) (value & 0x00ff);
        }

    }

}
