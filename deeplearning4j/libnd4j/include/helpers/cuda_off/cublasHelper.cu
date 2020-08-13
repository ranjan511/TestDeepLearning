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


#include <cublas_v2.h>
#include <cusolverDn.h>
#include "../cublasHelper.h"
#include <exceptions/cuda_exception.h>
#include <helpers/logger.h>
#include <execution/AffinityManager.h>

namespace nd4j {
    std::mutex CublasHelper::_mutex;

    static void* handle_() {
        auto _handle = new cublasHandle_t();
        auto status = cublasCreate_v2(_handle); // initialize CUBLAS context
        if (status != CUBLAS_STATUS_SUCCESS)
            throw cuda_exception::build("cuBLAS handle creation failed !", status);

        return reinterpret_cast<void *>(_handle);
    }

    static void* solver_() {
        auto cusolverH = new cusolverDnHandle_t();
        auto status = cusolverDnCreate(cusolverH);
        if (status != CUSOLVER_STATUS_SUCCESS)
            throw cuda_exception::build("cuSolver handle creation failed !", status);

        return cusolverH;
    }

    static void destroyHandle_(void* handle) {
        auto ch = reinterpret_cast<cublasHandle_t *>(handle);
        auto status = cublasDestroy_v2(*ch);
        if (status != CUBLAS_STATUS_SUCCESS)
            throw cuda_exception::build("cuBLAS handle destruction failed !", status);

        delete ch;
    }

    CublasHelper::CublasHelper() {
        //nd4j_printf("Initializing cuBLAS\n","");
        auto numDevices = AffinityManager::numberOfDevices();
        auto currentDevice = AffinityManager::currentDeviceId();
        _cache.resize(numDevices);
        _solvers.resize(numDevices);
        for (int e = 0; e < numDevices; e++) {
            AffinityManager::setCurrentNativeDevice(e);

            _cache[e] = handle_();
            _solvers[e] = solver_();
        }

        // don't forget to restore back original device
        AffinityManager::setCurrentNativeDevice(currentDevice);
    }

    CublasHelper::~CublasHelper() {
        nd4j_printf("Releasing cuBLAS\n","");
        auto numDevices = AffinityManager::numberOfDevices();

        for (int e = 0; e < numDevices; e++)
            destroyHandle_(_cache[e]);
    }

    CublasHelper* CublasHelper::getInstance() {
        _mutex.lock();
        if (!_INSTANCE)
            _INSTANCE = new nd4j::CublasHelper();
        _mutex.unlock();

        return _INSTANCE;
    }

    void* CublasHelper::handle() {
        auto deviceId = AffinityManager::currentDeviceId();
        return handle(deviceId);
    }

    void* CublasHelper::solver() {
        auto deviceId = AffinityManager::currentDeviceId();
        if (deviceId < 0 || deviceId > _solvers.size())
            throw cuda_exception::build("requested deviceId doesn't look valid", deviceId);

        return _solvers[deviceId];
    }

    void* CublasHelper::handle(int deviceId) {
        if (deviceId < 0 || deviceId > _cache.size())
            throw cuda_exception::build("requested deviceId doesn't look valid", deviceId);

        return _cache[deviceId];
    }


    nd4j::CublasHelper* nd4j::CublasHelper::_INSTANCE = 0;
}