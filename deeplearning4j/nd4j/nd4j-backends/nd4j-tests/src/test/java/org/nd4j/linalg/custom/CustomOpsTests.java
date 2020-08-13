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

package org.nd4j.linalg.custom;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.junit.Ignore;
import org.junit.Test;
import org.nd4j.linalg.BaseNd4jTest;
import org.nd4j.linalg.api.blas.params.MMulTranspose;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.CustomOp;
import org.nd4j.linalg.api.ops.DynamicCustomOp;
import org.nd4j.linalg.api.ops.custom.Flatten;
import org.nd4j.linalg.api.ops.custom.ScatterUpdate;
import org.nd4j.linalg.api.ops.executioner.OpExecutioner;
import org.nd4j.linalg.api.ops.executioner.OpStatus;
import org.nd4j.linalg.api.ops.impl.reduce.Mmul;
import org.nd4j.linalg.api.ops.impl.reduce.MmulBp;
import org.nd4j.linalg.api.ops.impl.transforms.any.IsMax;
import org.nd4j.linalg.api.ops.impl.transforms.pairwise.arithmetic.AddOp;
import org.nd4j.linalg.api.ops.impl.transforms.pairwise.arithmetic.ModOp;
import org.nd4j.linalg.api.ops.random.compat.RandomStandardNormal;
import org.nd4j.linalg.api.shape.LongShapeDescriptor;
import org.nd4j.linalg.api.shape.Shape;
import org.nd4j.linalg.exception.ND4JIllegalStateException;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.factory.Nd4jBackend;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.nativeblas.NativeOpsHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * This class holds various CustomOps tests
 *
 * @author raver119@gmail.com
 */
@Slf4j
public class CustomOpsTests extends BaseNd4jTest {

    public CustomOpsTests(Nd4jBackend b){
        super(b);
    }

    @Override
    public char ordering(){
        return 'c';
    }

    @Test
    public void testNonInplaceOp1() {
        val arrayX = Nd4j.create(10, 10);
        val arrayY = Nd4j.create(10, 10);
        val arrayZ = Nd4j.create(10, 10);

        arrayX.assign(3.0);
        arrayY.assign(1.0);

        val exp = Nd4j.create(10,10).assign(4.0);

        CustomOp op = DynamicCustomOp.builder("add")
                .addInputs(arrayX, arrayY)
                .addOutputs(arrayZ)
                .build();

        Nd4j.getExecutioner().exec(op);

        assertEquals(exp, arrayZ);
    }

    /**
     * This test works inplace, but without inplace declaration
     */
    @Test
    public void testNonInplaceOp2() {
        val arrayX = Nd4j.create(10, 10);
        val arrayY = Nd4j.create(10, 10);

        arrayX.assign(3.0);
        arrayY.assign(1.0);

        val exp = Nd4j.create(10,10).assign(4.0);

        CustomOp op = DynamicCustomOp.builder("add")
                .addInputs(arrayX, arrayY)
                .addOutputs(arrayX)
                .build();

        Nd4j.getExecutioner().exec(op);

        assertEquals(exp, arrayX);
    }

    @Test
    @Ignore // it's noop, we dont care anymore
    public void testNoOp1() {
        val arrayX = Nd4j.create(10, 10);
        val arrayY = Nd4j.create(5, 3);

        arrayX.assign(3.0);
        arrayY.assign(1.0);

        val expX = Nd4j.create(10,10).assign(3.0);
        val expY = Nd4j.create(5,3).assign(1.0);

        CustomOp op = DynamicCustomOp.builder("noop")
                .addInputs(arrayX, arrayY)
                .addOutputs(arrayX, arrayY)
                .build();

        Nd4j.getExecutioner().exec(op);

        assertEquals(expX, arrayX);
        assertEquals(expY, arrayY);
    }

    @Test
    public void testFloor() {
        val arrayX = Nd4j.create(10, 10);

        arrayX.assign(3.0);

        val exp = Nd4j.create(10,10).assign(3.0);

        CustomOp op = DynamicCustomOp.builder("floor")
                .addInputs(arrayX)
                .addOutputs(arrayX)
                .build();

        Nd4j.getExecutioner().exec(op);

        assertEquals(exp, arrayX);
    }

    @Test(expected = ND4JIllegalStateException.class)
    public void testInplaceOp1() {
        val arrayX = Nd4j.create(10, 10);
        val arrayY = Nd4j.create(10, 10);

        arrayX.assign(4.0);
        arrayY.assign(2.0);

        val exp = Nd4j.create(10,10).assign(6.0);

        CustomOp op = DynamicCustomOp.builder("add")
                .addInputs(arrayX, arrayY)
                .callInplace(true)
                .build();

        Nd4j.getExecutioner().exec(op);

        assertEquals(exp, arrayX);
    }

    @Test
    public void testNoneInplaceOp3() {
        val arrayX = Nd4j.create(10, 10);
        val arrayY = Nd4j.create(10, 10);

        arrayX.assign(4.0);
        arrayY.assign(2.0);

        val exp = Nd4j.create(10,10).assign(6.0);

        CustomOp op = DynamicCustomOp.builder("add")
                .addInputs(arrayX, arrayY)
                .callInplace(false)
                .build();

        Nd4j.getExecutioner().exec(op);

        assertEquals(exp, op.getOutputArgument(0));
    }

    @Test
    public void testNoneInplaceOp4() {
        val arrayX = Nd4j.create(DataType.INT, 10, 10);
        val arrayY = Nd4j.create(DataType.INT, 10, 10);

        arrayX.assign(4);
        arrayY.assign(2);

        val exp = Nd4j.create(DataType.INT,10, 10).assign(6);

        CustomOp op = DynamicCustomOp.builder("add")
                .addInputs(arrayX, arrayY)
                .callInplace(false)
                .build();

        Nd4j.getExecutioner().exec(op);

        val res = op.getOutputArgument(0);
        assertEquals(DataType.INT, res.dataType());
        assertEquals(exp, res);
    }

    @Test
    public void testNoneInplaceOp5() {
        if (!Nd4j.isExperimentalMode())
            return;

        val arrayX = Nd4j.create(DataType.INT, 10, 10);
        val arrayY = Nd4j.create(DataType.FLOAT, 10, 10);

        arrayX.assign(4);
        arrayY.assign(2.0);

        val exp = Nd4j.create(DataType.FLOAT,10, 10).assign(6);

        CustomOp op = DynamicCustomOp.builder("add")
                .addInputs(arrayX, arrayY)
                .callInplace(false)
                .build();

        Nd4j.getExecutioner().exec(op);

        val res = op.getOutputArgument(0);
        assertEquals(DataType.FLOAT, res.dataType());
        assertEquals(exp, res);
    }

    @Test
    public void testMergeMax1() {
        val array0 = Nd4j.create(new double[] {1, 0, 0, 0, 0});
        val array1 = Nd4j.create(new double[] {0, 2, 0, 0, 0});
        val array2 = Nd4j.create(new double[] {0, 0, 3, 0, 0});
        val array3 = Nd4j.create(new double[] {0, 0, 0, 4, 0});
        val array4 = Nd4j.create(new double[] {0, 0, 0, 0, 5});

        val z = Nd4j.create(DataType.DOUBLE, 5);
        val exp = Nd4j.create(new double[]{1, 2, 3, 4, 5});

        CustomOp op = DynamicCustomOp.builder("mergemax")
                .addInputs(array0, array1, array2, array3, array4)
                .addOutputs(z)
                .callInplace(false)
                .build();

        Nd4j.getExecutioner().exec(op);

        assertEquals(exp, z);
    }

    @Test
    public void testMergeMaxF() {

        val array0 = Nd4j.rand('f', 5, 2).add(1); //some random array with +ve numbers
        val array1 = array0.dup('f').add(5);
        array1.put(0, 0, 0); //array1 is always bigger than array0 except at 0,0

        //expected value of maxmerge
        val exp = array1.dup('f');
        exp.putScalar(0, 0, array0.getDouble(0, 0));

        val zF = Nd4j.zeros(array0.shape(), 'f');
        CustomOp op = DynamicCustomOp.builder("mergemax")
                .addInputs(array0, array1)
                .addOutputs(zF)
                .build();
        Nd4j.getExecutioner().exec(op);

        assertEquals(exp, zF);
    }

    @Test
    public void testMergeMaxMixedOrder_Subtract() {
        val exp = Nd4j.create(new int[] {2, 2}, 'c').assign(5.0);
        Nd4j.getExecutioner().commit();

        val array0 = Nd4j.create(new int[] {2, 2}, 'f'); //some random array with +ve numbers
        val array1 = array0.dup('c').addi(5.0);

        Nd4j.getExecutioner().commit();

        assertEquals(exp, array1);
    }

    @Test
    public void testMergeMaxSameOrder_Subtract() {
        val exp = Nd4j.create(new int[] {2, 2}, 'c').assign(5.0);
        Nd4j.getExecutioner().commit();

        val array0 = Nd4j.create(new int[] {2, 2}, 'c'); //some random array with +ve numbers
        val array1 = array0.dup('c').addi(5);

        assertEquals(exp, array1);
    }

    @Test
    public void testMergeMaxMixedOrder() {
        val array0 = Nd4j.rand('f', 5, 2).addi(1); //some random array with +ve numbers
        val array1 = array0.dup('c').addi(5);
        array1.put(0, 0, 0); //array1 is always bigger than array0 except at 0,0

        //expected value of maxmerge
        val exp = array1.dup();
        exp.putScalar(0, 0, array0.getDouble(0, 0));

        val zF = Nd4j.zeros(array0.shape() ,'f');
        CustomOp op = DynamicCustomOp.builder("mergemax")
                .addInputs(array0, array1)
                .addOutputs(zF)
                .callInplace(false)
                .build();
        Nd4j.getExecutioner().exec(op);

        assertEquals(exp, zF);
    }


    @Test
    public void testOutputShapes1() {
        val array0 = Nd4j.rand('f', 5, 2).addi(1); //some random array with +ve numbers
        val array1 = array0.dup().addi(5);
        array1.put(0, 0, 0); //array1 is always bigger than array0 except at 0,0

        //expected value of maxmerge
        val exp = array1.dup();
        exp.putScalar(0, 0, array0.getDouble(0, 0));

        CustomOp op = DynamicCustomOp.builder("mergemax")
                .addInputs(array0, array1)
                .build();

        val shapes = Nd4j.getExecutioner().calculateOutputShape(op);

        assertEquals(1, shapes.size());
        assertArrayEquals(new long[]{5, 2}, shapes.get(0).getShape());
    }


    @Test
    public void testScatterUpdate1() {
        val matrix = Nd4j.create(5, 5);
        val updates = Nd4j.create(2, 5).assign(1.0);
        int[] dims = new int[]{1};
        int[] indices = new int[]{1, 3};

        val exp0 = Nd4j.create(5).assign(0);
        val exp1 = Nd4j.create(5).assign(1);

        ScatterUpdate op = new ScatterUpdate(matrix, updates, indices, dims, ScatterUpdate.UpdateOp.ADD);
        Nd4j.getExecutioner().exec(op);

        log.info("Matrix: {}", matrix);
        assertEquals(exp0, matrix.getRow(0));
        assertEquals(exp1, matrix.getRow(1));
        assertEquals(exp0, matrix.getRow(2));
        assertEquals(exp1, matrix.getRow(3));
        assertEquals(exp0, matrix.getRow(4));
    }

    @Test(expected = ND4JIllegalStateException.class)
    public void testScatterUpdate2() {
        val matrix = Nd4j.create(5, 5);
        val updates = Nd4j.create(2, 5).assign(1.0);
        int[] dims = new int[]{0};
        int[] indices = new int[]{0, 1};

        val exp0 = Nd4j.create(1, 5).assign(0);
        val exp1 = Nd4j.create(1, 5).assign(1);

        ScatterUpdate op = new ScatterUpdate(matrix, updates, indices, dims, ScatterUpdate.UpdateOp.ADD);
    }

    @Test(expected = ND4JIllegalStateException.class)
    public void testScatterUpdate3() {
        val matrix = Nd4j.create(5, 5);
        val updates = Nd4j.create(2, 5).assign(1.0);
        int[] dims = new int[]{1};
        int[] indices = new int[]{0, 6};

        val exp0 = Nd4j.create(1, 5).assign(0);
        val exp1 = Nd4j.create(1, 5).assign(1);

        ScatterUpdate op = new ScatterUpdate(matrix, updates, indices, dims, ScatterUpdate.UpdateOp.ADD);
    }

    @Test
    public void testOpStatus1() {
        assertEquals(OpStatus.ND4J_STATUS_OK, OpStatus.byNumber(0));
    }

    @Test
    public void testRandomStandardNormal_1() {
        if (Nd4j.getExecutioner().type() == OpExecutioner.ExecutionerType.CUDA)
            return;

        val shape = Nd4j.create(new float[] {5, 10});
        val op = new RandomStandardNormal(shape);

        Nd4j.getExecutioner().exec(op);

        assertEquals(1, op.outputArguments().length);
        val output = op.getOutputArgument(0);

        assertArrayEquals(new long[]{5, 10}, output.shape());
    }

    @Test
    public void testRandomStandardNormal_2() {
        if (Nd4j.getExecutioner().type() == OpExecutioner.ExecutionerType.CUDA)
            return;

        val shape = new long[]{5, 10};
        val op = new RandomStandardNormal(shape);

        Nd4j.getExecutioner().exec(op);

        assertEquals(1, op.outputArguments().length);
        val output = op.getOutputArgument(0);

        assertArrayEquals(new long[]{5, 10}, output.shape());
    }

    @Test
    public void testOpContextExecution_1() {
        val arrayX = Nd4j.createFromArray(new float[]{1, 2, 3, 4, 5});
        val arrayY = Nd4j.createFromArray(new float[]{1, 2, 3, 4, 5});
        val arrayZ = Nd4j.create(DataType.FLOAT, 5);

        val exp = Nd4j.createFromArray(new float[]{2, 4, 6, 8, 10});

        val context = Nd4j.getExecutioner().buildContext();
        context.setInputArray(0, arrayX);
        context.setInputArray(1, arrayY);
        context.setOutputArray(0, arrayZ);

        val addOp = new AddOp();
        NativeOpsHolder.getInstance().getDeviceNativeOps().execCustomOp2(null, addOp.opHash(), context.contextPointer());

        assertEquals(exp, arrayZ);
    }

    @Test
    public void testOpContextExecution_2() {
        val arrayX = Nd4j.createFromArray(new float[]{1, 2, 3, 4, 5});
        val arrayY = Nd4j.createFromArray(new float[]{1, 2, 3, 4, 5});
        val arrayZ = Nd4j.create(DataType.FLOAT, 5);

        val exp = Nd4j.createFromArray(new float[]{2, 4, 6, 8, 10});

        val context = Nd4j.getExecutioner().buildContext();
        context.setInputArray(0, arrayX);
        context.setInputArray(1, arrayY);
        context.setOutputArray(0, arrayZ);

        val addOp = new AddOp();
        val output = Nd4j.exec(addOp, context);

        assertEquals(exp, arrayZ);
        assertTrue(arrayZ == output[0]);
    }

    @Test
    public void testOpContextExecution_3() {
        val arrayX = Nd4j.create(100);
        val arrayY = Nd4j.ones(100);
        val arrayZ = Nd4j.create(100);

        val exp = Nd4j.ones(100);

        val context = Nd4j.getExecutioner().buildContext();
        context.setInputArray(0, arrayX);
        context.setInputArray(1, arrayY);

        context.setOutputArray(0, arrayZ);

        val addOp = new AddOp();
        val output = Nd4j.exec(addOp, context);

        assertEquals(exp, arrayZ);
        assertTrue(arrayZ == output[0]);
    }

    @Test
    public void testFlatten_1() {
        val arrayA = Nd4j.createFromArray(1.f, 2.f, 3.f);
        val arrayB = Nd4j.createFromArray(4.f, 5.f, 6.f);
        val arrayC = Nd4j.createFromArray(7.f, 8.f, 9.f);

        val exp = Nd4j.createFromArray(1.f, 2.f, 3.f, 4.f, 5.f, 6.f, 7.f, 8.f, 9.f);

        val result = Nd4j.exec(new Flatten('c', arrayA, arrayB, arrayC))[0];

        assertEquals(exp, result);
    }

    @Test
    public void testMatmulBp() {
        val a = Nd4j.create(DataType.DOUBLE, 1,3);
        val b = Nd4j.create(DataType.DOUBLE, 1,4);
        val gI = Nd4j.create(DataType.DOUBLE, 3,4);

        val gA = Nd4j.create(DataType.DOUBLE, 1,3);
        val gB = Nd4j.create(DataType.DOUBLE, 1,4);

        val mt = MMulTranspose.builder()
                .transposeA(true)
                .transposeB(false)
                .transposeResult(false).build();

        val op = new MmulBp(a, b, gI, gA, gB, mt);
        Nd4j.exec(op);
    }

    @Test
    public void testStridedSliceEdgeCase(){
        INDArray in = Nd4j.scalar(10.0).reshape(1);   //Int [1]
        INDArray begin = Nd4j.ones(DataType.INT, 1);
        INDArray end = Nd4j.zeros(DataType.INT, 1);
        INDArray stride = Nd4j.ones(DataType.INT, 1);

        DynamicCustomOp op = DynamicCustomOp.builder("strided_slice")
                .addInputs(in, begin, end, stride)
                .addIntegerArguments(0, //Begin mask
                        0,  //Ellipsis mask
                        1,  //End mask
                        0,  //New axis mask
                        0)  //Shrink axis mask
                //.addOutputs(Nd4j.empty(DataType.INT))
                .build();

        List<LongShapeDescriptor> l = op.calculateOutputShape();
        assertEquals(1, l.size());
        assertEquals(DataType.DOUBLE, l.get(0).dataType());
        assertTrue(l.get(0).isEmpty()); //Should be empty array, is rank 0 scalar

        Nd4j.exec(op);  //Execution is OK
    }



    @Test
    public void testDepthwise(){
        INDArray input = Nd4j.create(DataType.DOUBLE, 1,3,8,8);
        INDArray depthwiseWeight = Nd4j.create(DataType.DOUBLE, 1,1,3,2);
        INDArray bias = Nd4j.create(DataType.DOUBLE, 1, 6);

        INDArray[] inputs = new INDArray[]{input, depthwiseWeight, bias};

        int[] args = {1, 1, 1, 1, 0, 0, 1, 1, 0};

        INDArray output = Nd4j.create(DataType.DOUBLE, 1, 6, 8, 8);

        CustomOp op = DynamicCustomOp.builder("depthwise_conv2d")
                .addInputs(inputs)
                .addIntegerArguments(args)
                .addOutputs(output)
                .callInplace(false)
                .build();

        for( int i=0; i<1000; i++ ) {
            System.out.println(i);
            Nd4j.getExecutioner().exec(op);
        }
    }

    @Test
    public void testMod_1() {
        val x = Nd4j.createFromArray(5.f, 6.f, 7.f);
        val y = Nd4j.scalar(4.f);
        val e = Nd4j.createFromArray(1.f, 2.f, 3.f);

        val z = Nd4j.exec(new ModOp(new INDArray[]{x, y}, new INDArray[]{}))[0];

        assertEquals(e, z);
    }

    @Test
    public void testScalarVector_edge_1() {
        val x = Nd4j.scalar(2.0f);
        val y = Nd4j.createFromArray(new float[]{2.0f});
        val e = Nd4j.createFromArray(new float[]{4.0f});

        val z = Nd4j.exec(new AddOp(new INDArray[]{x, y}, new INDArray[]{}))[0];

        assertTrue(Shape.shapeEquals(e.shape(), z.shape()));
        assertEquals(e, z);
    }

    @Test
    public void testScalarVector_edge_2() {
        val x = Nd4j.scalar(2.0f);
        val y = Nd4j.createFromArray(new float[]{2.0f});
        val e = Nd4j.createFromArray(new float[]{4.0f});

        val z = Nd4j.exec(new AddOp(new INDArray[]{y, x}, new INDArray[]{}))[0];

        assertTrue(Shape.shapeEquals(e.shape(), z.shape()));
        assertEquals(e, z);
    }

    @Test(expected = RuntimeException.class)
    public void testInputValidationMergeMax(){
        INDArray[] inputs = new INDArray[]{
                Nd4j.createFromArray(0.0f, 1.0f, 2.0f).reshape('c', 1, 3),
                Nd4j.createFromArray(1.0f).reshape('c', 1, 1)};

        INDArray out = Nd4j.create(DataType.FLOAT, 1, 3).assign(Double.NaN);
        CustomOp op = DynamicCustomOp.builder("mergemax")
                .addInputs(inputs)
                .addOutputs(out)
                .callInplace(false)
                .build();

        Nd4j.exec(op);
        System.out.println(out);
    }

    @Test
    public void testUpsampling2dBackprop(){

        Nd4j.getRandom().setSeed(12345);
        int c = 2;
        int[] sz = {2,2};
        long[] inSize = {1, c, 3, 3};
        INDArray eps = Nd4j.rand(DataType.FLOAT, 1, c, sz[0] * inSize[2], sz[1] * inSize[3]);

        INDArray input = Nd4j.create(inSize);    //Unused, not sure why this is even an arg...
        INDArray exp = Nd4j.create(DataType.FLOAT, inSize);

        for( int ch=0; ch<c; ch++ ) {
            for( int h=0; h<eps.size(2); h++ ){
                for( int w=0; w<eps.size(3); w++ ){
                    int[] from = new int[]{0, ch, h, w};
                    int[] to = new int[]{0, ch, h/sz[0], w/sz[1]};
                    float add = eps.getFloat(from);
                    float current = exp.getFloat(to);
                    exp.putScalar(to, current + add);
                }
            }
        }

        System.out.println("Eps:");
        System.out.println(eps.shapeInfoToString());
        System.out.println(Arrays.toString(eps.data().asFloat()));

        System.out.println("Expected:");
        System.out.println(exp.shapeInfoToString());
        System.out.println(Arrays.toString(exp.data().asFloat()));

        DynamicCustomOp op = DynamicCustomOp.builder("upsampling2d_bp")
                .addInputs(input, eps)
                .addOutputs(exp.ulike())
                .addIntegerArguments(1) //1 = NCHW
                .build();

        Nd4j.exec(op);

        INDArray act = op.getOutputArgument(0);
        assertEquals(exp, act);
    }

    @Test
    public void testIsMaxView(){
        INDArray predictions = Nd4j.rand(DataType.FLOAT, 3, 4, 3, 2);

        INDArray row = predictions.get(NDArrayIndex.point(0), NDArrayIndex.all(), NDArrayIndex.point(0), NDArrayIndex.point(0));
        row = row.reshape(1, row.length());
        assertArrayEquals(new long[]{1, 4}, row.shape());

        val result1 = row.ulike();
        val result2 = row.ulike();

        Nd4j.exec(new IsMax(row.dup(), result1, 1));        //OK
        Nd4j.exec(new IsMax(row, result2, 1));              //C++ exception

        assertEquals(result1, result2);
    }

    @Test
    public void isMax4d_2dims(){
        Nd4j.getRandom().setSeed(12345);
        INDArray in = Nd4j.rand(DataType.FLOAT, 3, 3, 4, 4).permute(0, 2, 3, 1);

        INDArray out_permutedIn = in.like();
        INDArray out_dupedIn = in.like();

        Nd4j.exec(new IsMax(in.dup(), out_dupedIn, 2, 3));
        Nd4j.exec(new IsMax(in, out_permutedIn, 2, 3));

        assertEquals(out_dupedIn, out_permutedIn);
    }

    @Test
    public void testSizeTypes(){
        List<DataType> failed = new ArrayList<>();
        for(DataType dt : new DataType[]{DataType.LONG, DataType.INT, DataType.SHORT, DataType.BYTE,
                DataType.UINT64, DataType.UINT32, DataType.UINT16, DataType.UBYTE,
                DataType.DOUBLE, DataType.FLOAT, DataType.HALF, DataType.BFLOAT16}) {

            INDArray in = Nd4j.create(DataType.FLOAT, 100);
            INDArray out = Nd4j.scalar(dt, 0);
            INDArray e = Nd4j.scalar(dt, 100);

            DynamicCustomOp op = DynamicCustomOp.builder("size")
                    .addInputs(in)
                    .addOutputs(out)
                    .build();

            try {
                Nd4j.exec(op);

                assertEquals(e, out);
            } catch (Throwable t){
                failed.add(dt);
            }
        }

        if(!failed.isEmpty()){
            fail("Failed datatypes: " + failed.toString());
        }
    }

    @Test
    public void testListDiff(){
        INDArray x = Nd4j.createFromArray(0, 1, 2, 3);
        INDArray y = Nd4j.createFromArray(3, 1);

        INDArray out = Nd4j.create(DataType.INT, 2);
        INDArray outIdx = Nd4j.create(DataType.INT, 2);

        Nd4j.exec(DynamicCustomOp.builder("listdiff")
                .addInputs(x, y)
                .addOutputs(out, outIdx)
                .build());

        INDArray exp = Nd4j.createFromArray(0, 2);

        assertEquals(exp, out);         //Values in x not in y
        assertEquals(exp, outIdx);      //Indices of the values in x not in y
    }

    @Test
    public void testTopK1(){
        INDArray x = Nd4j.createFromArray(0.0, 0.0, 0.0, 10.0, 0.0);
        INDArray k = Nd4j.scalar(1);
        INDArray outValue = Nd4j.create(DataType.DOUBLE, 1);
        INDArray outIdx = Nd4j.create(DataType.INT, 1);

        Nd4j.exec(DynamicCustomOp.builder("top_k")
                .addInputs(x, k)
                .addOutputs(outValue, outIdx)
                .addBooleanArguments(false) //not sorted
                .addIntegerArguments(1)
                .build());

        INDArray expValue = Nd4j.createFromArray(10.0);
        INDArray expIdx = Nd4j.createFromArray(3);

        assertEquals(expValue, outValue);
        assertEquals(expIdx, outIdx);
    }

    @Test
    public void testMaxPool2Dbp_1() {
        val x = Nd4j.create(DataType.HALF, 2,3,16,16).assign(Double.NaN);
        val y = Nd4j.create(DataType.HALF, 2,3,8,8).assign(Double.NaN);
        val z = Nd4j.create(DataType.HALF, 2,3,16,16);

        val op = DynamicCustomOp.builder("maxpool2d_bp")
                .addInputs(x, y)
                .addOutputs(z)
                .addIntegerArguments(2, 2, 2, 2, 8,8, 1,1,1, 0,0)
                .build();

        Nd4j.exec(op);
        Nd4j.getExecutioner().commit();
    }

    @Test
    public void test() throws Exception {

        INDArray in1 = Nd4j.create(DataType.BFLOAT16, 2, 3, 10, 1);//Nd4j.createFromArray(0.2019043,0.6464844,0.9116211,0.60058594,0.34033203,0.7036133,0.6772461,0.3815918,0.87353516,0.04650879,0.67822266,0.8618164,0.88378906,0.7573242,0.66796875,0.63427734,0.33764648,0.46923828,0.62939453,0.76464844,-0.8618164,-0.94873047,-0.9902344,-0.88916016,-0.86572266,-0.92089844,-0.90722656,-0.96533203,-0.97509766,-0.4975586,-0.84814453,-0.984375,-0.98828125,-0.95458984,-0.9472656,-0.91064453,-0.80859375,-0.83496094,-0.9140625,-0.82470703,0.4802246,0.45361328,0.28125,0.28320312,0.79345703,0.44604492,-0.30273438,0.11730957,0.56396484,0.73583984,0.1418457,-0.44848633,0.6923828,-0.40234375,0.40185547,0.48632812,0.14538574,0.4638672,0.13000488,0.5058594)
                //.castTo(DataType.BFLOAT16).reshape(2,3,10,1);
        INDArray in2 = Nd4j.create(DataType.BFLOAT16, 2, 3, 10, 1); //Nd4j.createFromArray(0.0,-0.13391113,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,-0.1751709,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.51904297,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.5107422,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0)
                //.castTo(DataType.BFLOAT16).reshape(2,3,10,1);

        INDArray out = in1.ulike();

        Nd4j.exec(DynamicCustomOp.builder("maxpool2d_bp")
                .addInputs(in1, in2)
                .addOutputs(out)
                .addIntegerArguments(5,1,1,2,2,0,1,1,1,0,0)
                .build());

        Nd4j.getExecutioner().commit();
    }
}
