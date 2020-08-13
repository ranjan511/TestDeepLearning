/*******************************************************************************
 * Copyright (c) 2015-2018 Skymind, Inc.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

package org.datavec.python;

import lombok.Getter;
import org.bytedeco.javacpp.Pointer;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.nd4j.linalg.api.buffer.DataType;



/**
 * Wrapper around INDArray for initializing from numpy array
 *
 * @author Fariz Rahman
 */
@Getter
public class NumpyArray {

    private static NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
    private long address;
    private long[] shape;
    private long[] strides;
    private DataType dtype = DataType.FLOAT;
    private INDArray nd4jArray;

    public NumpyArray(long address, long[] shape, long strides[], boolean copy){
        this.address = address;
        this.shape = shape;
        this.strides = strides;
        setND4JArray();
        if (copy){
            nd4jArray = nd4jArray.dup();
            this.address = nd4jArray.data().address();

        }
    }

    public NumpyArray copy(){
        return new NumpyArray(nd4jArray.dup());
    }
    public NumpyArray(long address, long[] shape, long strides[]){
        this(address, shape, strides, false);
    }

    public NumpyArray(long address, long[] shape, long strides[], DataType dtype){
        this(address, shape, strides, dtype, false);
    }

    public NumpyArray(long address, long[] shape, long strides[], DataType dtype, boolean copy){
        this.address = address;
        this.shape = shape;
        this.strides = strides;
        this.dtype = dtype;
        setND4JArray();
        if (copy){
            nd4jArray = nd4jArray.dup();
            this.address = nd4jArray.data().address();
        }
    }

    private void setND4JArray(){
        long size = 1;
        for(long d: shape){
            size *= d;
        }
        Pointer ptr = nativeOps.pointerForAddress(address);
        ptr = ptr.limit(size);
        ptr = ptr.capacity(size);
        DataBuffer buff = Nd4j.createBuffer(ptr, size, dtype);
        int elemSize = buff.getElementSize();
        long[] nd4jStrides = new long[strides.length];
        for (int i=0; i<strides.length; i++){
            nd4jStrides[i] = strides[i] / elemSize;
        }
        this.nd4jArray = Nd4j.create(buff, shape, nd4jStrides, 0, 'c', dtype);

    }

    public NumpyArray(INDArray nd4jArray){
        DataBuffer buff = nd4jArray.data();
        address = buff.pointer().address();
        shape = nd4jArray.shape();
        long[] nd4jStrides = nd4jArray.stride();
        strides = new long[nd4jStrides.length];
        int elemSize = buff.getElementSize();
        for(int i=0; i<strides.length; i++){
            strides[i] = nd4jStrides[i] * elemSize;
        }
        dtype = nd4jArray.dataType();
        this.nd4jArray = nd4jArray;
    }

}
