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
// Created by raver119 on 16.10.2017.
//

#include <ops/declarable/LegacyTransformBoolOp.h>

#include <NativeOpExecutioner.h>


namespace nd4j {
    namespace ops {
        LegacyTransformBoolOp::LegacyTransformBoolOp() : LegacyOp::LegacyOp(1) {
            // just a no-op
        }

        LegacyTransformBoolOp::LegacyTransformBoolOp(int opNum) : LegacyOp::LegacyOp(1, opNum) {
            // just a no-op
        }

        LegacyOp* LegacyTransformBoolOp::clone() {
            return new LegacyTransformBoolOp(this->_opNum);
        }

        Nd4jStatus LegacyTransformBoolOp::validateAndExecute(Context &block) {
            auto input = INPUT_VARIABLE(0);
            auto z = OUTPUT_VARIABLE(0);

            NDArray::prepareSpecialUse({z}, {input});

            int opNum = block.opNum() < 0 ? this->_opNum : block.opNum();

            ExtraArguments extras(*block.getTArguments());
            PointersManager manager(block.launchContext(),"LegacyTransformBoolOp");

            NativeOpExecutioner::execTransformBool(block.launchContext(), opNum, input->getBuffer(), input->getShapeInfo(), input->specialBuffer(), input->specialShapeInfo(),
                    z->getBuffer(), z->getShapeInfo(), z->specialBuffer(), z->specialShapeInfo(),
                    extras.argumentsAsT(input->dataType()), nullptr, nullptr);

            manager.synchronize();
            STORE_RESULT(*z);

            return Status::OK();
        }

        /**
        * For transform operations, output shape always equals to input shape. With just a few exclusions, like im2col and col2im. 
        * But these ops already have CustomOp implementations.
        *
        */
        ShapeList *LegacyTransformBoolOp::calculateOutputShape(ShapeList *inputShape, nd4j::graph::Context &block) {
            auto inShape = inputShape->at(0);
            return SHAPELIST(ConstantShapeHelper::getInstance()->createShapeInfo(ShapeDescriptor(inShape, DataType::BOOL)));
        }
    }
}