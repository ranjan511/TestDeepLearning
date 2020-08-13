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

#include <array/ConstantDescriptor.h>
#include <DataTypeUtils.h>
#include <stdexcept>

namespace nd4j {
    ConstantDescriptor::ConstantDescriptor(double* values, int length) {
        for (int e = 0; e < length; e++)
            _floatValues.emplace_back(values[e]);
    }

    ConstantDescriptor::ConstantDescriptor(Nd4jLong * values, int length) {
        for (int e = 0; e < length; e++)
            _integerValues.emplace_back(values[e]);
    }

    ConstantDescriptor::ConstantDescriptor(std::initializer_list<double> values) {
        _floatValues = values;
    }

    ConstantDescriptor::ConstantDescriptor(std::vector<Nd4jLong> &values) {
        _integerValues = values;
    }

    ConstantDescriptor::ConstantDescriptor(std::vector<double> &values) {
        _floatValues = values;
    }

    // equal to operator
    bool ConstantDescriptor::operator==(const ConstantDescriptor &other) const {
        return std::tie(_floatValues, _integerValues) == std::tie(other._floatValues, other._integerValues);
    }

    // less than operator
    bool ConstantDescriptor::operator<(const ConstantDescriptor &other) const {
        return std::tie(_floatValues, _integerValues) < std::tie(other._floatValues, other._integerValues);
    }

    bool ConstantDescriptor::isInteger() const {
        return !_integerValues.empty();
    }

    bool ConstantDescriptor::isFloat() const {
        return !_floatValues.empty();
    }

    const std::vector<Nd4jLong>& ConstantDescriptor::integerValues() const {
        return _integerValues;
    }

    const std::vector<double>& ConstantDescriptor::floatValues() const {
        return _floatValues;
    }

    Nd4jLong ConstantDescriptor::length() const {
        return isInteger() ? _integerValues.size() : isFloat() ? _floatValues.size() : 0L;
    }
}
