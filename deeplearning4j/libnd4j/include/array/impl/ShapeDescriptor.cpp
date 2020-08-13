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
//  @author raver119@gmail.com
//

#include "../ShapeDescriptor.h"
#include <shape.h>
#include <ShapeBuilders.h>

using namespace nd4j;

//////////////////////////////////////////////////////////////////////////
// equal to operator
bool ShapeDescriptor::operator==(const ShapeDescriptor& other) const {

    if(_empty != other._empty)
        return false;
    if(_rank != other._rank)
        return false;
    if(_order != other._order)
        return false;
    if(_dataType != other._dataType)
        return false;
    if(_ews != other._ews)
        return false;

    if(_shape != other._shape)
        return false;

    if(_strides != other._strides)
        return false;

    return true;
}

//////////////////////////////////////////////////////////////////////////
// less than operator
bool ShapeDescriptor::operator<(const ShapeDescriptor& other) const {
    return std::tie(_empty, _rank, _dataType, _ews, _order, _shape, _strides) < std::tie(other._empty, other._rank, other._dataType, other._ews, other._order, other._shape, other._strides);
}

Nd4jLong* ShapeDescriptor::toShapeInfo() const {
    if (_empty) {
        if (_rank == 0)
            return ShapeBuilders::emptyShapeInfo(_dataType);
        else {
            return ShapeBuilders::emptyShapeInfo(_dataType, _order, _shape);
        }
    }


    switch (_rank) {
        case 0: {
            auto shapeInfo = ShapeBuilders::createScalarShapeInfo(_dataType);
            shapeInfo[2] = _ews;
            return shapeInfo;
        }
        case 1: {
            auto shapeInfo = ShapeBuilders::createVectorShapeInfo(_dataType, _shape[0]);
            shapeInfo[2 + _rank * 2] = _ews;
            shapeInfo[2] = _strides[0];
            shapeInfo[2 + _rank * 2 + 1] = _order;
            return shapeInfo;
        }
        default: {
            auto shapeInfo = ShapeBuilders::createShapeInfo(_dataType, _order, _shape);

            for (int e = 0; e < _rank; e++)
                shapeInfo[e + 1 + _rank] = _strides[e];

            shapeInfo[2 + _rank * 2] = _ews;

            return shapeInfo;
        }
    }
}

ShapeDescriptor::ShapeDescriptor(const DataType type, const char order, const Nd4jLong *shape, const int rank) :  _dataType(type), _order(order), _rank(rank), _ews(1){
    _shape.resize(rank);
    _strides.resize(rank);

    for (int e = 0; e < rank; e++)
        _shape[e] = shape[e];

    if (order == 'c')
        shape::calcStrides(_shape.data(), _shape.size(), _strides.data());
    else
        shape::calcStridesFortran(_shape.data(), _shape.size(), _strides.data());


    for (auto v:_shape) {
        if (v == 0) {
            _empty = true;
            break;
        }
    }
}

ShapeDescriptor::ShapeDescriptor(const DataType type, const char order, const Nd4jLong *shape, const Nd4jLong *strides, const int rank, Nd4jLong ews, const bool empty) {
    _shape.resize(rank);
    _strides.resize(rank);

    _dataType = type;
    _order = order;
    _rank = rank;
    _empty = empty;
    _ews = ews;

    for (int e = 0; e < rank; e++)
        _shape[e] = shape[e];

    for (int e = 0; e < rank; e++)
        _strides[e] = strides[e];


    for (auto v:_shape) {
        if (v == 0) {
            _empty = true;
            break;
        }
    }
}

//////////////////////////////////////////////////////////////////////////
ShapeDescriptor::ShapeDescriptor(const DataType type, const char order, const std::vector<Nd4jLong> &shape): _dataType(type), _order(order), _shape(shape) {
    _rank = shape.size();
    _ews = 1;

    if (_rank > 0) {
        _strides.resize(_rank);

        for (auto v:_shape) {
            if (v == 0) {
                _empty = true;
                break;
            }
        }

        // no point calculating strides for empty arrays
        if (!_empty) {
            if (order == 'c')
                shape::calcStrides(_shape.data(), shape.size(), _strides.data());
            else
                shape::calcStridesFortran(_shape.data(), shape.size(), _strides.data());
        } else {
            // all strides set to 0
            memset(_strides.data(), 0, sizeof(Nd4jLong) * shape.size());
        }
    }
}

//////////////////////////////////////////////////////////////////////////
ShapeDescriptor::ShapeDescriptor(const DataType type, const char order, const std::initializer_list<Nd4jLong> &shape): _dataType(type), _order(order), _shape(shape) {
    _rank = shape.size();
    _ews = 1;

    _strides.resize(shape.size());
    if (order == 'c')
        shape::calcStrides(_shape.data(), shape.size(), _strides.data());
    else
        shape::calcStridesFortran(_shape.data(), shape.size(), _strides.data());

    for (auto v:_shape) {
        if (v == 0) {
            _empty = true;
            break;
        }
    }
}

//////////////////////////////////////////////////////////////////////////
ShapeDescriptor::ShapeDescriptor(const DataType type, const char order, const std::vector<Nd4jLong> &shape, const std::vector<Nd4jLong> &strides, const Nd4jLong ews): ShapeDescriptor(type, order, shape, strides) {
    _ews = ews;
}

ShapeDescriptor::ShapeDescriptor(const DataType type, const Nd4jLong length) : _dataType(type), _ews(1), _order('c'), _rank(1), _empty(false) {
    _shape = {length};
    _strides = {1};
}

ShapeDescriptor::ShapeDescriptor(const Nd4jLong *shapeInfo, bool inheritDtype) {
    _order = shape::order(shapeInfo);
    _ews = shape::elementWiseStride(shapeInfo);
    _rank = shape::rank(shapeInfo);

    if (inheritDtype)
        _dataType = ArrayOptions::dataType(shapeInfo);

    _empty = shape::isEmpty(shapeInfo);

    for (int e = 0; e < _rank; e++) {
        _shape.emplace_back(shapeInfo[e + 1]);
        if (shapeInfo[e + 1] == 0)
            _empty = true;
    }

    for (int e = 0; e < _rank; e++)
        _strides.emplace_back(shapeInfo[e + 1 + _rank]);
}

ShapeDescriptor::ShapeDescriptor(const Nd4jLong *shapeInfo, const nd4j::DataType dtypeOverride) : ShapeDescriptor::ShapeDescriptor(shapeInfo, false) {
    _dataType = dtypeOverride;
}

ShapeDescriptor::ShapeDescriptor(const Nd4jLong *shapeInfo, const Nd4jLong *dtypeOverride) : ShapeDescriptor::ShapeDescriptor(shapeInfo, ArrayOptions::dataType(dtypeOverride)) {
    //
}

ShapeDescriptor::ShapeDescriptor(const Nd4jLong *shapeInfo, const Nd4jLong *dtypeOverride, const Nd4jLong *orderOverride) : ShapeDescriptor::ShapeDescriptor(shapeInfo, ArrayOptions::dataType(dtypeOverride)) {
    _order = shape::order(orderOverride);
}

int ShapeDescriptor::rank() const {
    return _rank;
}

Nd4jLong ShapeDescriptor::ews() const {
    return _ews;
}

Nd4jLong ShapeDescriptor::arrLength() const {

    Nd4jLong len = 1;
    for(const auto& dim : const_cast<ShapeDescriptor*>(this)->shape())
        len *= dim;
    return len;
}

char ShapeDescriptor::order() const {
    return _order;
}

DataType ShapeDescriptor::dataType() const {
    return _dataType;
}

bool ShapeDescriptor::isEmpty() const {
    return _empty;
}
std::vector<Nd4jLong>& ShapeDescriptor::shape() {
    return _shape;
}

std::vector<Nd4jLong>& ShapeDescriptor::strides() {
    return _strides;
}

ShapeDescriptor::ShapeDescriptor(const ShapeDescriptor &other) {
    _rank = other._rank;
    _ews = other._ews;
    _empty = other._empty;
    _dataType = other._dataType;
    _order = other._order;
    _shape = other._shape;
    _strides = other._strides;
}

//////////////////////////////////////////////////////////////////////////
ShapeDescriptor::ShapeDescriptor(const DataType type, const char order, const std::vector<Nd4jLong> &shape, const std::vector<Nd4jLong> &strides): _dataType(type), _order(order), _shape(shape) {

    if (strides.empty() && !shape.empty()) {
        _strides.resize(shape.size());
        if (order == 'c')
            shape::calcStrides(_shape.data(), shape.size(), _strides.data());
        else
            shape::calcStridesFortran(_shape.data(), shape.size(), _strides.data());
    }
    else {
        _strides = strides;
    }


    for (auto v:_shape) {
        if (v == 0) {
            _empty = true;
            break;
        }
    }
}

ShapeDescriptor ShapeDescriptor::emptyDescriptor(const DataType type) {
    ShapeDescriptor descriptor;
    descriptor._dataType = type;
    descriptor._empty = true;
    descriptor._rank = 0;
    descriptor._order = 'c';
    descriptor._ews = 1;

    return descriptor;
}

ShapeDescriptor ShapeDescriptor::scalarDescriptor(const DataType type) {
    ShapeDescriptor descriptor;
    descriptor._dataType = type;
    descriptor._empty = false;
    descriptor._rank = 0;
    descriptor._order = 'c';
    descriptor._ews = 1;

    return descriptor;
}

ShapeDescriptor ShapeDescriptor::vectorDescriptor(const Nd4jLong length, const DataType type) {
    ShapeDescriptor descriptor;
    descriptor._dataType = type;
    descriptor._shape.emplace_back(length);

    if (length > 0)
        descriptor._strides.emplace_back(1);
    else {
        descriptor._strides.emplace_back(0);
        descriptor._empty = true;
    }

    descriptor._order = 'c';
    descriptor._ews = 1;
    descriptor._rank = 1;

    return descriptor;
}


