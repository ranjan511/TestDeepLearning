/*******************************************************************************
 * Copyright (c) 2015-2019 Skymind, Inc.
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

package org.deeplearning4j.nn.mkldnn;

import org.deeplearning4j.BaseDL4JTest;
import org.deeplearning4j.LayerHelperValidationUtil;
import org.deeplearning4j.TestUtils;
import org.deeplearning4j.datasets.iterator.impl.SingletonDataSetIterator;
import org.deeplearning4j.nn.conf.ConvolutionMode;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.distribution.NormalDistribution;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.junit.Ignore;
import org.junit.Test;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assume.assumeTrue;

public class ValidateMKLDNN extends BaseDL4JTest {

    @Test
    public void validateConvSubsampling() throws Exception {
        //Only run test if using nd4j-native backend
        assumeTrue(Nd4j.getBackend().getClass().getName().toLowerCase().contains("native"));
        Nd4j.setDefaultDataTypes(DataType.FLOAT, DataType.FLOAT);
        Nd4j.getRandom().setSeed(12345);

        int[] inputSize = {-1, 3, 16, 16};

        for(int minibatch : new int[]{1,3}) {
            for (ConvolutionMode cm : new ConvolutionMode[]{ConvolutionMode.Same, ConvolutionMode.Truncate}) {
                for (int[] kernel : new int[][]{{2, 2}, {2, 3}}) {
                    for (int[] stride : new int[][]{{1, 1}, {2, 2}}) {
                        for (PoolingType pt : new PoolingType[]{PoolingType.MAX, PoolingType.AVG}) {

                            inputSize[0] = minibatch;
                            INDArray f = Nd4j.rand(DataType.FLOAT, inputSize);
                            INDArray l = TestUtils.randomOneHot(minibatch, 10).castTo(DataType.FLOAT);

                            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                                    .updater(new Adam(0.01))
                                    .convolutionMode(cm)
                                    .seed(12345)
                                    .list()
                                    .layer(new ConvolutionLayer.Builder().activation(Activation.TANH)
                                            .kernelSize(kernel)
                                            .stride(stride)
                                            .padding(0, 0)
                                            .nOut(3)
                                            .build())
                                    .layer(new SubsamplingLayer.Builder()
                                            .poolingType(pt)
                                            .kernelSize(kernel)
                                            .stride(stride)
                                            .padding(0, 0)
                                            .build())
                                    .layer(new ConvolutionLayer.Builder().activation(Activation.TANH)
                                            .kernelSize(kernel)
                                            .stride(stride)
                                            .padding(0, 0)
                                            .nOut(3)
                                            .build())
                                    .layer(new OutputLayer.Builder().nOut(10).activation(Activation.SOFTMAX).lossFunction(LossFunctions.LossFunction.MCXENT).build())
                                    .setInputType(InputType.convolutional(inputSize[2], inputSize[3], inputSize[1]))
                                    .build();

                            MultiLayerNetwork netWith = new MultiLayerNetwork(conf.clone());
                            netWith.init();

                            MultiLayerNetwork netWithout = new MultiLayerNetwork(conf.clone());
                            netWithout.init();

                            String name = pt + ", mb=" + minibatch + ", cm=" + cm + ", kernel=" + Arrays.toString(kernel) + ", stride=" + Arrays.toString(stride);
                            LayerHelperValidationUtil.TestCase tc = LayerHelperValidationUtil.TestCase.builder()
                                    .testName(name)
                                    .allowHelpersForClasses(Arrays.<Class<?>>asList(org.deeplearning4j.nn.layers.convolution.subsampling.SubsamplingLayer.class,
                                            org.deeplearning4j.nn.layers.convolution.ConvolutionLayer.class))
                                    .testForward(true)
                                    .testScore(true)
                                    .testBackward(true)
                                    .testTraining(true)
                                    .features(f)
                                    .labels(l)
                                    .data(new SingletonDataSetIterator(new DataSet(f, l)))
                                    .build();

                            System.out.println("Starting test: " + name);
                            LayerHelperValidationUtil.validateMLN(netWith, tc);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void validateBatchNorm() {
        //Only run test if using nd4j-native backend
        assumeTrue(Nd4j.getBackend().getClass().getName().toLowerCase().contains("native"));
        Nd4j.setDefaultDataTypes(DataType.FLOAT, DataType.FLOAT);
        Nd4j.getRandom().setSeed(12345);

        int[] inputSize = {-1, 3, 16, 16};
        int[] stride = {1, 1};
        int[] kernel = {2, 2};
        ConvolutionMode cm = ConvolutionMode.Truncate;

        for (int minibatch : new int[]{1, 3}) {

            inputSize[0] = minibatch;
            INDArray f = Nd4j.rand(Nd4j.defaultFloatingPointType(), inputSize);
            INDArray l = TestUtils.randomOneHot(minibatch, 10);

            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                    .updater(new Adam(0.01))
                    .convolutionMode(cm)
                    .seed(12345)
                    .list()
                    .layer(new ConvolutionLayer.Builder().activation(Activation.TANH)
                            .kernelSize(kernel)
                            .stride(stride)
                            .padding(0, 0)
                            .nOut(3)
                            .build())
                    .layer(new BatchNormalization.Builder().cudnnAllowFallback(false).build())
                    .layer(new ConvolutionLayer.Builder().activation(Activation.TANH)
                            .kernelSize(kernel)
                            .stride(stride)
                            .padding(0, 0)
                            .nOut(3)
                            .build())
                    .layer(new OutputLayer.Builder().nOut(10).activation(Activation.SOFTMAX).lossFunction(LossFunctions.LossFunction.MCXENT).build())
                    .setInputType(InputType.convolutional(inputSize[2], inputSize[3], inputSize[1]))
                    .build();

            MultiLayerNetwork netWith = new MultiLayerNetwork(conf.clone());
            netWith.init();

            MultiLayerNetwork netWithout = new MultiLayerNetwork(conf.clone());
            netWithout.init();

            LayerHelperValidationUtil.TestCase tc = LayerHelperValidationUtil.TestCase.builder()
                    .allowHelpersForClasses(Collections.<Class<?>>singletonList(org.deeplearning4j.nn.layers.normalization.BatchNormalization.class))
                    .testForward(true)
                    .testScore(true)
                    .testBackward(true)
                    .testTraining(true)
                    .features(f)
                    .labels(l)
                    .data(new SingletonDataSetIterator(new DataSet(f, l)))
                    .maxRelError(1e-4)
                    .build();

            LayerHelperValidationUtil.validateMLN(netWith, tc);
        }
    }

    @Test @Ignore   //https://github.com/deeplearning4j/deeplearning4j/issues/7272
    public void validateLRN() {

        //Only run test if using nd4j-native backend
        assumeTrue(Nd4j.getBackend().getClass().getName().toLowerCase().contains("native"));
        Nd4j.setDefaultDataTypes(DataType.FLOAT, DataType.FLOAT);
        Nd4j.getRandom().setSeed(12345);

        int[] inputSize = {-1, 3, 16, 16};
        int[] stride = {1, 1};
        int[] kernel = {2, 2};
        ConvolutionMode cm = ConvolutionMode.Truncate;

        double[] a = new double[]{1e-4, 1e-4, 1e-3, 1e-3};
        double[] b = new double[]{0.75, 0.9, 0.75, 0.75};
        double[] n = new double[]{5, 3, 3, 4};
        double[] k = new double[]{2, 2.5, 2.75, 2};

        for (int minibatch : new int[]{1, 3}) {
            for( int i=0; i<a.length; i++ ) {
                System.out.println("+++++ MINIBATCH = " + minibatch + ", TEST=" + i + " +++++");


                inputSize[0] = minibatch;
                INDArray f = Nd4j.rand(Nd4j.defaultFloatingPointType(), inputSize);
                INDArray l = TestUtils.randomOneHot(minibatch, 10).castTo(DataType.FLOAT);

                MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                        .updater(new Adam(0.01))
                        .convolutionMode(cm)
                        .weightInit(new NormalDistribution(0,1))
                        .seed(12345)
                        .list()
                        .layer(new ConvolutionLayer.Builder().activation(Activation.TANH)
                                .kernelSize(kernel)
                                .stride(stride)
                                .padding(0, 0)
                                .nOut(3)
                                .build())
                        .layer(new LocalResponseNormalization.Builder()
                                .alpha(a[i])
                                .beta(b[i])
                                .n(n[i])
                                .k(k[i])
                                .cudnnAllowFallback(false).build())
                        .layer(new OutputLayer.Builder().nOut(10).activation(Activation.SOFTMAX).lossFunction(LossFunctions.LossFunction.MCXENT).build())
                        .setInputType(InputType.convolutional(inputSize[2], inputSize[3], inputSize[1]))
                        .build();

                MultiLayerNetwork netWith = new MultiLayerNetwork(conf.clone());
                netWith.init();

                MultiLayerNetwork netWithout = new MultiLayerNetwork(conf.clone());
                netWithout.init();

                LayerHelperValidationUtil.TestCase tc = LayerHelperValidationUtil.TestCase.builder()
                        .allowHelpersForClasses(Collections.<Class<?>>singletonList(org.deeplearning4j.nn.layers.normalization.LocalResponseNormalization.class))
                        .testForward(true)
                        .testScore(true)
                        .testBackward(true)
                        .testTraining(true)
                        .features(f)
                        .labels(l)
                        .data(new SingletonDataSetIterator(new DataSet(f, l)))
                        //Very infrequent minor differences - as far as I can tell, just numerical precision issues...
                        .minAbsError(1e-3)
                        .maxRelError(1e-2)
                        .build();

                LayerHelperValidationUtil.validateMLN(netWith, tc);

                System.out.println("/////////////////////////////////////////////////////////////////////////////");
            }
        }
    }
}
