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
// @author Yurii Shyrma (iuriish@yahoo.com), created on 31.08.2018
//

#include <ops/declarable/helpers/histogramFixedWidth.h>

namespace nd4j {
namespace ops {
namespace helpers {


template <typename T>
void histogramFixedWidth_(const NDArray& input, const NDArray& range, NDArray& output) {

    const int nbins = output.lengthOf();

    // firstly initialize output with zeros
    output.nullify();

    const T leftEdge  = range.e<double>(0);
    const T rightEdge = range.e<double>(1);

    const T binWidth       = (rightEdge - leftEdge ) / nbins;
    const T secondEdge     = leftEdge + binWidth;
    const T lastButOneEdge = rightEdge - binWidth;

    Nd4jLong inputLength = input.lengthOf();

    PRAGMA_OMP_PARALLEL_FOR
    for(Nd4jLong i = 0; i < inputLength; ++i) {

        const T value = input.e<T>(i);

        if(value < secondEdge) {

            PRAGMA_OMP_CRITICAL
            {
                output.p<Nd4jLong>(0, output.e<Nd4jLong>(0) + 1);
            }
        } else if(value >= lastButOneEdge) {
            PRAGMA_OMP_CRITICAL
            {
                output.p<Nd4jLong>(nbins - 1, output.e<Nd4jLong>(nbins - 1) + 1);
            }
        } else {
            Nd4jLong currInd = static_cast<Nd4jLong>((value - leftEdge) / binWidth);

            PRAGMA_OMP_CRITICAL
            {
                output.p<Nd4jLong>(currInd, output.e<Nd4jLong>(currInd) + 1);
            }
        }
    }
}

void histogramFixedWidth(nd4j::LaunchContext * context, const NDArray& input, const NDArray& range, NDArray& output) {
    BUILD_SINGLE_SELECTOR(input.dataType(), histogramFixedWidth_, (input, range, output), LIBND4J_TYPES);
}
BUILD_SINGLE_TEMPLATE(template void histogramFixedWidth_, (const NDArray& input, const NDArray& range, NDArray& output), LIBND4J_TYPES);


}
}
}