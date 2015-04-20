/*
 * Copyright 2015 Christopher Blay <chris.b.blay@gmail.com>
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

import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class BufferHolder {

    private static final String TAG = BufferHolder.class.getSimpleName();
    private final byte[] mSizeBytes;
    public final ByteBuffer buffer;
    public int size;

    public BufferHolder() {
        mSizeBytes = new byte[2];
        buffer = ByteBuffer.allocate(0xffff);
    }

    void reset() {
        buffer.clear();
        size = 0;
    }

    @Override
    public String toString() {
        return new String(buffer.array(), 0, size);
    }

    boolean read(final FileInputStream inputStream) {
        if (size <= 0) {
            final int bytesRead;
            try {
                bytesRead = inputStream.read(mSizeBytes);
            } catch (IOException exception) {
                Log.d(TAG, "IOException while reading size bytes", exception);
                return false;
            }
            if (bytesRead != mSizeBytes.length) {
                Log.d(TAG, "Incorrect number of bytes read while reading size bytes:"
                        + " actual=" + bytesRead + " expected=" + mSizeBytes.length);
                return false;
            }
            size = readSizeBytes();
        }
        final int bytesRead;
        try {
            bytesRead = inputStream.read(buffer.array(), 0, size);
        } catch (IOException exception) {
            Log.d(TAG, "IOException while reading data bytes", exception);
            return false;
        }
        if (bytesRead != size) {
            Log.d(TAG, "Incorrect number of bytes read while reading data bytes:"
                    + " actual=" + bytesRead + " expected=" + size);
            return false;
        }
        return true;
    }

    boolean write(final FileOutputStream outputStream) {
        writeSizeBytes(size);
        try {
            outputStream.write(mSizeBytes);
            outputStream.write(buffer.array(), 0, size);
            outputStream.flush();
            return true;
        } catch (IOException exception) {
            Log.d(TAG, "IOException while writing size+data bytes", exception);
            return false;
        }
    }

    private int readSizeBytes() {
        return ((mSizeBytes[0] & 0xff) << 8) | (mSizeBytes[1] & 0xff);
    }

    private void writeSizeBytes(final int value) {
        if (BuildConfig.DEBUG && (value <= 0 || value > 0xffff)) {
            throw new AssertionError("Size value out of bounds: " + value);
        }
        mSizeBytes[0] = (byte) ((value & 0xff00) >> 8);
        mSizeBytes[1] = (byte) (value & 0x00ff);
    }

}
