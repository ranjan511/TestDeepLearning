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

package org.deeplearning4j.rl4j.observation.preprocessor.pooling;

import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

/**
 * ChannelStackPoolContentAssembler is used with the PoolingDataSetPreProcessor. This assembler will
 * stack along the dimension 0. For example if the pool elements are of shape [ Height, Width ]
 * the output will be of shape [ Stacked, Height, Width ]
 *
 * @author Alexandre Boulanger
 */
public class ChannelStackPoolContentAssembler implements PoolContentAssembler {

    /**
     * Will return a new INDArray with one more dimension and with poolContent stacked along dimension 0.
     *
     * @param poolContent Array of INDArray
     * @return A new INDArray with 1 more dimension than the input elements
     */
    @Override
    public INDArray assemble(INDArray[] poolContent)
    {
        // build the new shape
        long[] elementShape = poolContent[0].shape();
        long[] newShape = new long[elementShape.length + 1];
        newShape[0] = poolContent.length;
        System.arraycopy(elementShape, 0, newShape, 1, elementShape.length);

        // put pool elements in result
        INDArray result = Nd4j.create(newShape);
        for(int i = 0; i < poolContent.length; ++i) {
            result.putRow(i, poolContent[i]);
        }
        return result;
    }
}
