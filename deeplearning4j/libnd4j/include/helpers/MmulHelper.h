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
// @author Yurii Shyrma (iuriish@yahoo.com), created on 05.06.2018
//

#ifndef LIBND4J_MMULHELPER_H
#define LIBND4J_MMULHELPER_H

#include "NDArray.h"

namespace nd4j {
    class MmulHelper {

    private:

        // multiptication N-dimensions tensor on other N-dimensions one
        static nd4j::NDArray* mmulNxN(const nd4j::NDArray* A, const nd4j::NDArray* B, nd4j::NDArray* C, const double alpha = 1.0, const double beta = 0.0, const char outOrder = 'f');

        // dot product of vectors (X * Y) = Z[0]
        static nd4j::NDArray* dot(const nd4j::NDArray* X, const nd4j::NDArray* Y, nd4j::NDArray* Z, const double alpha = 1.0, const double beta = 0.0);

        // multiptication Matrix to Matrix
        static nd4j::NDArray* mmulMxM(const nd4j::NDArray* A, const nd4j::NDArray* B, nd4j::NDArray* C, double alpha = 1.0, double beta = 0.0, const char outOrder = 'f');

        // multiptication Matrix to vector
        static nd4j::NDArray* mmulMxV(const nd4j::NDArray* A, const nd4j::NDArray* B, nd4j::NDArray* C, double alpha = 1.0, double beta = 0.0, const char outOrder = 'f');

    public:

        static nd4j::NDArray* mmul(const nd4j::NDArray* A, const nd4j::NDArray* B, nd4j::NDArray* C = nullptr, const double alpha = 1.0, const double beta = 0.0, const char outOrder = 'f');

        static nd4j::NDArray* tensorDot(const nd4j::NDArray* A, const nd4j::NDArray* B, const std::initializer_list<int>& axesA, const std::initializer_list<int>& axesB = {});

        static nd4j::NDArray* tensorDot(const nd4j::NDArray* A, const nd4j::NDArray* B, const std::vector<int>& axesA, const std::vector<int>& axesB);

        static void tensorDot(const nd4j::NDArray* a, const nd4j::NDArray* b, nd4j::NDArray* c, const std::vector<int>& axes_a, const std::vector<int>& axes_b, const std::vector<int>& permutForC = {});


#ifndef __JAVACPP_HACK__
        /**
        *  modif - (can be empty) vector containing a subsequence of permutation/reshaping arrays (in any order), user must take care of correctness of such arrays by himself 
        */
        static void tensorDot(const nd4j::NDArray* a, const nd4j::NDArray* b, nd4j::NDArray* c, const std::vector<std::vector<Nd4jLong>>& modifA, const std::vector<std::vector<Nd4jLong>>& modifB, const std::vector<std::vector<Nd4jLong>>& modifC);
        static nd4j::NDArray* tensorDot(const nd4j::NDArray* a, const nd4j::NDArray* b, const std::vector<std::vector<Nd4jLong>>& modifA, const std::vector<std::vector<Nd4jLong>>& modifB);
#endif

        static void matmul(const nd4j::NDArray* x, const nd4j::NDArray* y, nd4j::NDArray* z, const bool transX, const bool transY);
    };
}


#endif //LIBND4J_MMULHELPER_H