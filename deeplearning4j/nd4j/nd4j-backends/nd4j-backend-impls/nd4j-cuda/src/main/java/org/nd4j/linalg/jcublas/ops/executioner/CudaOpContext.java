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

package org.nd4j.linalg.jcublas.ops.executioner;

import lombok.NonNull;
import lombok.val;
import org.bytedeco.javacpp.BooleanPointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.LongPointer;
import org.bytedeco.javacpp.Pointer;
import org.nd4j.jita.allocator.impl.AtomicAllocator;
import org.nd4j.jita.allocator.pointers.cuda.cudaStream_t;
import org.nd4j.linalg.api.concurrency.AffinityManager;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.BaseOpContext;
import org.nd4j.linalg.api.ops.OpContext;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.jcublas.context.CudaContext;
import org.nd4j.linalg.primitives.Pair;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.nd4j.nativeblas.OpaqueContext;
import org.nd4j.nativeblas.OpaqueRandomGenerator;

/**
 * CUDA wrapper for op Context
 * @author raver119@gmail.com
 */
public class CudaOpContext extends BaseOpContext implements OpContext {
    // we might want to have configurable
    private NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
    private OpaqueContext context = nativeOps.createGraphContext(1);

    @Override
    public void close() {
        nativeOps.deleteGraphContext(context);
    }

    @Override
    public void setIArguments(long... arguments) {
        super.setIArguments(arguments);
        nativeOps.setGraphContextIArguments(context, new LongPointer(arguments), arguments.length);
    }

    @Override
    public void setBArguments(boolean... arguments) {
        super.setBArguments(arguments);
        nativeOps.setGraphContextBArguments(context, new BooleanPointer(arguments), arguments.length);
    }

    @Override
    public void setTArguments(double... arguments) {
        super.setTArguments(arguments);
        nativeOps.setGraphContextTArguments(context, new DoublePointer(arguments), arguments.length);
    }

    @Override
    public void setRngStates(long rootState, long nodeState) {
        nativeOps.setRandomGeneratorStates(nativeOps.getGraphContextRandomGenerator(context), rootState, nodeState);
    }

    @Override
    public Pair<Long, Long> getRngStates() {
        OpaqueRandomGenerator g = nativeOps.getGraphContextRandomGenerator(context);
        return Pair.makePair(nativeOps.getRandomGeneratorRootState(g), nativeOps.getRandomGeneratorNodeState(g));
    }

    @Override
    public void setInputArray(int index, @NonNull INDArray array) {
        val ctx = AtomicAllocator.getInstance().getFlowController().prepareAction(null, array);

        nativeOps.setGraphContextInputArray(context, index, array.isEmpty() ? null : array.data().addressPointer(), array.shapeInfoDataBuffer().addressPointer(), array.isEmpty() ? null : AtomicAllocator.getInstance().getPointer(array, ctx), AtomicAllocator.getInstance().getPointer(array.shapeInfoDataBuffer()));

        super.setInputArray(index, array);
    }

    @Override
    public void setOutputArray(int index, @NonNull INDArray array) {
        val ctx = AtomicAllocator.getInstance().getFlowController().prepareAction(array, null);

        nativeOps.setGraphContextOutputArray(context, index, array.isEmpty() ? null : array.data().addressPointer(), array.shapeInfoDataBuffer().addressPointer(), array.isEmpty() ? null : AtomicAllocator.getInstance().getPointer(array, ctx), AtomicAllocator.getInstance().getPointer(array.shapeInfoDataBuffer()));

        super.setOutputArray(index, array);
    }

    @Override
    public Pointer contextPointer() {
        for (val v:fastpath_in.values()) {
            if (v.isEmpty() || v.isS())
                continue;

            AtomicAllocator.getInstance().getAllocationPoint(v).tickHostRead();
            AtomicAllocator.getInstance().getAllocationPoint(v).tickDeviceRead();

            //if (context.isInplace())
                //AtomicAllocator.getInstance().getAllocationPoint(v).tickDeviceWrite();
        }

        for (val v:fastpath_out.values()) {
            if (v.isEmpty() || v.isS())
                continue;

            AtomicAllocator.getInstance().getAllocationPoint(v).tickHostRead();
            AtomicAllocator.getInstance().getAllocationPoint(v).tickDeviceRead();
        }

        return context;
    }


    public void setCudaStream(cudaStream_t stream, Pointer reductionPointer, Pointer allocationPointer) {
        nativeOps.setGraphContextCudaContext(context, stream, reductionPointer, allocationPointer);
    }

    @Override
    public void markInplace(boolean reallyInplace) {
        nativeOps.markGraphContextInplace(context, reallyInplace);
    }
}
