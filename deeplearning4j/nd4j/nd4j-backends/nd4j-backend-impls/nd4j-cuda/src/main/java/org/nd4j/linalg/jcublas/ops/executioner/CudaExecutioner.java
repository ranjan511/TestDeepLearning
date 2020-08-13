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


import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import lombok.var;
import org.bytedeco.javacpp.*;
import org.bytedeco.javacpp.indexer.LongIndexer;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.serde.FlatBuffersMapper;
import org.nd4j.base.Preconditions;
import org.nd4j.jita.allocator.impl.AllocationPoint;
import org.nd4j.jita.allocator.impl.AtomicAllocator;
import org.nd4j.jita.allocator.pointers.CudaPointer;
import org.nd4j.jita.allocator.tad.DeviceTADManager;
import org.nd4j.jita.allocator.utils.AllocationUtils;
import org.nd4j.jita.conf.CudaEnvironment;
import org.nd4j.linalg.api.buffer.BaseDataBuffer;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.buffer.Utf8Buffer;
import org.nd4j.linalg.api.environment.Nd4jEnvironment;
import org.nd4j.linalg.api.memory.pointers.PagedPointer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ndarray.INDArrayStatistics;
import org.nd4j.linalg.api.ops.*;
import org.nd4j.linalg.api.ops.aggregates.Aggregate;
import org.nd4j.linalg.api.ops.aggregates.Batch;
import org.nd4j.linalg.api.ops.executioner.DefaultOpExecutioner;
import org.nd4j.linalg.api.ops.executioner.OpStatus;
import org.nd4j.linalg.api.ops.impl.scatter.ScatterUpdate;
import org.nd4j.linalg.api.ops.impl.summarystats.Variance;
import org.nd4j.linalg.api.ops.impl.transforms.pairwise.arithmetic.CopyOp;
import org.nd4j.linalg.api.ops.performance.PerformanceTracker;
import org.nd4j.linalg.api.rng.Random;
import org.nd4j.linalg.api.shape.LongShapeDescriptor;
import org.nd4j.linalg.api.shape.Shape;
import org.nd4j.linalg.api.shape.TadPack;
import org.nd4j.linalg.api.shape.options.ArrayOptionsHelper;
import org.nd4j.linalg.api.shape.options.ArrayType;
import org.nd4j.linalg.cache.TADManager;
import org.nd4j.linalg.compression.ThresholdCompression;
import org.nd4j.linalg.exception.ND4JIllegalArgumentException;
import org.nd4j.linalg.exception.ND4JIllegalStateException;
import org.nd4j.linalg.exception.ND4JOpProfilerException;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.jcublas.buffer.AddressRetriever;
import org.nd4j.linalg.jcublas.buffer.BaseCudaDataBuffer;
import org.nd4j.linalg.jcublas.buffer.CudaLongDataBuffer;
import org.nd4j.linalg.jcublas.context.CudaContext;
import org.nd4j.linalg.primitives.AtomicBoolean;
import org.nd4j.linalg.primitives.Pair;
import org.nd4j.linalg.util.ArrayUtil;
import org.nd4j.nativeblas.LongPointerWrapper;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.nd4j.nativeblas.Nd4jCuda;
import org.nd4j.nativeblas.OpaqueConstantDataBuffer;
import org.nd4j.nativeblas.OpaqueShapeList;
import org.nd4j.nativeblas.OpaqueTadPack;
import org.nd4j.nativeblas.OpaqueVariable;
import org.nd4j.nativeblas.OpaqueVariablesSet;

import java.util.*;


/**
 * JCuda executioner.
 * <p/>
 * Runs ops directly on the gpu
 *
 * If requested Op doesn't exist within GPU context, DefaultOpExecutioner will be used, with arrays/buffers updated after that.
 *
 * @author Adam Gibson
 * @author raver119@gmail.com
 */
@Slf4j
public class CudaExecutioner extends DefaultOpExecutioner {

    protected static NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();

    //    private static final Allocator allocator = AtomicAllocator.getInstance();

    @Getter
    protected static TADManager tadManager = new DeviceTADManager();
    protected ThreadLocal<PointerPointer> extraz = new ThreadLocal<>();
    protected volatile transient Properties properties;

    protected ThreadLocal<String> lastOp = new ThreadLocal<>();

    protected Map<String, CustomOpDescriptor> customOps = null;

    protected AtomicBoolean experimentalMode = new AtomicBoolean(false);

    public CudaExecutioner() {
        experimentalMode.set(nativeOps.isExperimentalEnabled());
    }

    public NativeOps getNativeOps() {
        return nativeOps;
    }

    @Override
    public String getLastOp() {
        return lastOp.get();
    }

    @Override
    public INDArray exec(BroadcastOp op) {
        long st = profilingConfigurableHookIn(op);

        checkForCompression(op);

        val dimension = op.dimensions().toIntVector();

//        validateDataType(Nd4j.dataType(), op);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());

        if (CudaEnvironment.getInstance().getConfiguration().isDebug())
            lastOp.set(op.opName());

        Pointer hostYShapeInfo =
                op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        Pointer hostZShapeInfo =
                op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        Pointer x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        Pointer y = AtomicAllocator.getInstance().getPointer(op.y(), context);
        Pointer z = AtomicAllocator.getInstance().getPointer(op.z(), context);
        Pointer xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);

        Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), dimension);

        Pointer hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
        Pointer devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

        DataBuffer offsets = tadBuffers.getSecond();
        Pointer devTadOffsets = AtomicAllocator.getInstance().getPointer(offsets, context);

        Pointer devTadShapeInfoZ = null;
        Pointer devTadOffsetsZ = null;

        // that's the place where we're going to have second TAD in place
        Pair<DataBuffer, DataBuffer> tadBuffersZ = tadManager.getTADOnlyShapeInfo(op.z(), dimension);

        devTadShapeInfoZ = AtomicAllocator.getInstance().getPointer(tadBuffersZ.getFirst(), context);
        devTadOffsetsZ = AtomicAllocator.getInstance().getPointer(tadBuffersZ.getSecond(), context);
        //        }

        // extraz.get().put
        // new PointerPointer
        PointerPointer xShapeInfoHostPointer = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()), context.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(), context.getBufferAllocation(),
                context.getBufferReduction(), context.getBufferScalar(), context.getBufferSpecial(),
                hostYShapeInfo, hostZShapeInfo, hostTadShapeInfo, devTadShapeInfo, devTadOffsets,
                devTadShapeInfoZ, devTadOffsetsZ);

        //Pointer dimensionPointer = AtomicAllocator.getInstance().getPointer(Nd4j.createBuffer(dimension), context);
        Pointer dimensionPointer = AtomicAllocator.getInstance()
                .getPointer(AtomicAllocator.getInstance().getConstantBuffer(dimension), context);

        switch (op.getOpType()) {
            case BROADCAST:
                nativeOps.execBroadcast(xShapeInfoHostPointer, op.opNum(),
                        null, (LongPointer) AtomicAllocator.getInstance().getHostPointer(op.x().shapeInfoDataBuffer()), x, (LongPointer) xShapeInfo,
                        null, (LongPointer) AtomicAllocator.getInstance().getHostPointer(op.y().shapeInfoDataBuffer()), y, (LongPointer) AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(),context),
                        null, (LongPointer) AtomicAllocator.getInstance().getHostPointer(op.z().shapeInfoDataBuffer()), z, (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                        null,
                        (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                        AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                        null);
                break;
            case BROADCAST_BOOL:
                nativeOps.execBroadcastBool(xShapeInfoHostPointer, op.opNum(),
                        null, (LongPointer) AtomicAllocator.getInstance().getHostPointer(op.x().shapeInfoDataBuffer()), x, (LongPointer) xShapeInfo,
                        null, (LongPointer) AtomicAllocator.getInstance().getHostPointer(op.y().shapeInfoDataBuffer()), y, (LongPointer) AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(),context),
                        null, (LongPointer) AtomicAllocator.getInstance().getHostPointer(op.z().shapeInfoDataBuffer()), z, (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                        null,
                        (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                        AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                        null);
                break;
            default:
                throw new UnsupportedOperationException("Unknown op type: " + op.getOpType());
        }

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());


        AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());

        profilingConfigurableHookOut(op, st);

        return op.z();
    }

    /**
     *
     * @param op
     * @param dimension
     * @return
     */
    protected INDArray naiveExec(ReduceOp op, int... dimension) {
        long st = profilingConfigurableHookIn(op);

        if(op instanceof BaseReduceOp && ((BaseReduceOp)op).isEmptyReduce()){
            //Edge case for TF import compatibility: [x,y].reduce(empty) = [x,y]
            //Note that "empty" axis is NOT the same as length 0, as in INDArray.sum(new int[0]), which means "all dimensions"
            if(op.z() != null){
                Preconditions.checkState(op.x().equalShapes(op.z()), "For empty reductions, result (z) array must have same shape as x shape." +
                        " Got: x=%ndShape, z=%ndShape", op.x(), op.z());
                op.z().assign(op.x());
                return op.z();
            } else {
                op.setZ(op.x().dup());
                return op.z();
            }
        }

        INDArray ret = op.z();

        checkForCompression(op);
        op.validateDataTypes();
        //validateDataType(Nd4j.dataType(), op);

        for (int i = 0; i < dimension.length; i++)
            if (dimension[i] >= op.x().rank() && dimension[i] != Integer.MAX_VALUE)
                throw new ND4JIllegalStateException("Op target dimension " + Arrays.toString(dimension)
                        + " contains element that higher then rank of op.X: [" + op.x().rank() + "]");

        val context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());

        if (CudaEnvironment.getInstance().getConfiguration().isDebug())
            lastOp.set(op.opName());

        val hostXShapeInfo = op.x() == null ? null : AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer());
        val hostYShapeInfo = op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        val hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), dimension);

        Pointer hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
        Pointer devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

        DataBuffer offsets = tadBuffers.getSecond();
        Pointer devTadOffsets = offsets == null ? null : AtomicAllocator.getInstance().getPointer(offsets, context);

        Pointer x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        Pointer xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        PointerPointer xShapeInfoHostPointer = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()),
                context.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(),
                context.getBufferAllocation(),
                context.getBufferReduction(),
                context.getBufferScalar(),
                context.getBufferSpecial(),
                hostYShapeInfo,
                hostZShapeInfo,
                hostTadShapeInfo,
                devTadShapeInfo,
                devTadOffsets);

        Pointer yDevTadOffsets = null;
        Pointer yDevTadShapeInfo = null;

        if (op.y() != null) {
            if (dimension.length == 0 || (dimension.length == 1 &&  dimension[0] == Integer.MAX_VALUE )|| op.x().tensorAlongDimension(0, dimension).length() != op.y().length()) {
                if (!op.isComplexAccumulation() && op.x().length() != op.y().length())
                    throw new ND4JIllegalStateException("Op.X [" + op.x().length() + "] and Op.Y [" + op.y().length() + "] lengths should match");

                if (!op.z().isScalar()) {
                    Pair<DataBuffer, DataBuffer> yTadBuffers = tadManager.getTADOnlyShapeInfo(op.y(), dimension);

                    yDevTadShapeInfo = AtomicAllocator.getInstance().getPointer(yTadBuffers.getFirst(), context);

                    DataBuffer yOffsets = yTadBuffers.getSecond();
                    yDevTadOffsets = yOffsets == null ? null : AtomicAllocator.getInstance().getPointer(yOffsets, context);

                    xShapeInfoHostPointer.put(12, yDevTadShapeInfo);
                    xShapeInfoHostPointer.put(13, yDevTadOffsets);
                }
            } else {
                // TAD vs full array code branch
                val fakeOffsets = Nd4j.getConstantHandler().getConstantBuffer(new int[] {0, 0}, DataType.LONG);
                yDevTadOffsets = fakeOffsets == null ? null : AtomicAllocator.getInstance().getPointer(fakeOffsets, context);

                yDevTadShapeInfo = AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context);

                xShapeInfoHostPointer.put(12, AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context));
                xShapeInfoHostPointer.put(13, null);
            }
        }

        DataType argsType;
        switch (op.getOpType()) {
            case REDUCE_LONG:
            case REDUCE_BOOL:
                argsType = op.x().dataType();
                break;
            default:
                argsType = op.z().dataType();
        }

        Pointer extraArgs = op.extraArgs() != null ? AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(argsType), context) : null;
        Pointer dimensionPointer = AtomicAllocator.getInstance().getPointer(AtomicAllocator.getInstance().getConstantBuffer(dimension), context); //AtomicAllocator.getInstance().getPointer(Nd4j.createBuffer(dimension), context);

            if (op instanceof Variance) {
                if (ret.isScalar()) {
                    nativeOps.execSummaryStatsScalar(xShapeInfoHostPointer, op.opNum(),
                            null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                            extraArgs,
                            null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer()),
                            ((Variance) op).isBiasCorrected());

                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                } else {
                    nativeOps.execSummaryStatsTad(xShapeInfoHostPointer, op.opNum(),
                            null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                            extraArgs,
                            null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                            null,
                            (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                            AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                            null, ((Variance) op).isBiasCorrected(),
                            (LongPointer) devTadShapeInfo,
                            (LongPointer) devTadOffsets);

                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                }
            } else if (op.y() != null) {
                if (op.isComplexAccumulation()) {

                    val dT = new LongPointerWrapper(devTadOffsets);
                    val yT = new LongPointerWrapper(yDevTadOffsets);

                    nativeOps.execReduce3All(xShapeInfoHostPointer, op.opNum(),
                            null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                            extraArgs,
                            null, (LongPointer) hostYShapeInfo, AtomicAllocator.getInstance().getPointer(op.y(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(),context),
                            null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                            null,
                            (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                            AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                            null,
                            (LongPointer) devTadShapeInfo,
                            dT,
                            (LongPointer) yDevTadShapeInfo,
                            yT);

                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                } else if (ret.isScalar()) {
                    nativeOps.execReduce3Scalar(xShapeInfoHostPointer, op.opNum(),
                            null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                            extraArgs,
                            null, (LongPointer) hostYShapeInfo, AtomicAllocator.getInstance().getPointer(op.y(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context),
                            null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context));
                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                } else {
                    nativeOps.execReduce3Tad(xShapeInfoHostPointer, op.opNum(),
                            null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                            extraArgs,
                            null, (LongPointer) hostYShapeInfo, AtomicAllocator.getInstance().getPointer(op.y(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context),
                            null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                            (IntPointer) op.dimensions().data().addressPointer(),
                            (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                            AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                            null,
                            (LongPointer) devTadShapeInfo, (LongPointer) devTadOffsets, (LongPointer) yDevTadShapeInfo, (LongPointer) yDevTadOffsets);

                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                }
            } else {
                if (ret.isScalar()) {
                    switch (op.getOpType()) {
                        case REDUCE_FLOAT:
                            nativeOps.execReduceFloat(xShapeInfoHostPointer, op.opNum(),
                                    null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                    extraArgs,
                                    null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer()));
                            break;
                        case REDUCE_BOOL:
                            nativeOps.execReduceBool(xShapeInfoHostPointer, op.opNum(),
                                    null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                    extraArgs,
                                    null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer()));
                            break;
                        case REDUCE_LONG:
                            nativeOps.execReduceLong(xShapeInfoHostPointer, op.opNum(),
                                    null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                    extraArgs,
                                    null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer()));
                            break;
                        case REDUCE_SAME:
                            nativeOps.execReduceSame(xShapeInfoHostPointer, op.opNum(),
                                    null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                    extraArgs,
                                    null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer()));
                            break;
                        default:
                            throw new UnsupportedOperationException();
                    }

                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                } else {
                    switch (op.getOpType()) {
                        case REDUCE_FLOAT:
                            nativeOps.execReduceFloat2(xShapeInfoHostPointer, op.opNum(),
                                    null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                    extraArgs,
                                    null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                                    (IntPointer) op.dimensions().data().addressPointer(),
                                    (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                                    AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                                    null);
                            break;
                        case REDUCE_BOOL:
                            nativeOps.execReduceBool2(xShapeInfoHostPointer, op.opNum(),
                                    null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                    extraArgs,
                                    null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                                    (IntPointer) op.dimensions().data().addressPointer(),
                                    (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                                    AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                                    null);
                            break;
                        case REDUCE_SAME:
                            nativeOps.execReduceSame2(xShapeInfoHostPointer, op.opNum(),
                                    null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                    extraArgs,
                                    null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                                    (IntPointer) op.dimensions().data().addressPointer(),
                                    (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                                    AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                                    null);
                            break;
                        case REDUCE_LONG:
                            nativeOps.execReduceLong2(xShapeInfoHostPointer, op.opNum(),
                                    null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                    extraArgs,
                                    null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                                    (IntPointer) op.dimensions().data().addressPointer(),
                                    (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                                    AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                                    null);
                            break;
                        default:
                            throw new UnsupportedOperationException();
                    }

                    AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
                }
            }

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        profilingConfigurableHookOut(op, st);

        return op.z();
    }

    @Override
    public INDArray exec(Variance op) {
        return exec((ReduceOp) op);
    }

    @Override
    public INDArray exec(ReduceOp op) {
        checkForCompression(op);

        if(op instanceof BaseReduceOp && ((BaseReduceOp)op).isEmptyReduce()){
            //Edge case for TF import compatibility: [x,y].reduce(empty) = [x,y]
            //Note that "empty" axis is NOT the same as length 0, as in INDArray.sum(new int[0]), which means "all dimensions"
            if(op.z() != null){
                Preconditions.checkState(op.x().equalShapes(op.z()), "For empty reductions, result (z) array must have same shape as x shape." +
                        " Got: x=%ndShape, z=%ndShape", op.x(), op.z());
                op.z().assign(op.x());
                return op.z();
            } else {
                op.setZ(op.x().dup());
                return op.z();
            }
        }

        val dimension = op.dimensions().toIntVector();

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        val maxShape = Shape.getMaxShape(op.x(),op.y());

        val wholeDims = Shape.wholeArrayDimension(dimension) || op.x().rank() == dimension.length || dimension.length == 0;
        val retShape = Shape.reductionShape(op.y() == null ? op.x() : op.x().length() > op.y().length() ? op.x() : op.y(), dimension, true, op.isKeepDims());

        if (op.x().isVector() && op.x().length() == ArrayUtil.prod(retShape) && ArrayUtil.prodLong(retShape) > 1 && op.y() == null)
            return op.noOp();

        val dtype = op.resultType();
        INDArray ret = null;
        if (op.z() == null || op.z() == op.x()) {
            if (op.isComplexAccumulation()) {
                val xT = op.x().tensorsAlongDimension(dimension);
                val yT = op.y().tensorsAlongDimension(dimension);

                // we intentionally want to set it to 0.0
                ret = Nd4j.createUninitialized(dtype, new long[] {xT, yT});
            } else {
                if (op.y() != null) {
                    //2 options here: either pairwise, equal sizes - OR every X TAD vs. entirety of Y
                    if (op.x().length() == op.y().length()) {
                        //Pairwise
                        if (!wholeDims && op.x().tensorsAlongDimension(dimension) != op.y().tensorsAlongDimension(dimension)) {
                            throw new ND4JIllegalStateException("Number of TADs along dimension don't match: (x shape = " +
                                    Arrays.toString(op.x().shape()) + ", y shape = " + Arrays.toString(op.y().shape()) +
                                    ", dimension = " + Arrays.toString(dimension) + ")");
                        }
                    } else {
                        if (dimension.length == 0)
                            throw new ND4JIllegalStateException("TAD vs TAD comparison requires dimension (or other comparison mode was supposed to be used?)");

                        //Every X TAD vs. entirety of Y
                        val xTADSize = op.x().length() / op.x().tensorsAlongDimension(dimension);

                        if (xTADSize != op.y().length()) {
                            throw new ND4JIllegalStateException("Size of TADs along dimension don't match for pairwise execution:" +
                                    " (x TAD size = " + xTADSize + ", y size = " + op.y().length());
                        }
                    }
                }

                // in case of regular accumulation we don't care about array state before op
                ret = Nd4j.create(dtype, retShape);
            }
            op.setZ(ret);
        } else {
            // compare length

            if (op.z().length() != (retShape.length == 0 ? 1 : ArrayUtil.prodLong(retShape)))
                throw new ND4JIllegalStateException("Shape of target array for reduction [" + Arrays.toString(op.z().shape()) + "] doesn't match expected [" + Arrays.toString(retShape) + "]");
        }

        long st = profilingConfigurableHookIn(op);
        naiveExec(op, dimension);

        profilingConfigurableHookOut(op, st);

        return op.z();
    }

    @Override
    public INDArray exec(IndexAccumulation op) {
        val dimension = Shape.normalizeAxis(op.x().rank(), op.dimensions().toIntVector());

        if (op.x().isEmpty()) {
            for (val d:dimension) {
                Preconditions.checkArgument(op.x().shape()[d] != 0, "IndexReduce can't be issued along axis with 0 in shape");
            }
        }

        if (op.z() == null) {
            val retShape = Shape.reductionShape(op.x(), dimension, true, op.isKeepDims());
            op.setZ(Nd4j.createUninitialized(DataType.LONG, retShape));
        }

        long st = profilingConfigurableHookIn(op);

        checkForCompression(op);

        //validateDataType(Nd4j.dataType(), op);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        if (op.x().isVector() && op.x().length() == op.z().length()) {
            return op.x();
        }

        if (op.z().isEmpty())
            return op.z();

        if (CudaEnvironment.getInstance().getConfiguration().isDebug())
            lastOp.set(op.opName());

        val context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());

        val hostXShapeInfo =
                op.x() == null ? null : AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer());
        val hostYShapeInfo =
                op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        val hostZShapeInfo =
                op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        val x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        val xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);

        val z = AtomicAllocator.getInstance().getPointer(op.z(), context);
        val zShapeInfo = AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context);

        Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), dimension);

        val hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
        val devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

        val offsets = tadBuffers.getSecond();
        val devTadOffsets = offsets == null ? null : AtomicAllocator.getInstance().getPointer(offsets, context);

        PointerPointer xShapeInfoHostPointer = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()), context.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(), context.getBufferAllocation(),
                context.getBufferReduction(), context.getBufferScalar(), context.getBufferSpecial(),
                hostYShapeInfo, hostZShapeInfo, hostTadShapeInfo, devTadShapeInfo, devTadOffsets);
        Pointer extraArgs = op.extraArgs() != null
                ? AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(op.x().dataType()), context) : null;
        //Pointer dimensionPointer = AtomicAllocator.getInstance().getPointer(Nd4j.createBuffer(dimension), context);
        Pointer dimensionPointer = AtomicAllocator.getInstance()
                .getPointer(AtomicAllocator.getInstance().getConstantBuffer(dimension), context);


        nativeOps.execIndexReduce(xShapeInfoHostPointer,
                    op.opNum(),
                    null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                     extraArgs,
                null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                (IntPointer) op.dimensions().data().addressPointer(),
                (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                null);

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());

        profilingConfigurableHookOut(op, st);

        return op.z();
    }


    @Override
    public INDArray exec(Op op) {
        checkForCompression(op);

        //linear views and oblong offsets can't be handled by the gpu (due to the way the buffers are interpreted as vectors)
        if ( op instanceof CopyOp) {
            // we dont' care about op.Z sync state, since it'll be overwritten
            if (op.x() != null)
                AtomicAllocator.getInstance().synchronizeHostData(op.x());
            if (op.y() != null)
                AtomicAllocator.getInstance().synchronizeHostData(op.y());

            super.exec(op);

            if (op.z() != null)
                AtomicAllocator.getInstance().tickHostWrite(op.z());
            return null;
        }

        if (op instanceof TransformOp) {
            TransformOp t = (TransformOp) op;
            invoke(t);
        } else if (op instanceof ReduceOp) {
            ReduceOp acc = (ReduceOp) op;
            invoke(acc, acc.dimensions().toIntVector());
        } else if (op instanceof ScalarOp) {
            ScalarOp sc = (ScalarOp) op;
            invoke(sc);
        } else if (op instanceof BroadcastOp) {
            BroadcastOp broadcastOp = (BroadcastOp) op;
            invoke(broadcastOp);
        } else if (op instanceof IndexAccumulation) {
            IndexAccumulation indexAccumulation = (IndexAccumulation) op;
            invoke(indexAccumulation, indexAccumulation.dimensions().toIntVector());
        } else if (op instanceof RandomOp) {
            exec((RandomOp) op);
        } else if (op instanceof CustomOp) {
            exec((CustomOp) op);
        }


        return op.z();
    }



    @Override
    public TransformOp execAndReturn(TransformOp op) {
        checkForCompression(op);
        invoke(op);
        return op;
    }



    protected CudaContext invoke(BroadcastOp op) {
        long st = profilingConfigurableHookIn(op);

        checkForCompression(op);

        //validateDataType(Nd4j.dataType(), op);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());

        if (CudaEnvironment.getInstance().getConfiguration().isDebug())
            lastOp.set(op.opName());

        Pointer x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        Pointer xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);


        val hostXShapeInfo =
                op.x() == null ? null : AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer());
        val hostYShapeInfo =
                op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        val hostZShapeInfo =
                op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        val tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), op.getDimension());

        val hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
        val devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

        val offsets = tadBuffers.getSecond();
        val devTadOffsets = AtomicAllocator.getInstance().getPointer(offsets, context);

        Pointer devTadShapeInfoZ = null;
        Pointer devTadOffsetsZ = null;

        // that's the place where we're going to have second TAD in place
        val tadBuffersZ = tadManager.getTADOnlyShapeInfo(op.z(), op.getDimension());

        devTadShapeInfoZ = AtomicAllocator.getInstance().getPointer(tadBuffersZ.getFirst(), context);
        devTadOffsetsZ = AtomicAllocator.getInstance().getPointer(tadBuffersZ.getSecond(), context);

        PointerPointer xShapeInfoHostPointer = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()), // 0
                context.getOldStream(), // 1
                AtomicAllocator.getInstance().getDeviceIdPointer(), // 2
                context.getBufferAllocation(), // 3
                context.getBufferReduction(),  // 4
                context.getBufferScalar(),  // 5
                context.getBufferSpecial(), // 6
                hostYShapeInfo,  // 7
                hostZShapeInfo,  // 8
                hostTadShapeInfo,  // 9
                devTadShapeInfo,  // 10
                devTadOffsets, // 11
                devTadShapeInfoZ,  // 12
                devTadOffsetsZ); // 13

        Pointer y = AtomicAllocator.getInstance().getPointer(op.y(), context);
        Pointer yShapeInfo = AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context);

        Pointer z = AtomicAllocator.getInstance().getPointer(op.z(), context);
        Pointer zShapeInfo = AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context);
        Pointer dimensionPointer = AtomicAllocator.getInstance().getPointer(AtomicAllocator.getInstance().getConstantBuffer(op.getDimension()), context);

        //log.info("X: {}; Y: {}; Z: {}; dTS: {}, dTO: {}; dTSz: {}; dTOz: {};", x.address(), y.address(), z.address(), devTadShapeInfo.address(), devTadOffsets.address(), devTadShapeInfoZ.address(), devTadOffsetsZ.address());

        switch (op.getOpType()) {
            case BROADCAST:
                nativeOps.execBroadcast(xShapeInfoHostPointer, op.opNum(),
                        null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                        null, (LongPointer) hostYShapeInfo, y, (LongPointer) yShapeInfo,
                        null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                        null,
                        (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                        AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                        null);
                break;
            case BROADCAST_BOOL:
                nativeOps.execBroadcastBool(xShapeInfoHostPointer, op.opNum(),
                        null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                        null, (LongPointer) hostYShapeInfo, y, (LongPointer) yShapeInfo,
                        null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                        null,
                        (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                        AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                        null);
                break;
            default:
                throw new UnsupportedOperationException("Unknown opType: " + op.getOpType());
        }

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());

        profilingConfigurableHookOut(op, st);

        return null;
    }



    protected CudaContext invoke(IndexAccumulation op, int[] dimension) {
        dimension = Shape.normalizeAxis(op.x().rank(), dimension);
        if (dimension == null || (dimension.length == 1 && dimension[0] == Integer.MAX_VALUE)) {
            if(op.z() == op.x() || op.z() == null) {
                op.setZ(Nd4j.createUninitialized(DataType.LONG, new long[0], 'c'));
            }
        }

        long st = profilingConfigurableHookIn(op);

        checkForCompression(op);

        //validateDataType(Nd4j.dataType(), op);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        if (CudaEnvironment.getInstance().getConfiguration().isDebug())
            lastOp.set(op.opName());
        CudaEnvironment.getInstance().getConfiguration().enableDebug(true);
        if (dimension != null)
            for (int i = 0; i < dimension.length; i++)
                if (dimension[i] >= op.x().rank() && dimension[i] != Integer.MAX_VALUE)
                    throw new ND4JIllegalStateException("Op target dimension " + Arrays.toString(dimension) + " contains element that higher then rank of op.X: [" + op.x().rank() + "]");

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z().isScalar() ? null : op.z(), op.x(), op.y());

        Pointer x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        Pointer xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);
        Pointer extraArgs = op.extraArgs() != null
                ? AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(op.x().dataType()), context) : null;

        val hostXShapeInfo = op.x() == null ? null : AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer());
        val hostYShapeInfo = op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        val hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        int fdimension[] = dimension;
        if (fdimension == null)
            fdimension = new int[] {0};

        Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), fdimension);

        Pointer hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
        Pointer devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

        DataBuffer offsets = tadBuffers.getSecond();
        Pointer devTadOffsets = offsets == null ? null : AtomicAllocator.getInstance().getPointer(offsets, context);
        val z = AtomicAllocator.getInstance().getPointer(op.z(), context);
        val zShapeInfo = AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context);

        PointerPointer xShapeInfoHostPointer = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()), context.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(), context.getBufferAllocation(),
                context.getBufferReduction(), context.getBufferScalar(), context.getBufferSpecial(),
                hostYShapeInfo, hostZShapeInfo, hostTadShapeInfo, devTadShapeInfo, devTadOffsets);

        if (op.z().isScalar() || dimension == null || dimension[0] == Integer.MAX_VALUE) {
                nativeOps.execIndexReduceScalar(xShapeInfoHostPointer, op.opNum(),
                        null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                        extraArgs,
                        null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo);

            AtomicAllocator.getInstance().registerAction(context, null, op.x(), op.y());
        } else {
            Arrays.sort(dimension);

            //long dimensionPointer = AtomicAllocator.getInstance().getPointer(Nd4j.createBuffer(dimension), context);
            Pointer dimensionPointer = AtomicAllocator.getInstance()
                    .getHostPointer(AtomicAllocator.getInstance().getConstantBuffer(dimension));

            nativeOps.execIndexReduce(xShapeInfoHostPointer, op.opNum(),
                    null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                     extraArgs,
                    null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                    dimensionPointer,
                    (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                    AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                    null);

            AtomicAllocator.getInstance().registerAction(context, null, op.x(), op.y());
        }

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        profilingConfigurableHookOut(op, st);

        return null;

    }


    protected CudaContext invoke(ReduceOp op, int[] dimension) {
        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());

        if(op instanceof BaseReduceOp && ((BaseReduceOp)op).isEmptyReduce()){
            //Edge case for TF import compatibility: [x,y].reduce(empty) = [x,y]
            //Note that "empty" axis is NOT the same as length 0, as in INDArray.sum(new int[0]), which means "all dimensions"
            if(op.z() != null){
                Preconditions.checkState(op.x().equalShapes(op.z()), "For empty reductions, result (z) array must have same shape as x shape." +
                        " Got: x=%ndShape, z=%ndShape", op.x(), op.z());
                op.z().assign(op.x());
                return context;
            } else {
                op.setZ(op.x().dup());
                return context;
            }
        }

        long st = profilingConfigurableHookIn(op);

        checkForCompression(op);

        dimension = Shape.normalizeAxis(op.x().rank(), dimension);

        //validateDataType(Nd4j.dataType(), op);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        // dimension is ALWAYS null here.
        if (dimension == null )
            dimension = new int[] {Integer.MAX_VALUE};

        if (dimension.length > 1)
            Arrays.sort(dimension);

        for (int i = 0; i < dimension.length; i++)
            if (dimension[i] >= op.x().rank() && dimension[i] != Integer.MAX_VALUE)
                throw new ND4JIllegalStateException("Op target dimension " + Arrays.toString(dimension)
                        + " contains element that higher then rank of op.X: [" + op.x().rank() + "]");

        if (CudaEnvironment.getInstance().getConfiguration().isDebug())
            lastOp.set(op.opName());

        val tadBuffers = op.x().isEmpty() ? Pair.<DataBuffer, DataBuffer>makePair(op.x().data(), null) : tadManager.getTADOnlyShapeInfo(op.x(), dimension);

        val hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
        val devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

        val offsets = op.x().isEmpty() ? null : tadBuffers.getSecond();
        val devTadOffsets = offsets == null ? null : AtomicAllocator.getInstance().getPointer(offsets, context);

        Pointer x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        Pointer xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);

        long[] retShape = Shape.reductionShape(op.x(), dimension, true, op.isKeepDims());

        if (op.y() != null) {
            //2 options here: either pairwise, equal sizes - OR every X TAD vs. entirety of Y
            if (op.x().length() == op.y().length()) {
                //Pairwise
                if (op.x().tensorsAlongDimension(dimension) != op.y().tensorsAlongDimension(dimension)) {
                    throw new ND4JIllegalStateException("Number of TADs along dimension don't match: (x shape = " +
                            Arrays.toString(op.x().shape()) + ", y shape = " + Arrays.toString(op.y().shape()) +
                            ", dimension = " + Arrays.toString(dimension) + ")");
                }
            } else {
                //Every X TAD vs. entirety of Y
                val xTADSize = op.x().length() / op.x().tensorsAlongDimension(dimension);

                if (xTADSize != op.y().length()) {
                    throw new ND4JIllegalStateException("Size of TADs along dimension don't match for pairwise execution:" +
                            " (x TAD size = " + xTADSize + ", y size = " + op.y().length());
                }
            }
        }

        if (op.x().isVector() && op.x().length() == ArrayUtil.prod(retShape)) {
            return null;
        }

        val dataType = op.resultType();

        val ret = Nd4j.createUninitialized(dataType, retShape);
        op.setZ(ret);

        val eb = op.extraArgsDataBuff(op.z().dataType() == DataType.BOOL || op.getOpType() == Op.Type.REDUCE_LONG ? op.x().dataType() : op.z().dataType());
        Pointer extraArgs = op.extraArgs() != null ? AtomicAllocator.getInstance().getPointer(eb, context) : null;

        val hostXShapeInfo = op.x() == null ? null : AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer());
        val hostYShapeInfo = op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        val hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        val xShapeInfoHostPointer = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()), context.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(), context.getBufferAllocation(),
                context.getBufferReduction(), context.getBufferScalar(), context.getBufferSpecial(),
                hostYShapeInfo, hostZShapeInfo, hostTadShapeInfo, devTadShapeInfo, devTadOffsets);

        val yTadBuffers = op.y() == null ? null : tadManager.getTADOnlyShapeInfo(op.y(), dimension);

        val yDevTadShapeInfo = op.y() == null ? null : AtomicAllocator.getInstance().getPointer(yTadBuffers.getFirst(), context);
        val yOffsets = op.y() == null ? null : yTadBuffers.getSecond();
        val yDevTadOffsets = yOffsets == null ? null : AtomicAllocator.getInstance().getPointer(yOffsets, context);

        if (op.y() != null) {
            xShapeInfoHostPointer.put(12, yDevTadShapeInfo);
            xShapeInfoHostPointer.put(13, yDevTadOffsets);
        }

        val z = AtomicAllocator.getInstance().getPointer(op.z(), context);
        val zShapeInfo = AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context);

        //log.info("Op.X address: {};", x.address());

        op.validateDataTypes();

        if (op.z().isScalar()) {
            if (op instanceof Variance) {
                nativeOps.execSummaryStatsScalar(xShapeInfoHostPointer, op.opNum(),
                            null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                            extraArgs,
                            null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                            ((Variance) op).isBiasCorrected());
                AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
            } else if (op.y() != null) {
                Pointer y = AtomicAllocator.getInstance().getPointer(op.y(), context);
                Pointer yShapeInfo = AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context);
                    nativeOps.execReduce3Scalar(xShapeInfoHostPointer, op.opNum(),
                            null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                            extraArgs,
                            null, (LongPointer) hostYShapeInfo, y, (LongPointer) yShapeInfo,
                            null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo);

                AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
            } else {
                switch (op.getOpType()) {
                    case REDUCE_FLOAT:
                        nativeOps.execReduceFloat(xShapeInfoHostPointer, op.opNum(),
                                null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                extraArgs,
                                null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo);
                        break;
                    case REDUCE_BOOL:
                        nativeOps.execReduceBool(xShapeInfoHostPointer, op.opNum(),
                                null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                extraArgs,
                                null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo);
                        break;
                    case REDUCE_SAME:
                        nativeOps.execReduceSame(xShapeInfoHostPointer, op.opNum(),
                                null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                extraArgs,
                                null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo);
                        break;
                    case REDUCE_LONG:
                        nativeOps.execReduceLong(xShapeInfoHostPointer, op.opNum(),
                                null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                extraArgs,
                                null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo);
                        break;
                    default:
                        throw new UnsupportedOperationException();
                }

                AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
            }
        } else {
            val dimensionPointer = AtomicAllocator.getInstance().getPointer(AtomicAllocator.getInstance().getConstantBuffer(dimension), context); //AtomicAllocator.getInstance().getPointer(Nd4j.createBuffer(dimension), context);

            if (op.y() != null) {
                val y = AtomicAllocator.getInstance().getPointer(op.y(), context);
                val yShapeInfo = AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context);
                nativeOps.execReduce3Tad(xShapeInfoHostPointer, op.opNum(),
                            null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                            extraArgs,
                            null, (LongPointer) hostYShapeInfo, y, (LongPointer) yShapeInfo,
                            null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                        (IntPointer) op.dimensions().data().addressPointer(),
                        (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                        dimensionPointer, null, (LongPointer) devTadShapeInfo, (LongPointer) devTadOffsets, (LongPointer) yDevTadShapeInfo, (LongPointer) yDevTadOffsets);
            } else {
                if (op instanceof Variance) {
                    nativeOps.execSummaryStatsTad(xShapeInfoHostPointer, op.opNum(),
                             null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                extraArgs,
                               null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                            (IntPointer) op.dimensions().data().addressPointer(),
                            (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                            AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                            null,
                                ((Variance) op).isBiasCorrected(),
                                (LongPointer) devTadShapeInfo,
                                (LongPointer) devTadOffsets);
                } else {
                    switch (op.getOpType()) {
                        case REDUCE_FLOAT:
                            nativeOps.execReduceFloat2(xShapeInfoHostPointer, op.opNum(),
                                    null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                    extraArgs,
                                    null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                                    (IntPointer) op.dimensions().data().addressPointer(),
                                    (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                                    AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                                    null);
                            break;
                        case REDUCE_SAME:
                            nativeOps.execReduceSame2(xShapeInfoHostPointer, op.opNum(),
                                    null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                    extraArgs,
                                    null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                                    (IntPointer) op.dimensions().data().addressPointer(),
                                    (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                                    AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                                    null);
                            break;
                        case REDUCE_BOOL:
                            nativeOps.execReduceBool2(xShapeInfoHostPointer, op.opNum(),
                                    null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                    extraArgs,
                                    null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                                    (IntPointer) op.dimensions().data().addressPointer(),
                                    (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                                    AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                                    null);
                            break;
                        case REDUCE_LONG:
                            nativeOps.execReduceLong2(xShapeInfoHostPointer, op.opNum(),
                                    null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                                    extraArgs,
                                    null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                                    (IntPointer) op.dimensions().data().addressPointer(),
                                    (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                                    AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                                    null);
                            break;
                        default:
                            throw new UnsupportedOperationException();
                    }
                }
            }

            AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());
        }

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        profilingConfigurableHookOut(op, st);

        Nd4j.getExecutioner().commit();

        return context;
    }


    protected CudaContext intercept(ScalarOp op, int[] dimension) {
        long st = profilingConfigurableHookIn(op);

        Arrays.sort(dimension);

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());

        if (CudaEnvironment.getInstance().getConfiguration().isDebug())
            lastOp.set(op.opName());

        val hostXShapeInfo = op.x() == null ? null : AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer());
        val hostYShapeInfo = op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        val hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        val x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        val y = AtomicAllocator.getInstance().getPointer(op.y(), context);
        val z = AtomicAllocator.getInstance().getPointer(op.z(), context);
        val xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);
        val yShapeInfo = AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context);
        val zShapeInfo = AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context);

        val tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), dimension);

        val hostTadShapeInfo = AddressRetriever.retrieveHostPointer(tadBuffers.getFirst());
        val devTadShapeInfo = AtomicAllocator.getInstance().getPointer(tadBuffers.getFirst(), context);

        val offsets = tadBuffers.getSecond();
        val devTadOffsets = AtomicAllocator.getInstance().getPointer(offsets, context);

        Pointer devTadShapeInfoZ = null;
        Pointer devTadOffsetsZ = null;

        val tadBuffersZ = tadManager.getTADOnlyShapeInfo(op.z(), dimension);

        devTadShapeInfoZ = AtomicAllocator.getInstance().getPointer(tadBuffersZ.getFirst(), context);
        devTadOffsetsZ = AtomicAllocator.getInstance().getPointer(tadBuffersZ.getSecond(), context);


        PointerPointer extraPointers = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()), context.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(), context.getBufferAllocation(),
                context.getBufferReduction(), context.getBufferScalar(), context.getBufferSpecial(),
                hostYShapeInfo, hostZShapeInfo, hostTadShapeInfo, devTadShapeInfo, devTadOffsets,
                devTadShapeInfoZ, devTadOffsetsZ);

        val extraArgs = op.extraArgs() != null ? AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(op.z().dataType()), context) : null;

        val dimensionPointer = AtomicAllocator.getInstance().getPointer(AtomicAllocator.getInstance().getConstantBuffer(dimension), context);

        switch (op.getOpType()) {
            case SCALAR:
                nativeOps.execScalarTad(extraPointers, op.opNum(),
                        null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                       null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                        null, (LongPointer) hostYShapeInfo, y, (LongPointer) yShapeInfo,
                        extraArgs,
                        null,
                        (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                        AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                        null,
                        (LongPointer) devTadShapeInfo, (LongPointer) devTadOffsets,
                        (LongPointer) devTadShapeInfoZ, (LongPointer) devTadOffsetsZ);
                break;
            case SCALAR_BOOL:
                nativeOps.execScalarBoolTad(extraPointers, op.opNum(),
                        null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                        null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                        null, (LongPointer) hostYShapeInfo, y, (LongPointer) yShapeInfo,
                        extraArgs,
                        null,
                        (LongPointer) op.dimensions().shapeInfoDataBuffer().addressPointer(),
                        AtomicAllocator.getInstance().getPointer(op.dimensions(), context),
                        null,
                        (LongPointer) devTadShapeInfo, (LongPointer) devTadOffsets,
                        (LongPointer) devTadShapeInfoZ, (LongPointer) devTadOffsetsZ);
                break;
            default:
                throw new UnsupportedOperationException();
        }

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        AtomicAllocator.getInstance().getFlowController().registerAction(context, op.z(), op.x(), op.y());

        profilingConfigurableHookOut(op, st);

        return null;
    }

    @Override
    public INDArray exec(ScalarOp op) {
        invoke(op);
        return op.z();
    }

    protected CudaContext invoke(ScalarOp op) {
        long st = profilingConfigurableHookIn(op);

        checkForCompression(op);

//        validateDataType(Nd4j.dataType(), op);

        if (op.x().length() != op.z().length())
            throw new ND4JIllegalStateException("op.X length should be equal to op.Y length: ["
                    + Arrays.toString(op.x().shapeInfoDataBuffer().asInt()) + "] != ["
                    + Arrays.toString(op.z().shapeInfoDataBuffer().asInt()) + "]");

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        if (CudaEnvironment.getInstance().getConfiguration().isDebug())
            lastOp.set(op.opName());

        if (op.dimensions() != null) {
            intercept(op, op.dimensions().toIntVector());
            return null;
        }

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());

        val hostXShapeInfo = op.x() == null ? null : AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer());
        val hostYShapeInfo = op.scalar() == null ? null : AddressRetriever.retrieveHostPointer(op.scalar().shapeInfoDataBuffer());
        val hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        Pointer x = AtomicAllocator.getInstance().getPointer(op.x(), context);
        Pointer xShapeInfo = AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context);
        Pointer extraArgs = op.extraArgs() != null ? AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(op.getOpType() == Op.Type.SCALAR_BOOL ? op.x().dataType() : op.z().dataType()), context) : null;

        Pointer z = AtomicAllocator.getInstance().getPointer(op.z(), context);
        Pointer zShapeInfo = AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context);

        PointerPointer xShapeInfoHostPointer = extraz.get().put(
                AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()), context.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(), context.getBufferAllocation(),
                context.getBufferReduction(), context.getBufferScalar(), context.getBufferSpecial(),
                hostYShapeInfo, hostZShapeInfo, null, null);

        switch (op.getOpType()) {
            case SCALAR_BOOL:
                nativeOps.execScalarBool(xShapeInfoHostPointer, op.opNum(),
                        null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                        null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                        null, (LongPointer) hostYShapeInfo, AtomicAllocator.getInstance().getPointer(op.scalar(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.scalar().shapeInfoDataBuffer(), context),
                        extraArgs);
                break;
            case SCALAR:
                nativeOps.execScalar(xShapeInfoHostPointer, op.opNum(),
                        null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                        null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                        null, (LongPointer) hostYShapeInfo, AtomicAllocator.getInstance().getPointer(op.scalar(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.scalar().shapeInfoDataBuffer(), context),
                        extraArgs);
                break;
            default:
                throw new UnsupportedOperationException("Unknown op type: " + op.getOpType());
        }

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.scalar());

        profilingConfigurableHookOut(op, st);

        return null;
    }

    protected CudaContext invoke(TransformOp op) {
        long st = profilingConfigurableHookIn(op);

        checkForCompression(op);

        //validateDataType(Nd4j.dataType(), op);

        AtomicAllocator allocator = AtomicAllocator.getInstance();

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        CudaContext context = allocator.getFlowController().prepareAction(op.z(), op.x(), op.y());

        if (CudaEnvironment.getInstance().getConfiguration().isDebug())
            lastOp.set(op.opName());

        // special temp array for IsMax along dimension
        INDArray ret = null;

        Pointer x = allocator.getPointer(op.x(), context);
        Pointer xShapeInfo = allocator.getPointer(op.x().shapeInfoDataBuffer(), context);


        Pointer dimensionDevPointer = null;
        Pointer dimensionHostPointer = null;
        Pointer retPointer = null;
        Pointer retHostShape = null;
        int dimension[] = null;

        val hostXShapeInfo = op.x() == null ? null : AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer());
        var hostYShapeInfo = op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());


        if (op.z() == null) {
            ret = Nd4j.createUninitialized(op.resultType(), op.x().shape(), op.x().ordering());
            op.setZ(ret);
        }

        var extraArgs = op.extraArgs() != null ? allocator.getPointer(op.extraArgsDataBuff(op.getOpType() == Op.Type.TRANSFORM_BOOL || op.getOpType() == Op.Type.PAIRWISE_BOOL ? op.x().dataType() : op.z().dataType()), context) : null;
        val hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        Pointer hostTadShapeInfo = null;
        Pointer devTadShapeInfo = null;

        Pointer hostMaxTadShapeInfo = null;
        Pointer devMaxTadShapeInfo = null;

        Pair<DataBuffer, DataBuffer> tadBuffers;
        Pair<DataBuffer, DataBuffer> tadMaxBuffers;

        Pointer devTadOffsets = null;
        Pointer devMaxTadOffsets = null;

        op.validateDataTypes(experimentalMode.get());

        Pointer z = allocator.getPointer(op.z(), context);
        Pointer zShapeInfo = allocator.getPointer(op.z().shapeInfoDataBuffer(), context);


        PointerPointer xShapeInfoHostPointer =
                extraz.get().put(AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer()), // 0
                        context.getOldStream(), // 1
                        allocator.getDeviceIdPointer(), // 2
                        context.getBufferAllocation(), // 3
                        context.getBufferReduction(), // 4
                        context.getBufferScalar(), // 5
                        context.getBufferSpecial(), // 6
                        hostYShapeInfo, // 7
                        hostZShapeInfo, // 8
                        hostTadShapeInfo, // 9
                        devTadShapeInfo, // 10
                        devTadOffsets, // 11
                        hostMaxTadShapeInfo, // 12
                        devMaxTadShapeInfo, // 13
                        devMaxTadOffsets, // 14
                        dimensionDevPointer, // special pointer for IsMax  // 15
                        dimensionHostPointer, // special pointer for IsMax  // 16
                        retPointer, // special pointer for IsMax // 17
                        new CudaPointer(dimension == null ? 0 : dimension.length),
                        retHostShape);




        if (op.y() != null) {
            Pointer y = allocator.getPointer(op.y(), context);
            Pointer yShapeInfo = allocator.getPointer(op.y().shapeInfoDataBuffer(), context);

            if (op.x().length() != op.y().length() || op.x().length() != op.z().length())
                throw new ND4JIllegalStateException("X, Y and Z arguments should have the same length for PairwiseTransform");

            ///log.info("X: {}; Y: {}; Z: {}; E: {};", x.address(), y.address(), z.address(), extraArgs != null ? extraArgs.address() : null);

            switch (op.getOpType()) {
                case TRANSFORM_BOOL:
                case PAIRWISE_BOOL:
                    nativeOps.execPairwiseTransformBool(xShapeInfoHostPointer, op.opNum(),
                            null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                            null, (LongPointer) hostYShapeInfo, y, (LongPointer) yShapeInfo,
                            null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                            extraArgs);
                    break;
                default:
                    nativeOps.execPairwiseTransform(xShapeInfoHostPointer, op.opNum(),
                        null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                        null, (LongPointer) hostYShapeInfo, y, (LongPointer) yShapeInfo,
                        null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                        extraArgs);
                    break;
            }
        } else {
            switch (op.getOpType()) {
                case TRANSFORM_ANY:
                    nativeOps.execTransformAny(xShapeInfoHostPointer, op.opNum(),
                            null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                            null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                            extraArgs);
                    break;
                case TRANSFORM_FLOAT:
                    nativeOps.execTransformFloat(xShapeInfoHostPointer, op.opNum(),
                            null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                           null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                            extraArgs);
                    break;
                case TRANSFORM_BOOL:
                    nativeOps.execTransformBool(xShapeInfoHostPointer, op.opNum(),
                            null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                            null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                            extraArgs);
                    break;
                case TRANSFORM_SAME:
                    nativeOps.execTransformSame(xShapeInfoHostPointer, op.opNum(),
                            null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                            null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                            extraArgs);
                    break;
                case TRANSFORM_STRICT:
                    nativeOps.execTransformStrict(xShapeInfoHostPointer, op.opNum(),
                            null, (LongPointer) hostXShapeInfo, x, (LongPointer) xShapeInfo,
                            null, (LongPointer) hostZShapeInfo, z, (LongPointer) zShapeInfo,
                            extraArgs);
                    break;
                default:
                    throw new UnsupportedOperationException();
            }
        }

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        AtomicAllocator.getInstance().registerAction(context, op.z(), op.x(), op.y());

        if (extraArgs != null)
            extraArgs.address();

        if (ret != null)
            ret.elementWiseStride();

        profilingConfigurableHookOut(op, st);

        return null;
    }

    protected <T extends Aggregate> DataBuffer getBuffer(Batch<T> batch) {
        DataBuffer buffer = Nd4j.getDataBufferFactory().createInt(batch.getSample().getRequiredBatchMemorySize() * 4,
                false);
        batch.setParamsSurface(buffer);
        return buffer;
    }

    @Override
    public <T extends Aggregate> void exec(Batch<T> batch) {
        val surfaceBuffer = (BaseCudaDataBuffer) getBuffer(batch);
        surfaceBuffer.lazyAllocateHostPointer();

        val context = AtomicAllocator.getInstance().getDeviceContext();

        val pointer = (IntPointer) new CudaPointer(AtomicAllocator.getInstance().getHostPointer(surfaceBuffer))
                .asIntPointer();
        val surfacePoint = AtomicAllocator.getInstance().getAllocationPoint(surfaceBuffer);

        int maxTypes = 5;

        int maxIntArrays = batch.getSample().maxIntArrays();

        int maxArraySize = batch.getSample().maxIntArraySize();


        int indexPos = maxTypes * (Batch.getBatchLimit() * 16);
        int intArraysPos = indexPos + (batch.getSample().maxIndexArguments() * (Batch.getBatchLimit() * 16));
        int realPos = (intArraysPos + (maxIntArrays * maxArraySize * (Batch.getBatchLimit() * 16)))
                / (Nd4j.dataType() == DataType.DOUBLE ? 2 : 1);

        if (Nd4j.dataType() == DataType.HALF)
            realPos *= 2;

        int argsPos = (realPos + (batch.getSample().maxRealArguments() * (Batch.getBatchLimit() * 16)))
                / (Nd4j.dataType() == DataType.FLOAT ? 2 : 1);

        if (Nd4j.dataType() == DataType.HALF)
            argsPos /= 4;

        int shapesPos = argsPos + (batch.getSample().maxArguments() * (Batch.getBatchLimit() * 16));
        DataType dataType = null;
        for (int i = 0; i < batch.getNumAggregates(); i++) {
            T op = batch.getAggregates().get(i);

            if (i == 0)
                dataType = op.getArguments().get(0).dataType();

            // put num arguments
            int idx = i * maxTypes;
            pointer.put(idx, op.getArguments().size());
            pointer.put(idx + 1, op.getShapes().size());
            pointer.put(idx + 2, op.getIndexingArguments().size());
            pointer.put(idx + 3, op.getRealArguments().size());
            pointer.put(idx + 4, op.getIntArrayArguments().size());


            // putting indexing arguments
            for (int e = 0; e < op.getIndexingArguments().size(); e++) {
                idx = indexPos + i * batch.getSample().maxIndexArguments();
                pointer.put(idx + e, op.getIndexingArguments().get(e));
            }

            // putting intArray values
            int bsize = maxIntArrays * maxArraySize;
            for (int e = 0; e < op.getIntArrayArguments().size(); e++) {
                int step = (i * bsize) + (e * maxArraySize);
                if (op.getIntArrayArguments().get(e) != null)
                    for (int x = 0; x < op.getIntArrayArguments().get(e).length; x++) {
                        idx = intArraysPos + step + x;
                        pointer.put(idx, op.getIntArrayArguments().get(e)[x]);
                    }
            }

            // TODO: variable datatype should be handled here
            // putting real arguments
            switch (dataType) {
                case FLOAT: {
                    FloatPointer realPtr = new FloatPointer(pointer);
                    for (int e = 0; e < op.getRealArguments().size(); e++) {
                        idx = realPos + i * op.maxRealArguments();
                        realPtr.put(idx + e, op.getRealArguments().get(e).floatValue());
                    }
                }
                break;
                case DOUBLE: {
                    DoublePointer dPtr = new DoublePointer(pointer);
                    for (int e = 0; e < op.getRealArguments().size(); e++) {
                        idx = realPos + (i * op.maxRealArguments());
                        dPtr.put(idx + e, op.getRealArguments().get(e).doubleValue());
                    }
                }
                break;
                case HALF: {
                    ShortPointer sPtr = new ShortPointer(pointer);
                    for (int e = 0; e < op.getRealArguments().size(); e++) {
                        idx = realPos + (i * op.maxRealArguments());
                        sPtr.put(idx + e, BaseDataBuffer.fromFloat(op.getRealArguments().get(e).floatValue()));
                    }
                }
                break;
                default:
                    throw new UnsupportedOperationException("Unknown data type");
            }

            // putting arguments pointers
            PointerPointer ptrPtr = new PointerPointer(pointer);
            for (int e = 0; e < op.getArguments().size(); e++) {
                idx = argsPos + i * batch.getSample().maxArguments();

                if (op.getArguments().get(e) != null) {
                    ptrPtr.put(idx + e, AtomicAllocator.getInstance().getPointer(op.getArguments().get(e), context));
                    AtomicAllocator.getInstance().getAllocationPoint(op.getArguments().get(e)).tickDeviceWrite();
                }
            }


            // putting shape pointers
            for (int e = 0; e < op.getShapes().size(); e++) {
                idx = shapesPos + i * batch.getSample().maxShapes();

                if (op.getShapes().get(e) != null) {
                    ptrPtr.put(idx + e, AtomicAllocator.getInstance().getPointer(op.getShapes().get(e), context));
                    AtomicAllocator.getInstance().getAllocationPoint(op.getShapes().get(e)).tickDeviceWrite();
                }
            }
        }

        // trigger write, so getPointer request will force relocation to GPU
        surfacePoint.tickHostWrite();

        PointerPointer extraArgs = new PointerPointer(32);
        extraArgs.put(0, null);
        extraArgs.put(1, context.getOldStream());
        extraArgs.put(2, new CudaPointer(Math.min(batch.getNumAggregates(),
                CudaEnvironment.getInstance().getConfiguration().getMaximumGridSize())));
        extraArgs.put(3, new CudaPointer(batch.getSample().getThreadsPerInstance()));
        extraArgs.put(4, new CudaPointer(batch.getSample().getSharedMemorySize()));


        nativeOps.execAggregateBatch(extraArgs, batch.getNumAggregates(), batch.opNum(),
                    batch.getSample().maxArguments(), batch.getSample().maxShapes(),
                    batch.getSample().maxIntArrays(), batch.getSample().maxIntArraySize(),
                    batch.getSample().maxIndexArguments(), batch.getSample().maxRealArguments(),
                    AtomicAllocator.getInstance().getPointer(surfaceBuffer, context), FlatBuffersMapper.getDataTypeAsByte(dataType));

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        surfacePoint.tickHostWrite();
    }

    @Override
    public void exec(List<Aggregate> batch) {
        if (batch.size() == 0)
            return;

        List<Batch<Aggregate>> batches = Batch.getBatches(batch, 8192);
        for (Batch<Aggregate> single : batches) {
            this.exec(single);
        }

        val context = AtomicAllocator.getInstance().getDeviceContext();
        context.syncOldStream();
    }

    @Override
    public void exec(Aggregate op) {
        int numArguments = op.getArguments().size();
        int numShapeArguments = op.getShapes().size();
        int numIndexArguments = op.getIndexingArguments().size();
        int numIntArrays = op.getIntArrayArguments().size();
        int numRealArguments = op.getRealArguments().size();

        val context = (CudaContext) AtomicAllocator.getInstance().getDeviceContext();

        val extraArgs = new PointerPointer(32);
        extraArgs.put(0, null);
        extraArgs.put(1, context.getOldStream());
        extraArgs.put(2, new CudaPointer(1));
        extraArgs.put(3, new CudaPointer(op.getThreadsPerInstance()));
        extraArgs.put(4, new CudaPointer(op.getSharedMemorySize()));

        long arguments[] = new long[numArguments];
        val dataType = op.getArguments().get(0).dataType();

        for (int x = 0; x < numArguments; x++) {
            arguments[x] = op.getArguments().get(x) == null ? 0
                    : AtomicAllocator.getInstance().getPointer(op.getArguments().get(x), context).address();

            if (op.getArguments().get(x) != null)
                AtomicAllocator.getInstance().getAllocationPoint(op.getArguments().get(x)).tickDeviceWrite();
        }

        DataBuffer tempX = AllocationUtils.getPointersBuffer(arguments);
        PointerPointer xPtr = new PointerPointer(AtomicAllocator.getInstance().getPointer(tempX, context));


        long shapes[] = new long[numShapeArguments];
        for (int x = 0; x < numShapeArguments; x++) {
            shapes[x] = op.getShapes().get(x) == null ? 0
                    : AtomicAllocator.getInstance().getPointer(op.getShapes().get(x), context).address();

            if (op.getShapes().get(x) != null)
                AtomicAllocator.getInstance().getAllocationPoint(op.getShapes().get(x)).tickDeviceWrite();
        }

        DataBuffer tempS = AllocationUtils.getPointersBuffer(shapes);
        PointerPointer sPtr = new PointerPointer(AtomicAllocator.getInstance().getPointer(tempS, context));


        long ints[] = new long[numIntArrays];
        for (int x = 0; x < numIntArrays; x++) {
            if (op.getIntArrayArguments().get(x) != null) {
                DataBuffer intBuf = Nd4j.getDataBufferFactory().createInt(op.getIntArrayArguments().get(x));
                ints[x] = AtomicAllocator.getInstance().getPointer(intBuf, context).address();
            }

        }

        DataBuffer tempI = AllocationUtils.getPointersBuffer(ints);
        PointerPointer iPtr = new PointerPointer(AtomicAllocator.getInstance().getPointer(tempI, context));

        int[] indexes = new int[numIndexArguments];
        for (int x = 0; x < numIndexArguments; x++) {
            indexes[x] = op.getIndexingArguments().get(x);
        }

        DataBuffer intBuffer = Nd4j.getDataBufferFactory().createInt(indexes);

        double[] reals = new double[numRealArguments];
        INDArray realsBuffer;
        for (int x = 0; x < numRealArguments; x++) {
            reals[x] = op.getRealArguments().get(x).doubleValue();
        }

        realsBuffer = Nd4j.create(reals, new long[]{reals.length}, dataType);

        nativeOps.execAggregate(extraArgs, op.opNum(), xPtr, numArguments, sPtr, numShapeArguments,
                    (IntPointer) AtomicAllocator.getInstance().getPointer(intBuffer, context),
                    numIndexArguments, iPtr, numIntArrays,
                    AtomicAllocator.getInstance().getPointer(realsBuffer.data(), context),
                    numRealArguments, FlatBuffersMapper.getDataTypeAsByte(dataType));

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());
    }

    /**
     * This method executes specified RandomOp using default RNG available via Nd4j.getRandom()
     *
     * @param op
     */
    @Override
    public INDArray exec(RandomOp op) {
        return exec(op, Nd4j.getRandom());
    }


    @Override
    public INDArray exec(RandomOp op, Random rng) {
        long st = profilingConfigurableHookIn(op);

        checkForCompression(op);

        //validateDataType(Nd4j.dataType(), op);

        if (rng.getStatePointer() == null)
            throw new IllegalStateException(
                    "You should use one of NativeRandom classes for NativeOperations execution");

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        if (CudaEnvironment.getInstance().getConfiguration().isDebug())
            lastOp.set(op.opName());

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(op.z(), op.x(), op.y());

        PointerPointer extraZZ = extraz.get().put(AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer()),
                context.getOldStream(), AtomicAllocator.getInstance().getDeviceIdPointer());

        val hostXShapeInfo = op.x() == null ? null : AddressRetriever.retrieveHostPointer(op.x().shapeInfoDataBuffer());
        val hostYShapeInfo = op.y() == null ? null : AddressRetriever.retrieveHostPointer(op.y().shapeInfoDataBuffer());
        val hostZShapeInfo = op.z() == null ? null : AddressRetriever.retrieveHostPointer(op.z().shapeInfoDataBuffer());

        if (op.x() != null && op.y() != null && op.z() != null) {
            // triple arg call
            nativeOps.execRandom3(extraZZ, op.opNum(), rng.getStatePointer(), // rng state ptr
                        null, (LongPointer) hostXShapeInfo, AtomicAllocator.getInstance().getPointer(op.x(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context),
                        null, (LongPointer) hostYShapeInfo, AtomicAllocator.getInstance().getPointer(op.y(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.y().shapeInfoDataBuffer(), context),
                        null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                        AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(op.z().dataType()), context));

        } else if (op.x() != null && op.z() != null) {
            //double arg call
            nativeOps.execRandom2(extraZZ, op.opNum(), rng.getStatePointer(), // rng state ptr
                        null, (LongPointer) hostXShapeInfo, AtomicAllocator.getInstance().getPointer(op.x(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.x().shapeInfoDataBuffer(), context),
                        null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                        AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(op.z().dataType()),context));


        } else {
            // single arg call
            nativeOps.execRandom(extraZZ, op.opNum(), rng.getStatePointer(), // rng state ptr
                        null, (LongPointer) hostZShapeInfo, AtomicAllocator.getInstance().getPointer(op.z(), context), (LongPointer) AtomicAllocator.getInstance().getPointer(op.z().shapeInfoDataBuffer(), context),
                         AtomicAllocator.getInstance().getPointer(op.extraArgsDataBuff(op.z().dataType()), context));
        }

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        AtomicAllocator.getInstance().getFlowController().registerAction(context, op.z(), op.x(), op.y());

        profilingConfigurableHookOut(op, st);

        return op.z();
    }

    /**
     * This method return set of key/value
     * and key/key/value objects,
     * describing current environment
     *
     * @return
     */
    @Override
    public synchronized Properties getEnvironmentInformation() {
        if (properties == null) {
            Properties props = super.getEnvironmentInformation();

            List<Map<String, Object>> devicesList = new ArrayList<>();

            // fill with per-device information: name, memory, versions
            for (int i = 0; i < nativeOps.getAvailableDevices(); i++) {
                Map<String, Object> deviceProps = new HashMap<>();

                deviceProps.put(Nd4jEnvironment.CUDA_DEVICE_NAME_KEY, nativeOps.getDeviceName(i));
                deviceProps.put(Nd4jEnvironment.CUDA_FREE_MEMORY_KEY, nativeOps.getDeviceFreeMemory(i));
                deviceProps.put(Nd4jEnvironment.CUDA_TOTAL_MEMORY_KEY, nativeOps.getDeviceTotalMemory(i));
                deviceProps.put(Nd4jEnvironment.CUDA_DEVICE_MAJOR_VERSION_KEY, (long) nativeOps.getDeviceMajor(i));
                deviceProps.put(Nd4jEnvironment.CUDA_DEVICE_MINOR_VERSION_KEY, (long) nativeOps.getDeviceMinor(i));

                devicesList.add(i, deviceProps);
            }

            // fill with basic general info
            props.put(Nd4jEnvironment.BACKEND_KEY, "CUDA");
            props.put(Nd4jEnvironment.CUDA_NUM_GPUS_KEY, nativeOps.getAvailableDevices());
            props.put(Nd4jEnvironment.CUDA_DEVICE_INFORMATION_KEY, devicesList);
            props.put(Nd4jEnvironment.BLAS_VENDOR_KEY, (Nd4j.factory().blas()).getBlasVendor().toString());
            props.put(Nd4jEnvironment.HOST_FREE_MEMORY_KEY, Pointer.maxBytes() - Pointer.totalBytes());

            // fill bandwidth information
            props.put(Nd4jEnvironment.MEMORY_BANDWIDTH_KEY, PerformanceTracker.getInstance().getCurrentBandwidth());

            properties = props;
        } else {

            List<Map<String, Object>> devicesList = (List<Map<String, Object>>) properties.get(Nd4jEnvironment.CUDA_DEVICE_INFORMATION_KEY);

            // just update information that might change over time
            for (int i = 0; i < nativeOps.getAvailableDevices(); i++) {
                Map<String, Object> dev = devicesList.get(i);

                dev.put(Nd4jEnvironment.CUDA_FREE_MEMORY_KEY, nativeOps.getDeviceFreeMemory(i));
                dev.put(Nd4jEnvironment.CUDA_TOTAL_MEMORY_KEY, nativeOps.getDeviceTotalMemory(i));
            }

            properties.put(Nd4jEnvironment.CUDA_DEVICE_INFORMATION_KEY, devicesList);
            properties.put(Nd4jEnvironment.HOST_FREE_MEMORY_KEY, Pointer.maxBytes() - Pointer.totalBytes());

            // fill bandwidth information
            properties.put(Nd4jEnvironment.MEMORY_BANDWIDTH_KEY, PerformanceTracker.getInstance().getCurrentBandwidth());
        }
        return properties;
    }

    @Override
    public TADManager getTADManager() {
        return tadManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void printEnvironmentInformation() {
        super.printEnvironmentInformation();

        Properties env = getEnvironmentInformation();

        List<Map<String, Object>> devicesList = (List<Map<String, Object>>) env.get(Nd4jEnvironment.CUDA_DEVICE_INFORMATION_KEY);
        for (Map<String, Object> dev : devicesList) {
            log.info("Device Name: [{}]; CC: [{}.{}]; Total/free memory: [{}]", dev.get(Nd4jEnvironment.CUDA_DEVICE_NAME_KEY),
                    dev.get(Nd4jEnvironment.CUDA_DEVICE_MAJOR_VERSION_KEY), dev.get(Nd4jEnvironment.CUDA_DEVICE_MINOR_VERSION_KEY), dev.get(Nd4jEnvironment.CUDA_TOTAL_MEMORY_KEY));
        }
    }

    @Override
    public void commit() {
        AtomicAllocator.getInstance().getDeviceContext().syncOldStream();
        AtomicAllocator.getInstance().getDeviceContext().syncSpecialStream();
    }

    @Override
    public INDArray thresholdEncode(INDArray input, double threshold, Integer boundary) {
        DataBuffer buffer = input.data();

        int numThreads = 1024;
        int numBlocks = (int) (buffer.length() / numThreads + (buffer.length() % numThreads == 0 ? 0 : 1));

        val context = AtomicAllocator.getInstance().getDeviceContext();

        DataBuffer blocksBuffer = Nd4j.getMemoryManager().getCurrentWorkspace() == null ? Nd4j.getDataBufferFactory().createInt(numBlocks+1, true) : Nd4j.getDataBufferFactory().createInt(numBlocks+1, true, Nd4j.getMemoryManager().getCurrentWorkspace());

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        val extras = extraz.get().put(1, context.getOldStream());



        NativeOpsHolder.getInstance().getDeviceNativeOps().encodeThresholdP1(extras,
                AtomicAllocator.getInstance().getPointer(buffer),
                (LongPointer) AtomicAllocator.getInstance().getHostPointer(input.shapeInfoDataBuffer()),
                buffer.length(),
                (IntPointer) AtomicAllocator.getInstance().getPointer(blocksBuffer),
                (float) threshold);

        AtomicAllocator.getInstance().getAllocationPoint(blocksBuffer).tickDeviceWrite();


        int numMatches = blocksBuffer.getInt(0);

        // special case here, nothing to update
        if (numMatches < 2)
            return null;

        if (boundary != null && numMatches > boundary)  {
            numMatches = boundary;
            blocksBuffer.put(0, numMatches);
        }

/*
        log.info("Totals: {}", numMatches);


        log.info("Number of blocks for compression: {}", numBlocks);
        log.info("BlocksCounts: {}", Arrays.toString(blocksBuffer.asInt()));
*/
        DataBuffer encodedBuffer = Nd4j.getMemoryManager().getCurrentWorkspace() == null ? Nd4j.getDataBufferFactory().createInt(4+numMatches, false) : Nd4j.getDataBufferFactory().createInt(4+numMatches, false, Nd4j.getMemoryManager().getCurrentWorkspace());
        AtomicAllocator.getInstance().getAllocationPoint(encodedBuffer).tickHostWrite();
        encodedBuffer.put(0, numMatches);
        encodedBuffer.put(1, (int) buffer.length());
        encodedBuffer.put(2, Float.floatToIntBits((float) threshold));
        AtomicAllocator.getInstance().getAllocationPoint(encodedBuffer).tickHostWrite();

        encodedBuffer.put(3, ThresholdCompression.FLEXIBLE_ENCODING);


        int prefixThreads = 512;
        int numElts = numBlocks;
        int level = 0;
        List<DataBuffer> buffers = new ArrayList<>();

        // here we just calculate number of sumBlock arrays
        do {
            int numPrefixBlocks = Math.max(1, (int)Math.ceil((float)numElts / (2.0f * prefixThreads)));
            if (numBlocks > 1) {
                level++;
            }
            numElts = numPrefixBlocks;
        } while (numElts > 1);

        long[] pointers = new long[level];

        level = 0;
        numElts = numBlocks;

        //  allocating temp buffers for prefux sum
        DataBuffer tempX = Nd4j.getMemoryManager().getCurrentWorkspace() == null ? Nd4j.getDataBufferFactory().createDouble(pointers.length, false) : Nd4j.getDataBufferFactory().createDouble(pointers.length, false, Nd4j.getMemoryManager().getCurrentWorkspace());

        do {
            int numPrefixBlocks = Math.max(1, (int)Math.ceil((float)numElts / (2.0f * prefixThreads)));
            if (numPrefixBlocks > 1) {
                DataBuffer bf = Nd4j.getMemoryManager().getCurrentWorkspace() == null ? Nd4j.getDataBufferFactory().createInt(numPrefixBlocks, false) : Nd4j.getDataBufferFactory().createInt(numPrefixBlocks, false, Nd4j.getMemoryManager().getCurrentWorkspace());

                buffers.add(bf);

                pointers[level++] = AtomicAllocator.getInstance().getPointer(bf).address();
            }
            numElts = numPrefixBlocks;
        } while (numElts > 1);


        AtomicAllocator.getInstance().memcpyBlocking(tempX, new LongPointer(pointers), pointers.length * 8, 0);

        extras.put(2, AtomicAllocator.getInstance().getPointer(tempX));

        DataBuffer offsetsBuffer = Nd4j.getMemoryManager().getCurrentWorkspace() == null ? Nd4j.getDataBufferFactory().createInt(numBlocks, true) : Nd4j.getDataBufferFactory().createInt(numBlocks, true, Nd4j.getMemoryManager().getCurrentWorkspace());

        NativeOpsHolder.getInstance().getDeviceNativeOps().encodeThresholdP2Int(extras, (IntPointer) AtomicAllocator.getInstance().getPointer(blocksBuffer), numBlocks, (IntPointer) AtomicAllocator.getInstance().getPointer(offsetsBuffer) );
        AtomicAllocator.getInstance().getAllocationPoint(offsetsBuffer).tickDeviceWrite();


        NativeOpsHolder.getInstance().getDeviceNativeOps().encodeThresholdP3(extras, AtomicAllocator.getInstance().getPointer(buffer), (LongPointer) AtomicAllocator.getInstance().getHostPointer(input.shapeInfoDataBuffer()), (IntPointer) AtomicAllocator.getInstance().getPointer(offsetsBuffer), buffer.length(), (IntPointer) AtomicAllocator.getInstance().getPointer(encodedBuffer));

        AtomicAllocator.getInstance().getAllocationPoint(encodedBuffer).tickDeviceWrite();
        AtomicAllocator.getInstance().getAllocationPoint(buffer).tickDeviceWrite();

        return Nd4j.createArrayFromShapeBuffer(encodedBuffer, input.shapeInfoDataBuffer());
    }


    @Override
    public INDArray thresholdEncode(INDArray input, double threshold) {
        return thresholdEncode(input, threshold, null);
    }

    @Override
    public INDArray thresholdDecode(INDArray encoded, INDArray target) {
        DataBuffer buffer = encoded.data();

        if (buffer.dataType() != DataType.INT)
            throw new UnsupportedOperationException();

        long compressedLength = buffer.getInt(0);
        long originalLength = buffer.getInt(1);

        if (target.length() != originalLength)
            throw new ND4JIllegalStateException("originalLength ["+ originalLength+"] stored in encoded array doesn't match target length ["+ target.length()+"]");

        DataBuffer result = target.data();

        val context = AtomicAllocator.getInstance().getDeviceContext();

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        PointerPointer extras = extraz.get().put(1, context.getOldStream());

        nativeOps.decodeThreshold(extras, AtomicAllocator.getInstance().getPointer(buffer), compressedLength, AtomicAllocator.getInstance().getPointer(result), (LongPointer) AtomicAllocator.getInstance().getHostPointer(target.shapeInfoDataBuffer()));

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        AtomicAllocator.getInstance().getAllocationPoint(result).tickDeviceWrite();

        return target;
    }


    @Override
    public long bitmapEncode(INDArray indArray, INDArray target, double threshold) {
        long length = indArray.length();
        long tLen = target.data().length();

        if (tLen != (length / 16 + 5))
            throw new ND4JIllegalStateException("Length of target array should be " + (length / 16 + 5));

        if (target.data().dataType() != DataType.INT)
            throw new ND4JIllegalStateException("Target array should have INT dataType");

        DataBuffer buffer = target.data();
        buffer.put(0, (int) length);
        buffer.put(1, (int) length);
        buffer.put(2, Float.floatToIntBits((float) threshold));

        // format id
        buffer.put(3, ThresholdCompression.BITMAP_ENCODING);

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(indArray);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));


        PointerPointer extras = extraz.get().put(
                AtomicAllocator.getInstance().getHostPointer(indArray),
                context.getOldStream(),
                context.getBufferScalar(),
                context.getBufferReduction()
        );

        long val = nativeOps.encodeBitmap(extras,
                    AtomicAllocator.getInstance().getPointer(indArray, context), (LongPointer) AtomicAllocator.getInstance().getHostPointer(indArray.shapeInfoDataBuffer()),
                    length,
                    (IntPointer) AtomicAllocator.getInstance().getPointer(buffer, context),
                    (float) threshold);

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        AtomicAllocator.getInstance().getFlowController().registerAction(context, indArray);

        AtomicAllocator.getInstance().getAllocationPoint(buffer).tickDeviceWrite();

        return val;
    }

    @Override
    public INDArray bitmapDecode(INDArray encoded, INDArray target) {

        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(target);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));


        PointerPointer extras = extraz.get().put(
                AtomicAllocator.getInstance().getHostPointer(target),
                context.getOldStream(),
                context.getBufferScalar(),
                context.getBufferReduction());

        nativeOps.decodeBitmap(extras, AtomicAllocator.getInstance().getPointer(encoded.data(), context), target.length(), AtomicAllocator.getInstance().getPointer(target, context), (LongPointer) AtomicAllocator.getInstance().getHostPointer(target.shapeInfoDataBuffer()));

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        AtomicAllocator.getInstance().getFlowController().registerAction(context, target);

        return target;
    }


    @Override
    public synchronized Map<String, CustomOpDescriptor> getCustomOperations() {
        if(customOps == null) {
            String list = nativeOps.getAllCustomOps();

            if (list == null || list.isEmpty()) {
                log.warn("No customs ops available!");
                customOps = Collections.emptyMap();
                return customOps;
            }

            val map = new HashMap<String, CustomOpDescriptor>();

            String[] split = list.split(";");
            for (String op : split) {
                if (op == null || op.isEmpty())
                    continue;

                String[] another = op.split(":");

                CustomOpDescriptor descriptor = CustomOpDescriptor.builder()
                        .hash(Long.valueOf(another[1]))
                        .numInputs(Integer.valueOf(another[2]))
                        .numOutputs(Integer.valueOf(another[3]))
                        .allowsInplace(Integer.valueOf(another[4]) == 1)
                        .numTArgs(Integer.valueOf(another[5]))
                        .numIArgs(Integer.valueOf(another[6]))
                        .build();

                map.put(another[0], descriptor);
            }

            customOps = Collections.unmodifiableMap(map);
        }

        return customOps;
    }



    protected LongShapeDescriptor getShapeFromPointer(LongPointer ptr) {
        val rank = (int) ptr.get(0);

        val shape = new long[rank * 2 + 4];
        for (int i = 0; i < shape.length; i++) {
            shape[i] = ptr.get(i);
        }

        //val extras = ptr.get(Shape.shapeInfoLength(rank) - 3);
        val t = ArrayOptionsHelper.arrayType(shape);
        return LongShapeDescriptor.fromShape(Shape.shape(shape), Shape.stride(shape), Shape.elementWiseStride(shape), Shape.order(shape), ArrayOptionsHelper.dataType(shape), t == ArrayType.EMPTY);
    }

    @Override
    public List<LongShapeDescriptor> calculateOutputShape(@NonNull CustomOp op) {

        Nd4j.getExecutioner().commit();

        val lc = op.opName().toLowerCase();
        val hash = op.opHash();

        val result = new ArrayList<LongShapeDescriptor>();
        if(op.numInputArguments() < 1 && op.getDescriptor().getNumInputs() != -2) {
            if(log.isTraceEnabled()){
                log.trace("Could not calculate output shape for op {}: number of input args was 0",
                        op.getClass().getName());
            }
            return Collections.emptyList();
        }

        val inputBuffers = new PointerPointer<>(op.inputArguments().length * 2);
        val inputShapes = new PointerPointer<>(op.inputArguments().length);

        int cnt= 0;
        for (val in: op.inputArguments()) {
            // NOT A TYPO: shape functions work on host side only
            if (!in.isEmpty()) {
                inputBuffers.put(cnt, in.data().addressPointer());
                inputBuffers.put(cnt + op.inputArguments().length, AtomicAllocator.getInstance().getPointer(in.data()));
            }

            inputShapes.put(cnt++, in.shapeInfoDataBuffer().addressPointer());
        }


        val iArgs = op.iArgs().length > 0 ? new LongPointer(op.iArgs().length) : null;
        cnt = 0;
        for (val i: op.iArgs())
            iArgs.put(cnt++, i);


        val tArgs = op.tArgs().length > 0 ? new DoublePointer(op.tArgs().length) : null;

        val bArgs = op.bArgs().length > 0 ? new BooleanPointer(op.bArgs().length) : null;

        cnt = 0;
        for (val b: op.bArgs())
            bArgs.put(cnt++, b);

        cnt = 0;
        for (val t: op.tArgs())
            tArgs.put(cnt++, (float) t);

        OpaqueShapeList ptrptr = nativeOps.calculateOutputShapes2(null, hash, inputBuffers, inputShapes, op.inputArguments().length, tArgs, op.tArgs().length, iArgs, op.iArgs().length, bArgs, op.numBArguments());

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        if (ptrptr == null)
            throw new RuntimeException();

        for (int e = 0; e < nativeOps.getShapeListSize(ptrptr); e++ )
            result.add(getShapeFromPointer(new PagedPointer(nativeOps.getShape(ptrptr, e)).asLongPointer()));

        nativeOps.deleteShapeList(ptrptr);


        return result;
    }

    /**
     * This method executes given CustomOp
     *
     * PLEASE NOTE: You're responsible for input/output validation
     * PLEASE NOTE: right now this operations are executing on CPU
     * @param op
     */
    @Override
    public INDArray[] exec(CustomOp op) {

        Nd4j.getExecutioner().commit();

        //
        if (op.numOutputArguments() == 0 && !op.isInplaceCall()) {
            try {
                val list = this.calculateOutputShape(op);
                if (list.isEmpty())
                    throw new ND4JIllegalStateException("Op name " + op.opName() + " failed to execute. You can't execute non-inplace CustomOp without outputs being specified");

                for (val shape: list)
                    op.addOutputArgument(Nd4j.create(shape));

            } catch (Exception e) {
                throw new ND4JIllegalStateException("Op name " + op.opName() + " failed to execute. You can't execute non-inplace CustomOp without outputs being specified");
            }
        }

        val ctx = AtomicAllocator.getInstance().getDeviceContext();

        val name = op.opName();
        try (val context = (CudaOpContext) buildContext()) {

            context.markInplace(op.isInplaceCall());

            // transferring rng state
            context.setRngStates(Nd4j.getRandom().rootState(), Nd4j.getRandom().nodeState());

            //transferring input/output arrays
            context.setInputArrays(op.inputArguments());
            context.setOutputArrays(op.outputArguments());

            // transferring static args
            context.setBArguments(op.bArgs());
            context.setIArguments(op.iArgs());
            context.setTArguments(op.tArgs());

            val result = exec(op, context);
            val states = context.getRngStates();

            // pulling states back
            Nd4j.getRandom().setStates(states.getFirst(), states.getSecond());

            return result;
        } catch (ND4JOpProfilerException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Op [" + name + "] execution failed", e);
        }
    }

    @Override
    public void enableDebugMode(boolean reallyEnable) {
        debug.set(reallyEnable);
        nativeOps.enableDebugMode(reallyEnable);
    }

    @Override
    public void enableVerboseMode(boolean reallyEnable) {
        verbose.set(reallyEnable);
        nativeOps.enableVerboseMode(reallyEnable);
    }

    @Override
    public void registerGraph(long id, Pointer graph) {
        nativeOps.registerGraph(null, id, graph);

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());
    }

    @Override
    public Map<String, INDArray> executeGraph(long id, @NonNull Map<String, INDArray> map, @NonNull Map<String, Integer> reverseMap) {

        Nd4j.getExecutioner().commit();

        val ptrBuffers = new PointerPointer(map.size() * 2);
        val ptrShapes = new PointerPointer(map.size() * 2);
        val ptrIndices = new IntPointer(map.size());

        int cnt = 0;
        val keySet = new ArrayList<String>(map.keySet());
        for (val key: keySet) {
            val array = map.get(key);

            ptrBuffers.put(cnt, AtomicAllocator.getInstance().getHostPointer(array));
            ptrShapes.put(cnt, AtomicAllocator.getInstance().getHostPointer(array.shapeInfoDataBuffer()));
            ptrIndices.put(cnt, reverseMap.get(key));

            cnt++;
        }

        val newMap = new LinkedHashMap<String, INDArray>();

        OpaqueVariablesSet result = nativeOps.executeStoredGraph(null, id, ptrBuffers, ptrShapes, ptrIndices, map.size());

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        OpStatus status = OpStatus.byNumber(nativeOps.getVariablesSetStatus(result));

        if (status != OpStatus.ND4J_STATUS_OK)
            throw new ND4JIllegalStateException("Op execution failed: " + status);

        for (int e = 0; e < nativeOps.getVariablesSetSize(result); e++) {
            OpaqueVariable var = nativeOps.getVariable(result, e);
            int nodeId = nativeOps.getVariableId(var);
            int index = nativeOps.getVariableIndex(var);
            LongPointer shapeInfo = nativeOps.getVariableShape(var);
            Pointer buffer = nativeOps.getVariableBuffer(var);

            val rank = (int) shapeInfo.get(0);
            val jshape = new long[rank * 2 + 4];
            for (int i = 0; i < jshape.length; i++) {
                jshape[i] = shapeInfo.get(i);
            }

            val shapeOf = Shape.shapeOf(jshape);
            val stridesOf = Shape.stridesOf(jshape);
            val order = Shape.order(jshape);
            val array = Nd4j.create(shapeOf, stridesOf, 0, order);

            Pointer.memcpy(AtomicAllocator.getInstance().getHostPointer(array), buffer, ArrayUtil.prod(shapeOf) * Nd4j.sizeOfDataType());
            AtomicAllocator.getInstance().getAllocationPoint(array).tickHostWrite();

            String nodeName = nativeOps.getVariableName(var);
            newMap.put(nodeName, array);
        }

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        nativeOps.deleteVariablesSet(result);

        return newMap;
    }

    @Override
    public void forgetGraph(long id) {
        nativeOps.unregisterGraph(null, id);

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());
    }

    /**
     * This method allows to set desired number of elements per thread, for performance optimization purposes.
     * I.e. if array contains 2048 elements, and threshold is set to 1024, 2 threads will be used for given op execution.
     * <p>
     * Default value: 1024
     *
     * @param threshold
     */
    @Override
    public void setElementsThreshold(int threshold) {
        nativeOps.setElementThreshold(threshold);
    }

    /**
     * This method allows to set desired number of sub-arrays per thread, for performance optimization purposes.
     * I.e. if matrix has shape of 64 x 128, and threshold is set to 8, each thread will be processing 8 sub-arrays (sure, if you have 8 core cpu).
     * If your cpu has, say, 4, cores, only 4 threads will be spawned, and each will process 16 sub-arrays
     * <p>
     * Default value: 8
     *
     * @param threshold
     */
    @Override
    public void setTadThreshold(int threshold) {
        nativeOps.setTADThreshold(threshold);
    }


    @Override
    public ExecutionerType type() {
        return ExecutionerType.CUDA;
    }

    @Override
    public String getString(Utf8Buffer buffer, long index) {
        val addr = ((LongIndexer) buffer.indexer()).get(index);
        val ptr = new PagedPointer(addr);
        val str = new Nd4jCuda.utf8string(ptr);
        return str._buffer().capacity(str._length()).getString();
    }

    @Override
    public boolean isExperimentalMode() {
        return experimentalMode.get();
    }

    @Override
    public void scatterUpdate(ScatterUpdate.UpdateOp op, @NonNull INDArray array, @NonNull INDArray indices, @NonNull INDArray updates, @NonNull int[] axis) {
        CudaContext context = AtomicAllocator.getInstance().getFlowController().prepareAction(array, indices, updates);

        val tadX = tadManager.getTADOnlyShapeInfo(array, axis);
        val tadY = tadManager.getTADOnlyShapeInfo(updates, axis);

        if (tadY.getSecond().length() != indices.length())
            throw new IllegalStateException("Number of updates doesn't match number of indices. Bad dimensions used?");

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        val stuff = extraz.get().put(null, context.getOldStream());

        nativeOps.scatterUpdate(stuff, op.ordinal(), (int) indices.length(),
                null, (LongPointer) AtomicAllocator.getInstance().getHostPointer(tadX.getFirst()), null, AtomicAllocator.getInstance().getPointer(array, context), (LongPointer) AtomicAllocator.getInstance().getPointer(tadX.getFirst()), (LongPointer) AtomicAllocator.getInstance().getPointer(tadX.getSecond()),
                null, (LongPointer) AtomicAllocator.getInstance().getHostPointer(tadY.getFirst()), null, AtomicAllocator.getInstance().getPointer(updates, context), (LongPointer) AtomicAllocator.getInstance().getPointer(tadY.getFirst()), (LongPointer) AtomicAllocator.getInstance().getPointer(tadY.getSecond()),
                null, (IntPointer) AtomicAllocator.getInstance().getPointer(indices, context));

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        AtomicAllocator.getInstance().getFlowController().registerAction(context, array, indices, updates);
    }

    @Override
    public OpContext buildContext() {
        return new CudaOpContext();
    }

    @Override
    public INDArray[] exec(CustomOp op, OpContext context) {
        long st = profilingConfigurableHookIn(op);

        val ctx = AtomicAllocator.getInstance().getDeviceContext();
        ((CudaOpContext) context).setCudaStream(ctx.getOldStream(), ctx.getBufferReduction(), ctx.getBufferAllocation());

        val status = nativeOps.execCustomOp2(null, op.opHash(), context.contextPointer());
        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        if (status != 0)
            throw new RuntimeException("Op [" + op.opName() + "] execution failed");



        for (val arr:op.outputArguments())
            AtomicAllocator.getInstance().registerAction(ctx, arr);

        AtomicAllocator.getInstance().registerAction(ctx, null, op.inputArguments());

        profilingConfigurableHookOut(op, st);

        if (context.getOutputArrays().isEmpty())
            return new INDArray[0];
        else
            return context.getOutputArrays().toArray(new INDArray[context.getOutputArrays().size()]);
    }

    @Override
    public INDArrayStatistics inspectArray(@NonNull INDArray array) {
        val debugInfo = new Nd4jCuda.DebugInfo();
        val ctx = AtomicAllocator.getInstance().getDeviceContext();
        AtomicAllocator.getInstance().synchronizeHostData(array);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        val extras = extraz.get().put(
                null,
                ctx.getOldStream(),
                AtomicAllocator.getInstance().getDeviceIdPointer(),
                ctx.getBufferAllocation(),
                ctx.getBufferReduction(),
                ctx.getBufferScalar(),
                ctx.getBufferSpecial());


        nativeOps.inspectArray(extras, AtomicAllocator.getInstance().getHostPointer(array), (LongPointer) AtomicAllocator.getInstance().getHostPointer(array.shapeInfoDataBuffer()), AtomicAllocator.getInstance().getPointer(array, ctx), (LongPointer) AtomicAllocator.getInstance().getPointer(array.shapeInfoDataBuffer()), debugInfo);

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        return INDArrayStatistics.builder()
                .minValue(debugInfo._minValue())
                .maxValue(debugInfo._maxValue())
                .meanValue(debugInfo._meanValue())
                .stdDevValue(debugInfo._stdDevValue())
                .countInf(debugInfo._infCount())
                .countNaN(debugInfo._nanCount())
                .countNegative(debugInfo._negativeCount())
                .countPositive(debugInfo._positiveCount())
                .countZero(debugInfo._zeroCount())
                .build();
    }


    @Override
    public DataBuffer createShapeInfo(long[] shape, long[] stride, long elementWiseStride, char order, DataType dtype, boolean empty) {
        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        OpaqueConstantDataBuffer dbf = nativeOps.shapeBuffer(shape.length, new LongPointer(shape), new LongPointer(stride), dtype.toInt(), order, elementWiseStride, empty);

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        val result = new CudaLongDataBuffer(nativeOps.getConstantDataBufferPrimary(dbf), nativeOps.getConstantDataBufferSpecial(dbf), Shape.shapeInfoLength(shape.length));

        nativeOps.deleteShapeBuffer(dbf);

        return result;
    }

    @Override
    public TadPack tadShapeInfoAndOffsets(INDArray array, int[] dimension) {
        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        OpaqueTadPack pack = nativeOps.tadOnlyShapeInfo((LongPointer) array.shapeInfoDataBuffer().addressPointer(), new IntPointer(dimension), dimension.length);

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        val tadShape = new CudaLongDataBuffer(nativeOps.getPrimaryShapeInfo(pack), nativeOps.getSpecialShapeInfo(pack), nativeOps.getShapeInfoLength(pack));
        val tadOffsets = new CudaLongDataBuffer(nativeOps.getPrimaryOffsets(pack), nativeOps.getSpecialOffsets(pack), nativeOps.getNumberOfTads(pack));

        nativeOps.deleteTadPack(pack);

        return new TadPack(tadShape, tadOffsets);
    }

    @Override
    public DataBuffer createConstantBuffer(long[] values, DataType desiredType) {
        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        OpaqueConstantDataBuffer dbf = nativeOps.constantBufferLong(desiredType.toInt(), new LongPointer(values), values.length);

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        val buffer = Nd4j.createBuffer(nativeOps.getConstantDataBufferPrimary(dbf), nativeOps.getConstantDataBufferSpecial(dbf), values.length, desiredType);
        buffer.setConstant(true);

        return buffer;
    }

    @Override
    public DataBuffer createConstantBuffer(double[] values, DataType desiredType)  {
        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        OpaqueConstantDataBuffer dbf = nativeOps.constantBufferDouble(desiredType.toInt(), new DoublePointer(values), values.length);

        if (nativeOps.lastErrorCode() != 0)
            throw new RuntimeException(nativeOps.lastErrorMessage());

        val buffer = Nd4j.createBuffer(nativeOps.getConstantDataBufferPrimary(dbf), nativeOps.getConstantDataBufferSpecial(dbf), values.length, desiredType);
        buffer.setConstant(true);

        return buffer;
    }

    @Override
    public String runLightBenchmarkSuit(boolean printOut) {
        return nativeOps.runLightBenchmarkSuit(printOut);
    }

    @Override
    public String runFullBenchmarkSuit(boolean printOut) {
        return nativeOps.runFullBenchmarkSuit(printOut);
    }
}


