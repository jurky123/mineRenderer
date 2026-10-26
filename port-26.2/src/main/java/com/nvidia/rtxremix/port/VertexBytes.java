/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;
import java.nio.*;
import java.util.Arrays;
/** Reusable growing CPU buffer; no per-vertex heap objects. */
public final class VertexBytes {
    private ByteBuffer buffer=ByteBuffer.allocate(4096).order(ByteOrder.nativeOrder());
    public ByteBuffer append(int bytes) {
        if(buffer.remaining()<bytes) {
            ByteBuffer next=ByteBuffer.allocate(Math.max(buffer.capacity()*2,buffer.position()+bytes)).order(ByteOrder.nativeOrder());
            buffer.flip();next.put(buffer);buffer=next;
        }
        return buffer;
    }
    public int size(){return buffer.position();}
    public byte[] copy(){return Arrays.copyOf(buffer.array(),buffer.position());}
}
