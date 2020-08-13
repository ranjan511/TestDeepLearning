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
// @author raver119@gmail.com
//

#include <ops/declarable/helpers/histogram.h>

namespace nd4j {
    namespace ops {
        namespace helpers {
            template <typename X, typename Z>
            static void histogram_(void *xBuffer, Nd4jLong *xShapeInfo, void *zBuffer, Nd4jLong *zShapeInfo, Nd4jLong numBins, double min_val, double max_val) {
                auto dx = reinterpret_cast<X*>(xBuffer);
                auto result = reinterpret_cast<Z*>(zBuffer);

                int length = shape::length(xShapeInfo);
                // FIXME: 2???
                int _threads = 2;

                int span = (length / _threads) + 8;

                X binSize = (max_val - min_val) / (numBins);

                PRAGMA_OMP_PARALLEL_THREADS(_threads)
                {
                    int tid, start, end;

                    int *bins = new int[numBins];
                    std::memset(bins, 0, sizeof(int) * numBins);
                    tid = omp_get_thread_num();
                    start = span * tid;
                    end = span * (tid + 1);
                    if (end > length) end = length;

                    PRAGMA_OMP_SIMD
                    for (int x = start; x < end; x++) {
                        int idx = (int) ((dx[x] - min_val) / binSize);
                        if (idx < 0)
                            idx = 0;
                        else if (idx >= numBins)
                            idx = numBins - 1;

                        bins[idx]++;
                    }

                    PRAGMA_OMP_CRITICAL
                    {
                        PRAGMA_OMP_SIMD
                        for (int x = 0; x < numBins; x++) {
                            result[x] += bins[x];
                        }

                    }

                    delete[] bins;
                }
            }

            void histogramHelper(nd4j::LaunchContext *context, NDArray &input, NDArray &output) {
                Nd4jLong numBins = output.lengthOf();
                double min_val = input.reduceNumber(reduce::SameOps::Min).e<double>(0);
                double max_val = input.reduceNumber(reduce::SameOps::Max).e<double>(0);

                BUILD_DOUBLE_SELECTOR(input.dataType(), output.dataType(), histogram_, (input.buffer(), input.shapeInfo(), output.getBuffer(), output.getShapeInfo(), numBins, min_val, max_val), LIBND4J_TYPES, INDEXING_TYPES);
            }
        }
    }
}