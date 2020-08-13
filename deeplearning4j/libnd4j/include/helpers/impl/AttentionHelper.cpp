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

//
// @author Paul Dubs
//

#ifndef LIBND4J_ATTENTIONHELPER_CPP
#define LIBND4J_ATTENTIONHELPER_CPP

#include <helpers/AttentionHelper.h>

#include "../AttentionHelper.h"
#include <ops/declarable/CustomOperations.h>

namespace nd4j {

    nd4j::NDArray AttentionHelper::multiHeadProject(const nd4j::NDArray *input, const nd4j::NDArray *projectionMatrix, nd4j::LaunchContext * context) {
        auto miniBatchSize = input->sizeAt(0);
        auto seqLength = input->sizeAt(2);
        auto numHeads = projectionMatrix->sizeAt(0);
        auto projectedSize = projectionMatrix->sizeAt(1);

        auto inputPerm = input->permute({1, 0, 2});
        auto inputPrep = inputPerm.reshape('c', {input->sizeAt(1), (miniBatchSize * seqLength)});
        auto projectionPrep = projectionMatrix->reshape('c', {numHeads * projectionMatrix->sizeAt(1), projectionMatrix->sizeAt(2)});

        NDArray projected('c', {numHeads * projectionMatrix->sizeAt(1), (miniBatchSize * seqLength)}, input->dataType(), context);
        nd4j::ops::matmul mmul;
        mmul.execute({&projectionPrep, &inputPrep}, {&projected},  {}, {}, {});

        projected.reshapei({numHeads, projectedSize, miniBatchSize, seqLength});
        projected.permutei({2, 0, 1, 3});

        return projected;
    }

    void AttentionHelper::multiHeadProjectBp(const nd4j::NDArray *input, const nd4j::NDArray *projectionMatrix,
                                        const nd4j::NDArray *eps, nd4j::NDArray *dLdInput,
                                        nd4j::NDArray *dLdProjectionMatrix, nd4j::LaunchContext * context) {
        auto miniBatchSize = input->sizeAt(0);
        auto seqLength = input->sizeAt(2);
        auto numHeads = projectionMatrix->sizeAt(0);
        auto projectedSize = projectionMatrix->sizeAt(1);

        auto epsPerm = eps->permute({1, 2, 0, 3});
        auto epsReshaped = epsPerm.reshape('c', {numHeads * projectedSize, miniBatchSize * seqLength});

        auto inputPerm = input->permute({1, 0, 2});
        auto inputPrep = inputPerm.reshape('c', {input->sizeAt(1), (miniBatchSize * seqLength)});
        auto projectionPrep = projectionMatrix->reshape('c', {numHeads * projectionMatrix->sizeAt(1), projectionMatrix->sizeAt(2)});

        nd4j::ops::matmul_bp mmulBp;
        NDArray dLdProjectionPrep(projectionPrep.shapeInfo(), false, context);
        NDArray dLdInputPrep(inputPrep.shapeInfo(), false, context);
        mmulBp.execute({&projectionPrep, &inputPrep, &epsReshaped}, {&dLdProjectionPrep, &dLdInputPrep}, {}, {}, {});

        dLdProjectionPrep.reshapei({numHeads, projectionMatrix->sizeAt(1), projectionMatrix->sizeAt(2)});
        dLdProjectionMatrix->assign(dLdProjectionPrep);

        dLdInputPrep.reshapei({input->sizeAt(1), miniBatchSize, seqLength});
        dLdInputPrep.permutei({1, 0, 2});
        dLdInput->assign(dLdInputPrep);
    }
}


#endif
