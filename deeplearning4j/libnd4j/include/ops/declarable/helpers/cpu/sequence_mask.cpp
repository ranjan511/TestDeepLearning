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

//
//  @author GS <sgazeos@gmail.com>
//

#include <ops/declarable/helpers/sequence_mask.h>

namespace nd4j {
namespace ops {
namespace helpers {

    template <typename I, typename B>
    static void sequenceMask_(NDArray* input, NDArray* output, int maxIndex) {
        PRAGMA_OMP_PARALLEL_FOR_SIMD_COLLAPSE(2)
        for (Nd4jLong i = 0; i < maxIndex; i++)
            for(Nd4jLong k = 0; k < input->lengthOf(); k++)
                if (i < input->t<I>(k))
                    output->t<B>(k * maxIndex + i) = B(true); //,  T(1.0f));
    }

    void sequenceMask(nd4j::LaunchContext * context, NDArray* input, NDArray* output, int maxIndex) {
        BUILD_DOUBLE_SELECTOR(input->dataType(), output->dataType(), sequenceMask_, (input, output, maxIndex), INTEGER_TYPES, BOOL_TYPES);
    }

    BUILD_DOUBLE_TEMPLATE(template void sequenceMask_, (NDArray* input, NDArray* output, int maxIndex), INTEGER_TYPES, BOOL_TYPES);
}
}
}